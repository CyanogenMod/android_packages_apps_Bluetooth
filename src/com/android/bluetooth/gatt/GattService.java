/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.gatt;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothGattServerCallback;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.ResultStorageDescriptor;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Provides Bluetooth Gatt profile, as a service in
 * the Bluetooth application.
 * @hide
 */
public class GattService extends ProfileService {
    private static final boolean DBG = GattServiceConfig.DBG;
    private static final boolean VDBG = GattServiceConfig.VDBG;
    private static final String TAG = GattServiceConfig.TAG_PREFIX + "GattService";

    static final int SCAN_FILTER_ENABLED = 1;
    static final int SCAN_FILTER_MODIFIED = 2;

    private static final int MAC_ADDRESS_LENGTH = 6;
    // Batch scan related constants.
    private static final int TRUNCATED_RESULT_SIZE = 11;
    private static final int TIME_STAMP_LENGTH = 2;

    // onFoundLost related constants
    private static final int ADVT_STATE_ONFOUND = 0;
    private static final int ADVT_STATE_ONLOST = 1;

    /**
     * Search queue to serialize remote onbject inspection.
     */
    SearchQueue mSearchQueue = new SearchQueue();

    /**
     * List of our registered clients.
     */

    class ClientMap extends ContextMap<IBluetoothGattCallback> {}
    ClientMap mClientMap = new ClientMap();

    /**
     * List of our registered server apps.
     */
    class ServerMap extends ContextMap<IBluetoothGattServerCallback> {}
    ServerMap mServerMap = new ServerMap();

    /**
     * Server handle map.
     */
    HandleMap mHandleMap = new HandleMap();
    private List<UUID> mAdvertisingServiceUuids = new ArrayList<UUID>();

    private int mMaxScanFilters;
    private Map<ScanClient, ScanResult> mOnFoundResults = new HashMap<ScanClient, ScanResult>();

    /**
     * Pending service declaration queue
     */
    private List<ServiceDeclaration> mServiceDeclarations = new ArrayList<ServiceDeclaration>();

    /**
     * Temporary App (active) service declaration queue
     */
    private HashMap<Integer, ServiceDeclaration> mActiveServiceDeclarations = new HashMap<Integer, ServiceDeclaration>();

    private ServiceDeclaration addToActiveDeclaration(int serverIf) {
        synchronized (mActiveServiceDeclarations) {
            mActiveServiceDeclarations.put(serverIf, new ServiceDeclaration());
        }
        return getActiveDeclaration(serverIf);
    }

    private void removeFromActiveDeclaration(int serverIf) {
        synchronized (mActiveServiceDeclarations) {
            mActiveServiceDeclarations.remove(serverIf);
        }
    }

    private ServiceDeclaration getActiveDeclaration(int serverIf) {
        synchronized (mActiveServiceDeclarations) {
            if (mActiveServiceDeclarations.size() > 0)
                return mActiveServiceDeclarations.get(serverIf);
        }
        return null;
    }


    private void addToPendingDeclaration(ServiceDeclaration serviceDeclaration) {
        synchronized (mServiceDeclarations) {
            mServiceDeclarations.add(serviceDeclaration);
        }
    }

    private ServiceDeclaration getPendingDeclaration() {
        synchronized (mServiceDeclarations) {
            if (mServiceDeclarations.size() > 0)
                return mServiceDeclarations.get(0);
        }
        return null;
    }

    private void removePendingDeclaration() {
        synchronized (mServiceDeclarations) {
            if (mServiceDeclarations.size() > 0)
                mServiceDeclarations.remove(0);
        }
    }

    private AdvertiseManager mAdvertiseManager;
    private ScanManager mScanManager;

    /**
     * Reliable write queue
     */
    private Set<String> mReliableQueue = new HashSet<String>();

    static {
        System.load("/system/lib/libbluetooth_jni.so");
        if (DBG) Log.d(TAG, "classInitNative called");
        classInitNative();
    }

    protected String getName() {
        return TAG;
    }

    protected IProfileServiceBinder initBinder() {
        return new BluetoothGattBinder(this);
    }

    protected boolean start() {
        if (DBG) Log.d(TAG, "start()");
        initializeNative();
        mAdvertiseManager = new AdvertiseManager(this);
        mAdvertiseManager.start();

        mScanManager = new ScanManager(this);
        mScanManager.start();

        return true;
    }

    protected boolean stop() {
        if (DBG) Log.d(TAG, "stop()");
        mClientMap.clear();
        mServerMap.clear();
        mSearchQueue.clear();
        mHandleMap.clear();
        mServiceDeclarations.clear();
        mActiveServiceDeclarations.clear();
        mReliableQueue.clear();
        if (mAdvertiseManager != null) mAdvertiseManager.cleanup();
        if (mScanManager != null) mScanManager.cleanup();
        return true;
    }

    protected boolean cleanup() {
        if (DBG) Log.d(TAG, "cleanup()");
        cleanupNative();
        if (mAdvertiseManager != null) mAdvertiseManager.cleanup();
        if (mScanManager != null) mScanManager.cleanup();
        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (GattDebugUtils.handleDebugAction(this, intent)) {
            return Service.START_NOT_STICKY;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    /**
     * DeathReceipient handlers used to unregister applications that
     * disconnect ungracefully (ie. crash or forced close).
     */

    class ClientDeathRecipient implements IBinder.DeathRecipient {
        int mAppIf;

        public ClientDeathRecipient(int appIf) {
            mAppIf = appIf;
        }

        @Override
        public void binderDied() {
            if (DBG) Log.d(TAG, "Binder is dead - unregistering client (" + mAppIf + ")!");

            if (isScanClient(mAppIf)) {
                ScanClient client = new ScanClient(mAppIf, false);
                client.appDied = true;
                stopScan(client);
            } else {
                AdvertiseClient client = new AdvertiseClient(mAppIf);
                client.appDied = true;
                stopMultiAdvertising(client);
            }
        }

        private boolean isScanClient(int clientIf) {
            for (ScanClient client : mScanManager.getRegularScanQueue()) {
                if (client.clientIf == clientIf) {
                    return true;
                }
            }
            for (ScanClient client : mScanManager.getBatchScanQueue()) {
                if (client.clientIf == clientIf) {
                    return true;
                }
            }
            return false;
        }
    }

    class ServerDeathRecipient implements IBinder.DeathRecipient {
        int mAppIf;

        public ServerDeathRecipient(int appIf) {
            mAppIf = appIf;
        }

        public void binderDied() {
            if (DBG) Log.d(TAG, "Binder is dead - unregistering server (" + mAppIf + ")!");
            unregisterServer(mAppIf);
        }
    }

    /**
     * Handlers for incoming service calls
     */
    private static class BluetoothGattBinder extends IBluetoothGatt.Stub implements IProfileServiceBinder {
        private GattService mService;

        public BluetoothGattBinder(GattService svc) {
            mService = svc;
        }

        public boolean cleanup()  {
            mService = null;
            return true;
        }

        private GattService getService() {
            if (mService  != null && mService.isAvailable()) return mService;
            Log.e(TAG, "getService() - Service requested, but not available!");
            return null;
        }

        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            GattService service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>();
            return service.getDevicesMatchingConnectionStates(states);
        }

        public void registerClient(ParcelUuid uuid, IBluetoothGattCallback callback) {
            GattService service = getService();
            if (service == null) return;
            service.registerClient(uuid.getUuid(), callback);
        }

        public void unregisterClient(int clientIf) {
            GattService service = getService();
            if (service == null) return;
            service.unregisterClient(clientIf);
        }

        @Override
        public void startScan(int appIf, boolean isServer, ScanSettings settings,
                List<ScanFilter> filters, List storages) {
            GattService service = getService();
            if (service == null) return;
            service.startScan(appIf, isServer, settings, filters, storages);
        }

        public void stopScan(int appIf, boolean isServer) {
            GattService service = getService();
            if (service == null) return;
            service.stopScan(new ScanClient(appIf, isServer));
        }

        @Override
        public void flushPendingBatchResults(int appIf, boolean isServer) {
            GattService service = getService();
            if (service == null) return;
            service.flushPendingBatchResults(appIf, isServer);
        }

        public void clientConnect(int clientIf, String address, boolean isDirect, int transport) {
            GattService service = getService();
            if (service == null) return;
            service.clientConnect(clientIf, address, isDirect, transport);
        }

        public void clientDisconnect(int clientIf, String address) {
            GattService service = getService();
            if (service == null) return;
            service.clientDisconnect(clientIf, address);
        }

        public void refreshDevice(int clientIf, String address) {
            GattService service = getService();
            if (service == null) return;
            service.refreshDevice(clientIf, address);
        }

        public void discoverServices(int clientIf, String address) {
            GattService service = getService();
            if (service == null) return;
            service.discoverServices(clientIf, address);
        }

        public void readCharacteristic(int clientIf, String address, int srvcType,
                                       int srvcInstanceId, ParcelUuid srvcId,
                                       int charInstanceId, ParcelUuid charId,
                                       int authReq) {
            GattService service = getService();
            if (service == null) return;
            service.readCharacteristic(clientIf, address, srvcType, srvcInstanceId,
                                       srvcId.getUuid(), charInstanceId,
                                       charId.getUuid(), authReq);
        }

        public void writeCharacteristic(int clientIf, String address, int srvcType,
                             int srvcInstanceId, ParcelUuid srvcId,
                             int charInstanceId, ParcelUuid charId,
                             int writeType, int authReq, byte[] value) {
            GattService service = getService();
            if (service == null) return;
            service.writeCharacteristic(clientIf, address, srvcType, srvcInstanceId,
                                       srvcId.getUuid(), charInstanceId,
                                       charId.getUuid(), writeType, authReq,
                                       value);
        }

        public void readDescriptor(int clientIf, String address, int srvcType,
                            int srvcInstanceId, ParcelUuid srvcId,
                            int charInstanceId, ParcelUuid charId,
                            int descrInstanceId, ParcelUuid descrId,
                            int authReq) {
            GattService service = getService();
            if (service == null) return;
            service.readDescriptor(clientIf, address, srvcType,
                                   srvcInstanceId, srvcId.getUuid(),
                                   charInstanceId, charId.getUuid(),
                                   descrInstanceId, descrId.getUuid(),
                                   authReq);
        }

        public void writeDescriptor(int clientIf, String address, int srvcType,
                            int srvcInstanceId, ParcelUuid srvcId,
                            int charInstanceId, ParcelUuid charId,
                            int descrInstanceId, ParcelUuid descrId,
                            int writeType, int authReq, byte[] value) {
            GattService service = getService();
            if (service == null) return;
            service.writeDescriptor(clientIf, address, srvcType,
                                    srvcInstanceId, srvcId.getUuid(),
                                    charInstanceId, charId.getUuid(),
                                    descrInstanceId, descrId.getUuid(),
                                    writeType, authReq, value);
        }

        public void beginReliableWrite(int clientIf, String address) {
            GattService service = getService();
            if (service == null) return;
            service.beginReliableWrite(clientIf, address);
        }

        public void endReliableWrite(int clientIf, String address, boolean execute) {
            GattService service = getService();
            if (service == null) return;
            service.endReliableWrite(clientIf, address, execute);
        }

        public void registerForNotification(int clientIf, String address, int srvcType,
                            int srvcInstanceId, ParcelUuid srvcId,
                            int charInstanceId, ParcelUuid charId,
                            boolean enable) {
            GattService service = getService();
            if (service == null) return;
            service.registerForNotification(clientIf, address, srvcType, srvcInstanceId,
                                       srvcId.getUuid(), charInstanceId,
                                       charId.getUuid(), enable);
        }

        public void readRemoteRssi(int clientIf, String address) {
            GattService service = getService();
            if (service == null) return;
            service.readRemoteRssi(clientIf, address);
        }

        public void configureMTU(int clientIf, String address, int mtu) {
            GattService service = getService();
            if (service == null) return;
            service.configureMTU(clientIf, address, mtu);
        }

        public void connectionParameterUpdate(int clientIf, String address,
                                              int connectionPriority) {
            GattService service = getService();
            if (service == null) return;
            service.connectionParameterUpdate(clientIf, address, connectionPriority);
        }

        public void registerServer(ParcelUuid uuid, IBluetoothGattServerCallback callback) {
            GattService service = getService();
            if (service == null) return;
            service.registerServer(uuid.getUuid(), callback);
        }

        public void unregisterServer(int serverIf) {
            GattService service = getService();
            if (service == null) return;
            service.unregisterServer(serverIf);
        }

        public void serverConnect(int serverIf, String address, boolean isDirect, int transport) {
            GattService service = getService();
            if (service == null) return;
            service.serverConnect(serverIf, address, isDirect, transport);
        }

        public void serverDisconnect(int serverIf, String address) {
            GattService service = getService();
            if (service == null) return;
            service.serverDisconnect(serverIf, address);
        }

        public void beginServiceDeclaration(int serverIf, int srvcType,
                                            int srvcInstanceId, int minHandles,
                                            ParcelUuid srvcId, boolean advertisePreferred) {
            GattService service = getService();
            if (service == null) return;
            service.beginServiceDeclaration(serverIf, srvcType, srvcInstanceId,
                               minHandles, srvcId.getUuid(), advertisePreferred);
        }

        public void addIncludedService(int serverIf, int srvcType,
                            int srvcInstanceId, ParcelUuid srvcId) {
            GattService service = getService();
            if (service == null) return;
            service.addIncludedService(serverIf, srvcType, srvcInstanceId,
                                            srvcId.getUuid());
        }

        public void addCharacteristic(int serverIf, ParcelUuid charId,
                            int properties, int permissions) {
            GattService service = getService();
            if (service == null) return;
            service.addCharacteristic(serverIf, charId.getUuid(), properties,
                                      permissions);
        }

        public void addDescriptor(int serverIf, ParcelUuid descId,
                           int permissions) {
            GattService service = getService();
            if (service == null) return;
            service.addDescriptor(serverIf, descId.getUuid(), permissions);
        }

        public void endServiceDeclaration(int serverIf) {
            GattService service = getService();
            if (service == null) return;
            service.endServiceDeclaration(serverIf);
        }

        public void removeService(int serverIf, int srvcType,
                           int srvcInstanceId, ParcelUuid srvcId) {
            GattService service = getService();
            if (service == null) return;
            service.removeService(serverIf, srvcType, srvcInstanceId,
                                  srvcId.getUuid());
        }

        public void clearServices(int serverIf) {
            GattService service = getService();
            if (service == null) return;
            service.clearServices(serverIf);
        }

        public void sendResponse(int serverIf, String address, int requestId,
                                 int status, int offset, byte[] value) {
            GattService service = getService();
            if (service == null) return;
            service.sendResponse(serverIf, address, requestId, status, offset, value);
        }

        public void sendNotification(int serverIf, String address, int srvcType,
                                              int srvcInstanceId, ParcelUuid srvcId,
                                              int charInstanceId, ParcelUuid charId,
                                              boolean confirm, byte[] value) {
            GattService service = getService();
            if (service == null) return;
            service.sendNotification(serverIf, address, srvcType, srvcInstanceId,
                srvcId.getUuid(), charInstanceId, charId.getUuid(), confirm, value);
        }

        @Override
        public void startMultiAdvertising(int clientIf, AdvertiseData advertiseData,
                AdvertiseData scanResponse, AdvertiseSettings settings) {
            GattService service = getService();
            if (service == null) return;
            service.startMultiAdvertising(clientIf, advertiseData, scanResponse, settings);
        }

        @Override
        public void stopMultiAdvertising(int clientIf) {
            GattService service = getService();
            if (service == null) return;
            service.stopMultiAdvertising(new AdvertiseClient(clientIf));
        }
    };

    /**************************************************************************
     * Callback functions - CLIENT
     *************************************************************************/

    void onScanResult(String address, int rssi, byte[] adv_data) {
        if (VDBG) Log.d(TAG, "onScanResult() - address=" + address
                    + ", rssi=" + rssi);
        ScanRecord record = ScanRecord.parseFromBytes(adv_data);
        List<UUID> remoteUuids = parseUuids(adv_data);
        for (ScanClient client : mScanManager.getRegularScanQueue()) {
            if (client.uuids.length > 0) {
                int matches = 0;
                for (UUID search : client.uuids) {
                    for (UUID remote: remoteUuids) {
                        if (remote.equals(search)) {
                            ++matches;
                            break; // Only count 1st match in case of duplicates
                        }
                    }
                }

                if (matches < client.uuids.length) continue;
            }

            if (!client.isServer) {
                ClientMap.App app = mClientMap.getById(client.clientIf);
                if (app != null) {
                    BluetoothDevice device = BluetoothAdapter.getDefaultAdapter()
                            .getRemoteDevice(address);
                    ScanResult result = new ScanResult(device, ScanRecord.parseFromBytes(adv_data),
                            rssi, SystemClock.elapsedRealtimeNanos());
                    if (matchesFilters(client, result)) {
                        try {
                            ScanSettings settings = client.settings;
                            // framework detects the first match, hw signal is
                            // used to detect the onlost
                            // ToDo: make scanClient+result, 1 to many when hw
                            // support is available
                            if ((settings.getCallbackType() &
                                    ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0) {
                                synchronized (mOnFoundResults) {
                                    mOnFoundResults.put(client, result);
                                }
                                app.callback.onFoundOrLost(true, result);
                            }
                            if ((settings.getCallbackType() &
                                    ScanSettings.CALLBACK_TYPE_ALL_MATCHES) != 0) {
                                app.callback.onScanResult(result);
                            }
                        } catch (RemoteException e) {
                            Log.e(TAG, "Exception: " + e);
                            mClientMap.remove(client.clientIf);
                            mScanManager.stopScan(client);
                        }
                    }
                }
            } else {
                ServerMap.App app = mServerMap.getById(client.clientIf);
                if (app != null) {
                    try {
                        app.callback.onScanResult(address, rssi, adv_data);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Exception: " + e);
                        mServerMap.remove(client.clientIf);
                        mScanManager.stopScan(client);
                    }
                }
            }
        }
    }

    // Check if a scan record matches a specific filters.
    private boolean matchesFilters(ScanClient client, ScanResult scanResult) {
        if (client.filters == null || client.filters.isEmpty()) {
            return true;
        }
        if (DBG) Log.d(TAG, "result: " + scanResult.toString());
        for (ScanFilter filter : client.filters) {
            if (DBG) Log.d(TAG, "filter: " + filter.toString());
            if (filter.matches(scanResult)) {
                return true;
            }
        }
        return false;
    }

    void onClientRegistered(int status, int clientIf, long uuidLsb, long uuidMsb)
            throws RemoteException {
        UUID uuid = new UUID(uuidMsb, uuidLsb);
        if (DBG) Log.d(TAG, "onClientRegistered() - UUID=" + uuid + ", clientIf=" + clientIf);
        ClientMap.App app = mClientMap.getByUuid(uuid);
        if (app != null) {
            app.id = clientIf;
            app.linkToDeath(new ClientDeathRecipient(clientIf));
            app.callback.onClientRegistered(status, clientIf);
        }
    }

    void onConnected(int clientIf, int connId, int status, String address)
            throws RemoteException  {
        if (DBG) Log.d(TAG, "onConnected() - clientIf=" + clientIf
            + ", connId=" + connId + ", address=" + address);

        if (status == 0) mClientMap.addConnection(clientIf, connId, address);
        ClientMap.App app = mClientMap.getById(clientIf);
        if (app != null) {
            app.callback.onClientConnectionState(status, clientIf,
                                (status==BluetoothGatt.GATT_SUCCESS), address);
        }
    }

    void onDisconnected(int clientIf, int connId, int status, String address)
            throws RemoteException {
        if (DBG) Log.d(TAG, "onDisconnected() - clientIf=" + clientIf
            + ", connId=" + connId + ", address=" + address);

        mClientMap.removeConnection(clientIf, connId);
        mSearchQueue.removeConnId(connId);
        ClientMap.App app = mClientMap.getById(clientIf);
        if (app != null) {
            app.callback.onClientConnectionState(status, clientIf, false, address);
        }
    }

    void onSearchCompleted(int connId, int status) throws RemoteException {
        if (DBG) Log.d(TAG, "onSearchCompleted() - connId=" + connId+ ", status=" + status);
        // We got all services, now let's explore characteristics...
        continueSearch(connId, status);
    }

    void onSearchResult(int connId, int srvcType,
            int srvcInstId, long srvcUuidLsb, long srvcUuidMsb)
            throws RemoteException {
        UUID uuid = new UUID(srvcUuidMsb, srvcUuidLsb);
        String address = mClientMap.addressByConnId(connId);

        if (VDBG) Log.d(TAG, "onSearchResult() - address=" + address + ", uuid=" + uuid);

        mSearchQueue.add(connId, srvcType, srvcInstId, srvcUuidLsb, srvcUuidMsb);

        ClientMap.App app = mClientMap.getByConnId(connId);
        if (app != null) {
            app.callback.onGetService(address, srvcType, srvcInstId,
                                        new ParcelUuid(uuid));
        }
    }

    void onGetCharacteristic(int connId, int status, int srvcType,
            int srvcInstId, long srvcUuidLsb, long srvcUuidMsb,
            int charInstId, long charUuidLsb, long charUuidMsb,
            int charProp) throws RemoteException {

        UUID srvcUuid = new UUID(srvcUuidMsb, srvcUuidLsb);
        UUID charUuid = new UUID(charUuidMsb, charUuidLsb);
        String address = mClientMap.addressByConnId(connId);

        if (VDBG) Log.d(TAG, "onGetCharacteristic() - address=" + address
            + ", status=" + status + ", charUuid=" + charUuid + ", prop=" + charProp);

        if (status == 0) {
            mSearchQueue.add(connId, srvcType,
                            srvcInstId, srvcUuidLsb, srvcUuidMsb,
                            charInstId, charUuidLsb, charUuidMsb);

            ClientMap.App app = mClientMap.getByConnId(connId);
            if (app != null) {
                app.callback.onGetCharacteristic(address, srvcType,
                            srvcInstId, new ParcelUuid(srvcUuid),
                            charInstId, new ParcelUuid(charUuid), charProp);
            }

            // Get next characteristic in the current service
            gattClientGetCharacteristicNative(connId, srvcType,
                                        srvcInstId, srvcUuidLsb, srvcUuidMsb,
                                        charInstId, charUuidLsb, charUuidMsb);
        } else {
            // Check for included services next
            gattClientGetIncludedServiceNative(connId,
                srvcType, srvcInstId, srvcUuidLsb, srvcUuidMsb,
                0,0,0,0);
        }
    }

    void onGetDescriptor(int connId, int status, int srvcType,
            int srvcInstId, long srvcUuidLsb, long srvcUuidMsb,
            int charInstId, long charUuidLsb, long charUuidMsb,
            int descrInstId, long descrUuidLsb, long descrUuidMsb) throws RemoteException {

        UUID srvcUuid = new UUID(srvcUuidMsb, srvcUuidLsb);
        UUID charUuid = new UUID(charUuidMsb, charUuidLsb);
        UUID descUuid = new UUID(descrUuidMsb, descrUuidLsb);
        String address = mClientMap.addressByConnId(connId);

        if (VDBG) Log.d(TAG, "onGetDescriptor() - address=" + address
            + ", status=" + status + ", descUuid=" + descUuid);

        if (status == 0) {
            ClientMap.App app = mClientMap.getByConnId(connId);
            if (app != null) {
                app.callback.onGetDescriptor(address, srvcType,
                            srvcInstId, new ParcelUuid(srvcUuid),
                            charInstId, new ParcelUuid(charUuid),
                            descrInstId, new ParcelUuid(descUuid));
            }

            // Get next descriptor for the current characteristic
            gattClientGetDescriptorNative(connId, srvcType,
                                    srvcInstId, srvcUuidLsb, srvcUuidMsb,
                                    charInstId, charUuidLsb, charUuidMsb,
                                    descrInstId, descrUuidLsb, descrUuidMsb);
        } else {
            // Explore the next service
            continueSearch(connId, 0);
        }
    }

    void onGetIncludedService(int connId, int status, int srvcType,
            int srvcInstId, long srvcUuidLsb, long srvcUuidMsb, int inclSrvcType,
            int inclSrvcInstId, long inclSrvcUuidLsb, long inclSrvcUuidMsb)
            throws RemoteException {
        UUID srvcUuid = new UUID(srvcUuidMsb, srvcUuidLsb);
        UUID inclSrvcUuid = new UUID(inclSrvcUuidMsb, inclSrvcUuidLsb);
        String address = mClientMap.addressByConnId(connId);

        if (VDBG) Log.d(TAG, "onGetIncludedService() - address=" + address
            + ", status=" + status + ", uuid=" + srvcUuid
            + ", inclUuid=" + inclSrvcUuid);

        if (status == 0) {
            ClientMap.App app = mClientMap.getByConnId(connId);
            if (app != null) {
                app.callback.onGetIncludedService(address,
                    srvcType, srvcInstId, new ParcelUuid(srvcUuid),
                    inclSrvcType, inclSrvcInstId, new ParcelUuid(inclSrvcUuid));
            }

            // Find additional included services
            gattClientGetIncludedServiceNative(connId,
                srvcType, srvcInstId, srvcUuidLsb, srvcUuidMsb,
                inclSrvcType, inclSrvcInstId, inclSrvcUuidLsb, inclSrvcUuidMsb);
        } else {
            // Discover descriptors now
            continueSearch(connId, 0);
        }
    }

    void onRegisterForNotifications(int connId, int status, int registered, int srvcType,
            int srvcInstId, long srvcUuidLsb, long srvcUuidMsb,
            int charInstId, long charUuidLsb, long charUuidMsb) {
        UUID srvcUuid = new UUID(srvcUuidMsb, srvcUuidLsb);
        UUID charUuid = new UUID(charUuidMsb, charUuidLsb);
        String address = mClientMap.addressByConnId(connId);

        if (DBG) Log.d(TAG, "onRegisterForNotifications() - address=" + address
            + ", status=" + status + ", registered=" + registered
            + ", charUuid=" + charUuid);
    }

    void onNotify(int connId, String address, int srvcType,
            int srvcInstId, long srvcUuidLsb, long srvcUuidMsb,
            int charInstId, long charUuidLsb, long charUuidMsb,
            boolean isNotify, byte[] data) throws RemoteException {
        UUID srvcUuid = new UUID(srvcUuidMsb, srvcUuidLsb);
        UUID charUuid = new UUID(charUuidMsb, charUuidLsb);

        if (VDBG) Log.d(TAG, "onNotify() - address=" + address
            + ", charUuid=" + charUuid + ", length=" + data.length);

        ClientMap.App app = mClientMap.getByConnId(connId);
        if (app != null) {
            app.callback.onNotify(address, srvcType,
                        srvcInstId, new ParcelUuid(srvcUuid),
                        charInstId, new ParcelUuid(charUuid),
                        data);
        }
    }

    void onReadCharacteristic(int connId, int status, int srvcType,
            int srvcInstId, long srvcUuidLsb, long srvcUuidMsb,
            int charInstId, long charUuidLsb, long charUuidMsb,
            int charType, byte[] data) throws RemoteException {

        UUID srvcUuid = new UUID(srvcUuidMsb, srvcUuidLsb);
        UUID charUuid = new UUID(charUuidMsb, charUuidLsb);
        String address = mClientMap.addressByConnId(connId);

        if (VDBG) Log.d(TAG, "onReadCharacteristic() - address=" + address
            + ", status=" + status + ", length=" + data.length);

        ClientMap.App app = mClientMap.getByConnId(connId);
        if (app != null) {
            app.callback.onCharacteristicRead(address, status, srvcType,
                        srvcInstId, new ParcelUuid(srvcUuid),
                        charInstId, new ParcelUuid(charUuid), data);
        }
    }

    void onWriteCharacteristic(int connId, int status, int srvcType,
            int srvcInstId, long srvcUuidLsb, long srvcUuidMsb,
            int charInstId, long charUuidLsb, long charUuidMsb)
            throws RemoteException {

        UUID srvcUuid = new UUID(srvcUuidMsb, srvcUuidLsb);
        UUID charUuid = new UUID(charUuidMsb, charUuidLsb);
        String address = mClientMap.addressByConnId(connId);

        if (VDBG) Log.d(TAG, "onWriteCharacteristic() - address=" + address
            + ", status=" + status);

        ClientMap.App app = mClientMap.getByConnId(connId);
        if (app == null) return;

        if (!app.isCongested) {
            app.callback.onCharacteristicWrite(address, status, srvcType,
                    srvcInstId, new ParcelUuid(srvcUuid),
                    charInstId, new ParcelUuid(charUuid));
        } else {
            if (status == BluetoothGatt.GATT_CONNECTION_CONGESTED) {
                status = BluetoothGatt.GATT_SUCCESS;
            }
            CallbackInfo callbackInfo = new CallbackInfo(address, status, srvcType,
                    srvcInstId, srvcUuid, charInstId, charUuid);
            app.queueCallback(callbackInfo);
        }
    }

    void onExecuteCompleted(int connId, int status) throws RemoteException {
        String address = mClientMap.addressByConnId(connId);
        if (VDBG) Log.d(TAG, "onExecuteCompleted() - address=" + address
            + ", status=" + status);

        ClientMap.App app = mClientMap.getByConnId(connId);
        if (app != null) {
            app.callback.onExecuteWrite(address, status);
        }
    }

    void onReadDescriptor(int connId, int status, int srvcType,
            int srvcInstId, long srvcUuidLsb, long srvcUuidMsb,
            int charInstId, long charUuidLsb, long charUuidMsb,
            int descrInstId, long descrUuidLsb, long descrUuidMsb,
            int charType, byte[] data) throws RemoteException {

        UUID srvcUuid = new UUID(srvcUuidMsb, srvcUuidLsb);
        UUID charUuid = new UUID(charUuidMsb, charUuidLsb);
        UUID descrUuid = new UUID(descrUuidMsb, descrUuidLsb);
        String address = mClientMap.addressByConnId(connId);

        if (VDBG) Log.d(TAG, "onReadDescriptor() - address=" + address
            + ", status=" + status + ", length=" + data.length);

        ClientMap.App app = mClientMap.getByConnId(connId);
        if (app != null) {
            app.callback.onDescriptorRead(address, status, srvcType,
                        srvcInstId, new ParcelUuid(srvcUuid),
                        charInstId, new ParcelUuid(charUuid),
                        descrInstId, new ParcelUuid(descrUuid), data);
        }
    }

    void onWriteDescriptor(int connId, int status, int srvcType,
            int srvcInstId, long srvcUuidLsb, long srvcUuidMsb,
            int charInstId, long charUuidLsb, long charUuidMsb,
            int descrInstId, long descrUuidLsb, long descrUuidMsb) throws RemoteException {

        UUID srvcUuid = new UUID(srvcUuidMsb, srvcUuidLsb);
        UUID charUuid = new UUID(charUuidMsb, charUuidLsb);
        UUID descrUuid = new UUID(descrUuidMsb, descrUuidLsb);
        String address = mClientMap.addressByConnId(connId);

        if (VDBG) Log.d(TAG, "onWriteDescriptor() - address=" + address
            + ", status=" + status);

        ClientMap.App app = mClientMap.getByConnId(connId);
        if (app != null) {
            app.callback.onDescriptorWrite(address, status, srvcType,
                        srvcInstId, new ParcelUuid(srvcUuid),
                        charInstId, new ParcelUuid(charUuid),
                        descrInstId, new ParcelUuid(descrUuid));
        }
    }

    void onReadRemoteRssi(int clientIf, String address,
                    int rssi, int status) throws RemoteException{
        if (DBG) Log.d(TAG, "onReadRemoteRssi() - clientIf=" + clientIf + " address=" +
                     address + ", rssi=" + rssi + ", status=" + status);

        ClientMap.App app = mClientMap.getById(clientIf);
        if (app != null) {
            app.callback.onReadRemoteRssi(address, rssi, status);
        }
    }

    void onScanFilterEnableDisabled(int action, int status, int clientIf) {
        if (DBG) {
            Log.d(TAG, "onScanFilterEnableDisabled() - clientIf=" + clientIf + ", status=" + status
                    + ", action=" + action);
        }
        mScanManager.callbackDone(clientIf, status);
    }

    void onScanFilterParamsConfigured(int action, int status, int clientIf, int availableSpace) {
        if (DBG) {
            Log.d(TAG, "onScanFilterParamsConfigured() - clientIf=" + clientIf
                    + ", status=" + status + ", action=" + action
                    + ", availableSpace=" + availableSpace);
        }
        mScanManager.callbackDone(clientIf, status);
    }

    void onScanFilterConfig(int action, int status, int clientIf, int filterType,
            int availableSpace) {
        if (DBG) {
            Log.d(TAG, "onScanFilterConfig() - clientIf=" + clientIf + ", action = " + action
                    + " status = " + status + ", filterType=" + filterType
                    + ", availableSpace=" + availableSpace);
        }

        mScanManager.callbackDone(clientIf, status);
    }

    void onBatchScanStorageConfigured(int status, int clientIf) {
        if (DBG) {
            Log.d(TAG, "onBatchScanStorageConfigured() - clientIf="+ clientIf + ", status=" + status);
        }
        mScanManager.callbackDone(clientIf, status);
    }

    // TODO: split into two different callbacks : onBatchScanStarted and onBatchScanStopped.
    void onBatchScanStartStopped(int startStopAction, int status, int clientIf) {
        if (DBG) {
            Log.d(TAG, "onBatchScanStartStopped() - clientIf=" + clientIf
                    + ", status=" + status + ", startStopAction=" + startStopAction);
        }
        mScanManager.callbackDone(clientIf, status);
    }

    void onBatchScanReports(int status, int clientIf, int reportType, int numRecords,
            byte[] recordData) throws RemoteException {
        if (DBG) {
            Log.d(TAG, "onBatchScanReports() - clientIf=" + clientIf + ", status=" + status
                    + ", reportType=" + reportType + ", numRecords=" + numRecords);
        }
        mScanManager.callbackDone(clientIf, status);
        Set<ScanResult> results = parseBatchScanResults(numRecords, reportType, recordData);
        if (reportType == ScanManager.SCAN_RESULT_TYPE_TRUNCATED) {
            // We only support single client for truncated mode.
            ClientMap.App app = mClientMap.getById(clientIf);
            if (app == null) return;
            app.callback.onBatchScanResults(new ArrayList<ScanResult>(results));
        } else {
            for (ScanClient client : mScanManager.getFullBatchScanQueue()) {
                // Deliver results for each client.
                deliverBatchScan(client, results);
            }
        }
    }

    // Check and deliver scan results for different scan clients.
    private void deliverBatchScan(ScanClient client, Set<ScanResult> allResults) throws
            RemoteException {
        ClientMap.App app = mClientMap.getById(client.clientIf);
        if (app == null) return;
        if (client.filters == null || client.filters.isEmpty()) {
            app.callback.onBatchScanResults(new ArrayList<ScanResult>(allResults));
        }
        // Reconstruct the scan results.
        List<ScanResult> results = new ArrayList<ScanResult>();
        for (ScanResult scanResult : allResults) {
            if (matchesFilters(client, scanResult)) {
                results.add(scanResult);
            }
        }
        app.callback.onBatchScanResults(results);
    }

    private Set<ScanResult> parseBatchScanResults(int numRecords, int reportType,
            byte[] batchRecord) {
        if (numRecords == 0) {
            return Collections.emptySet();
        }
        if (DBG) Log.d(TAG, "current time is " + SystemClock.elapsedRealtimeNanos());
        if (reportType == ScanManager.SCAN_RESULT_TYPE_TRUNCATED) {
            return parseTruncatedResults(numRecords, batchRecord);
        } else {
            return parseFullResults(numRecords, batchRecord);
        }
    }

    private Set<ScanResult> parseTruncatedResults(int numRecords, byte[] batchRecord) {
        if (DBG) Log.d(TAG, "batch record " + Arrays.toString(batchRecord));
        Set<ScanResult> results = new HashSet<ScanResult>(numRecords);
        for (int i = 0; i < numRecords; ++i) {
            byte[] record = extractBytes(batchRecord, i * TRUNCATED_RESULT_SIZE,
                    TRUNCATED_RESULT_SIZE);
            byte[] address = extractBytes(record, 0, 6);
            // TODO: remove temp hack.
            reverse(address);
            BluetoothDevice device = mAdapter.getRemoteDevice(address);
            int rssi = record[8];
            // Timestamp is in every 50 ms.
            long timestampNanos = parseTimestampNanos(extractBytes(record, 9, 2));
            results.add(new ScanResult(device, ScanRecord.parseFromBytes(new byte[0]),
                    rssi, timestampNanos));
        }
        return results;
    }

    private long parseTimestampNanos(byte[] data) {
        long timestampUnit = data[1] & 0xFF << 8 + data[0];
        long timestampNanos = SystemClock.elapsedRealtimeNanos() -
                TimeUnit.MILLISECONDS.toNanos(timestampUnit * 50);
        return timestampNanos;
    }

    private Set<ScanResult> parseFullResults(int numRecords, byte[] batchRecord) {
        Log.d(TAG, "Batch record : " + Arrays.toString(batchRecord));
        Set<ScanResult> results = new HashSet<ScanResult>(numRecords);
        int position = 0;
        while (position < batchRecord.length) {
            byte[] address = extractBytes(batchRecord, position, 6);
            // TODO: remove temp hack.
            reverse(address);
            BluetoothDevice device = mAdapter.getRemoteDevice(address);
            position += 6;
            // Skip address type.
            position++;
            // Skip tx power level.
            position++;
            int rssi = batchRecord[position++];
            long timestampNanos = parseTimestampNanos(extractBytes(batchRecord, position, 2));
            position += 2;

            // Combine advertise packet and scan response packet.
            int advertisePacketLen = batchRecord[position++];
            byte[] advertiseBytes = extractBytes(batchRecord, position, advertisePacketLen);
            position += advertisePacketLen;
            int scanResponsePacketLen = batchRecord[position++];
            byte[] scanResponseBytes = extractBytes(batchRecord, position, scanResponsePacketLen);
            position += scanResponsePacketLen;
            byte[] scanRecord = new byte[advertisePacketLen + scanResponsePacketLen];
            System.arraycopy(advertiseBytes, 0, scanRecord, 0, advertisePacketLen);
            System.arraycopy(scanResponseBytes, 0, scanRecord,
                    advertisePacketLen, scanResponsePacketLen);
            Log.d(TAG, "ScanRecord : " + Arrays.toString(scanRecord));
            results.add(new ScanResult(device, ScanRecord.parseFromBytes(scanRecord),
                    rssi, timestampNanos));
        }
        return results;
    }

    // Reverse byte array.
    private void reverse(byte[] address) {
        int len = address.length;
        for (int i = 0; i < len / 2; ++i) {
            byte b = address[i];
            address[i] = address[len - 1 - i];
            address[len - 1 - i] = b;
        }
    }

    // Helper method to extract bytes from byte array.
    private static byte[] extractBytes(byte[] scanRecord, int start, int length) {
        byte[] bytes = new byte[length];
        System.arraycopy(scanRecord, start, bytes, 0, length);
        return bytes;
    }

    void onBatchScanThresholdCrossed(int clientIf) {
        if (DBG) {
            Log.d(TAG, "onBatchScanThresholdCrossed() - clientIf=" + clientIf);
        }
        boolean isServer = false;
        flushPendingBatchResults(clientIf, isServer);
    }

    void onTrackAdvFoundLost(int filterIndex, int addrType, String address, int advState,
            int clientIf) throws RemoteException {
        if (DBG) Log.d(TAG, "onClientAdvertiserFoundLost() - clientIf="
                + clientIf + "address = " + address + "adv_state = "
                + advState + "client_if = " + clientIf);
        ClientMap.App app = mClientMap.getById(clientIf);
        if (app == null || app.callback == null) {
            Log.e(TAG, "app or callback is null");
            return;
        }

        // use hw signal for only onlost reporting
        if (advState != ADVT_STATE_ONLOST) {
            return;
        }

        for (ScanClient client : mScanManager.getRegularScanQueue()) {
            if (client.clientIf == clientIf) {
                ScanSettings settings = client.settings;
                if ((settings.getCallbackType() &
                            ScanSettings.CALLBACK_TYPE_MATCH_LOST) != 0) {

                    while (!mOnFoundResults.isEmpty()) {
                        ScanResult result = mOnFoundResults.get(client);
                        app.callback.onFoundOrLost(false, result);
                        synchronized (mOnFoundResults) {
                            mOnFoundResults.remove(client);
                        }
                    }
                }
            }
        }
    }

    // callback from AdvertiseManager for advertise status dispatch.
    void onMultipleAdvertiseCallback(int clientIf, int status, boolean isStart,
            AdvertiseSettings settings) throws RemoteException {
        ClientMap.App app = mClientMap.getById(clientIf);
        if (app == null || app.callback == null) {
            Log.e(TAG, "Advertise app or callback is null");
            return;
        }
        app.callback.onMultiAdvertiseCallback(status, isStart, settings);
    }

    void onConfigureMTU(int connId, int status, int mtu) throws RemoteException {
        String address = mClientMap.addressByConnId(connId);

        if (DBG) Log.d(TAG, "onConfigureMTU() address=" + address + ", status="
            + status + ", mtu=" + mtu);

        ClientMap.App app = mClientMap.getByConnId(connId);
        if (app != null) {
            app.callback.onConfigureMTU(address, mtu, status);
        }
    }

    // Callback for standard advertising instance.
    void onAdvertiseCallback(int status, int clientIf) {
        if (DBG) Log.d(TAG, "onAdvertiseCallback,- clientIf=" + clientIf + ", status=" + status);
    }

    // Followings are callbacks for Bluetooth LE Advertise operations.
    // Start advertising flow is
    //     enable advertising instance -> onAdvertiseInstaceEnabled
    // ->  set advertise data          -> onAdvertiseDataSet
    // ->  set scan response           -> onAdvertiseDataSet

    // Callback when advertise instance is enabled.
    void onAdvertiseInstanceEnabled(int status, int clientIf) {
        if (DBG) Log.d(TAG, "onAdvertiseInstanceEnabled() - "
                + "clientIf=" + clientIf + ", status=" + status);
        mAdvertiseManager.callbackDone(clientIf, status);
    }

    // Not really used.
    void onAdvertiseDataUpdated(int status, int client_if) {
        if (DBG) Log.d(TAG, "onAdvertiseDataUpdated() - client_if=" + client_if
            + ", status=" + status);
    }

    // Callback when advertise data or scan response is set.
    void onAdvertiseDataSet(int status, int clientIf) {
        if (DBG) Log.d(TAG, "onAdvertiseDataSet() - clientIf=" + clientIf
            + ", status=" + status);
        mAdvertiseManager.callbackDone(clientIf, status);
    }

    // Callback when advertise instance is disabled
    void onAdvertiseInstanceDisabled(int status, int clientIf) throws RemoteException {
        if (DBG) Log.d(TAG, "onAdvertiseInstanceDisabled() - clientIf=" + clientIf
            + ", status=" + status);
        ClientMap.App app = mClientMap.getById(clientIf);
        if (app != null) {
            Log.d(TAG, "Client app is not null!");
            boolean isStart = false;
            if (status == 0) {
                app.callback.onMultiAdvertiseCallback(AdvertiseCallback.ADVERTISE_SUCCESS,
                        isStart, null);
            } else {
                app.callback.onMultiAdvertiseCallback(
                        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR, isStart, null);
            }
        }
    }

    void onClientCongestion(int connId, boolean congested) throws RemoteException {
        if (VDBG) Log.d(TAG, "onClientCongestion() - connId=" + connId + ", congested=" + congested);

        ClientMap.App app = mClientMap.getByConnId(connId);

        if (app != null) {
            app.isCongested = congested;
            while(!app.isCongested) {
                CallbackInfo callbackInfo = app.popQueuedCallback();
                if (callbackInfo == null)  return;
                app.callback.onCharacteristicWrite(callbackInfo.address,
                        callbackInfo.status, callbackInfo.srvcType,
                        callbackInfo.srvcInstId, new ParcelUuid(callbackInfo.srvcUuid),
                        callbackInfo.charInstId, new ParcelUuid(callbackInfo.charUuid));
            }
        }
    }

    /**************************************************************************
     * GATT Service functions - Shared CLIENT/SERVER
     *************************************************************************/

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        final int DEVICE_TYPE_BREDR = 0x1;

        Map<BluetoothDevice, Integer> deviceStates = new HashMap<BluetoothDevice,
                                                                 Integer>();

        // Add paired LE devices

        Set<BluetoothDevice> bondedDevices = mAdapter.getBondedDevices();
        for (BluetoothDevice device : bondedDevices) {
            if (getDeviceType(device) != DEVICE_TYPE_BREDR) {
                deviceStates.put(device, BluetoothProfile.STATE_DISCONNECTED);
            }
        }

        // Add connected deviceStates

        Set<String> connectedDevices = new HashSet<String>();
        connectedDevices.addAll(mClientMap.getConnectedDevices());
        connectedDevices.addAll(mServerMap.getConnectedDevices());

        for (String address : connectedDevices ) {
            BluetoothDevice device = mAdapter.getRemoteDevice(address);
            if (device != null) {
                deviceStates.put(device, BluetoothProfile.STATE_CONNECTED);
            }
        }

        // Create matching device sub-set

        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>();

        for (Map.Entry<BluetoothDevice, Integer> entry : deviceStates.entrySet()) {
            for(int state : states) {
                if (entry.getValue() == state) {
                    deviceList.add(entry.getKey());
                }
            }
        }

        return deviceList;
    }

    void startScan(int appIf, boolean isServer, ScanSettings settings,
            List<ScanFilter> filters, List<List<ResultStorageDescriptor>> storages) {
        if (DBG) Log.d(TAG, "start scan with filters");
        enforceAdminPermission();
        if (needsPrivilegedPermissionForScan(settings)) {
            enforcePrivilegedPermission();
        }
        mScanManager.startScan(new ScanClient(appIf, isServer, settings, filters, storages));
    }

    void flushPendingBatchResults(int clientIf, boolean isServer) {
        if (DBG) Log.d(TAG, "flushPendingBatchResults - clientIf=" + clientIf +
                ", isServer=" + isServer);
        mScanManager.flushBatchScanResults(new ScanClient(clientIf, isServer));
    }

    void stopScan(ScanClient client) {
        enforceAdminPermission();
        int scanQueueSize = mScanManager.getBatchScanQueue().size() +
                mScanManager.getRegularScanQueue().size();
        if (DBG) Log.d(TAG, "stopScan() - queue size =" + scanQueueSize);
        mScanManager.stopScan(client);
    }

    /**************************************************************************
     * GATT Service functions - CLIENT
     *************************************************************************/

    void registerClient(UUID uuid, IBluetoothGattCallback callback) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "registerClient() - UUID=" + uuid);
        mClientMap.add(uuid, callback);
        gattClientRegisterAppNative(uuid.getLeastSignificantBits(),
                                    uuid.getMostSignificantBits());
    }

    void unregisterClient(int clientIf) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "unregisterClient() - clientIf=" + clientIf);
        mClientMap.remove(clientIf);
        gattClientUnregisterAppNative(clientIf);
    }

    void clientConnect(int clientIf, String address, boolean isDirect, int transport) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "clientConnect() - address=" + address + ", isDirect=" + isDirect);
        gattClientConnectNative(clientIf, address, isDirect, transport);
    }

    void clientDisconnect(int clientIf, String address) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (DBG) Log.d(TAG, "clientDisconnect() - address=" + address + ", connId=" + connId);

        gattClientDisconnectNative(clientIf, address, connId != null ? connId : 0);
    }

    void startMultiAdvertising(int clientIf, AdvertiseData advertiseData,
            AdvertiseData scanResponse, AdvertiseSettings settings) {
        enforceAdminPermission();
        mAdvertiseManager.startAdvertising(new AdvertiseClient(clientIf, settings, advertiseData,
                scanResponse));
    }

    void stopMultiAdvertising(AdvertiseClient client) {
        enforceAdminPermission();
        mAdvertiseManager.stopAdvertising(client);
    }


    synchronized List<ParcelUuid> getRegisteredServiceUuids() {
        Utils.enforceAdminPermission(this);
        List<ParcelUuid> serviceUuids = new ArrayList<ParcelUuid>();
        for (HandleMap.Entry entry : mHandleMap.mEntries) {
            serviceUuids.add(new ParcelUuid(entry.uuid));
        }
        return serviceUuids;
    }

    List<String> getConnectedDevices() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        Set<String> connectedDevAddress = new HashSet<String>();
        connectedDevAddress.addAll(mClientMap.getConnectedDevices());
        connectedDevAddress.addAll(mServerMap.getConnectedDevices());
        List<String> connectedDeviceList = new ArrayList<String>(connectedDevAddress);
        return connectedDeviceList;
    }

    void refreshDevice(int clientIf, String address) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "refreshDevice() - address=" + address);
        gattClientRefreshNative(clientIf, address);
    }

    void discoverServices(int clientIf, String address) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (DBG) Log.d(TAG, "discoverServices() - address=" + address + ", connId=" + connId);

        if (connId != null)
            gattClientSearchServiceNative(connId, true, 0, 0);
        else
            Log.e(TAG, "discoverServices() - No connection for " + address + "...");
    }

    void readCharacteristic(int clientIf, String address, int srvcType,
                            int srvcInstanceId, UUID srvcUuid,
                            int charInstanceId, UUID charUuid, int authReq) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (VDBG) Log.d(TAG, "readCharacteristic() - address=" + address);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId != null)
            gattClientReadCharacteristicNative(connId, srvcType,
                srvcInstanceId, srvcUuid.getLeastSignificantBits(),
                srvcUuid.getMostSignificantBits(), charInstanceId,
                charUuid.getLeastSignificantBits(), charUuid.getMostSignificantBits(),
                authReq);
        else
            Log.e(TAG, "readCharacteristic() - No connection for " + address + "...");
    }

    void writeCharacteristic(int clientIf, String address, int srvcType,
                             int srvcInstanceId, UUID srvcUuid,
                             int charInstanceId, UUID charUuid, int writeType,
                             int authReq, byte[] value) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (VDBG) Log.d(TAG, "writeCharacteristic() - address=" + address);

        if (mReliableQueue.contains(address)) writeType = 3; // Prepared write

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId != null)
            gattClientWriteCharacteristicNative(connId, srvcType,
                srvcInstanceId, srvcUuid.getLeastSignificantBits(),
                srvcUuid.getMostSignificantBits(), charInstanceId,
                charUuid.getLeastSignificantBits(), charUuid.getMostSignificantBits(),
                writeType, authReq, value);
        else
            Log.e(TAG, "writeCharacteristic() - No connection for " + address + "...");
    }

    void readDescriptor(int clientIf, String address, int srvcType,
                            int srvcInstanceId, UUID srvcUuid,
                            int charInstanceId, UUID charUuid,
                            int descrInstanceId, UUID descrUuid,
                            int authReq) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (VDBG) Log.d(TAG, "readDescriptor() - address=" + address);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId != null)
            gattClientReadDescriptorNative(connId, srvcType,
                srvcInstanceId,
                srvcUuid.getLeastSignificantBits(), srvcUuid.getMostSignificantBits(),
                charInstanceId,
                charUuid.getLeastSignificantBits(), charUuid.getMostSignificantBits(),
                descrInstanceId,
                descrUuid.getLeastSignificantBits(), descrUuid.getMostSignificantBits(),
                authReq);
        else
            Log.e(TAG, "readDescriptor() - No connection for " + address + "...");
    };

    void writeDescriptor(int clientIf, String address, int srvcType,
                            int srvcInstanceId, UUID srvcUuid,
                            int charInstanceId, UUID charUuid,
                            int descrInstanceId, UUID descrUuid,
                            int writeType, int authReq, byte[] value) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (VDBG) Log.d(TAG, "writeDescriptor() - address=" + address);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId != null)
            gattClientWriteDescriptorNative(connId, srvcType,
                srvcInstanceId,
                srvcUuid.getLeastSignificantBits(), srvcUuid.getMostSignificantBits(),
                charInstanceId,
                charUuid.getLeastSignificantBits(), charUuid.getMostSignificantBits(),
                descrInstanceId,
                descrUuid.getLeastSignificantBits(), descrUuid.getMostSignificantBits(),
                writeType, authReq, value);
        else
            Log.e(TAG, "writeDescriptor() - No connection for " + address + "...");
    }

    void beginReliableWrite(int clientIf, String address) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "beginReliableWrite() - address=" + address);
        mReliableQueue.add(address);
    }

    void endReliableWrite(int clientIf, String address, boolean execute) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "endReliableWrite() - address=" + address
                                + " execute: " + execute);
        mReliableQueue.remove(address);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId != null) gattClientExecuteWriteNative(connId, execute);
    }

    void registerForNotification(int clientIf, String address, int srvcType,
                int srvcInstanceId, UUID srvcUuid,
                int charInstanceId, UUID charUuid,
                boolean enable) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "registerForNotification() - address=" + address + " enable: " + enable);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId != null) {
            gattClientRegisterForNotificationsNative(clientIf, address,
                srvcType, srvcInstanceId, srvcUuid.getLeastSignificantBits(),
                srvcUuid.getMostSignificantBits(), charInstanceId,
                charUuid.getLeastSignificantBits(), charUuid.getMostSignificantBits(),
                enable);
        } else {
            Log.e(TAG, "registerForNotification() - No connection for " + address + "...");
        }
    }

    void readRemoteRssi(int clientIf, String address) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "readRemoteRssi() - address=" + address);
        gattClientReadRemoteRssiNative(clientIf, address);
    }

    void configureMTU(int clientIf, String address, int mtu) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "configureMTU() - address=" + address + " mtu=" + mtu);
        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId != null) {
            gattClientConfigureMTUNative(connId, mtu);
        } else {
            Log.e(TAG, "configureMTU() - No connection for " + address + "...");
        }
    }

    void connectionParameterUpdate(int clientIf, String address, int connectionPriority) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        // Default spec recommended interval is 30->50 ms
        int minInterval = 24; // 24 * 1.25ms = 30ms
        int maxInterval = 40; // 40 * 1.25ms = 50ms

        // Slave latency
        int latency = 0;

        // Link supervision timeout is measured in N * 10ms
        int timeout = 2000; // 20s

        switch (connectionPriority)
        {
            case BluetoothGatt.CONNECTION_PRIORITY_HIGH:
                minInterval = 6; // 7.5ms
                maxInterval = 8; // 10ms
                break;

            case BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER:
                minInterval = 80; // 100ms
                maxInterval = 100; // 125ms
                latency = 2;
                break;
        }

        if (DBG) Log.d(TAG, "connectionParameterUpdate() - address=" + address
            + "params=" + connectionPriority + " interval=" + minInterval + "/" + maxInterval);
        gattConnectionParameterUpdateNative(clientIf, address, minInterval, maxInterval,
                                            latency, timeout);
    }

    /**************************************************************************
     * Callback functions - SERVER
     *************************************************************************/

    void onServerRegistered(int status, int serverIf, long uuidLsb, long uuidMsb)
            throws RemoteException {

        UUID uuid = new UUID(uuidMsb, uuidLsb);
        if (DBG) Log.d(TAG, "onServerRegistered() - UUID=" + uuid + ", serverIf=" + serverIf);
        ServerMap.App app = mServerMap.getByUuid(uuid);
        if (app != null) {
            app.id = serverIf;
            app.linkToDeath(new ServerDeathRecipient(serverIf));
            app.callback.onServerRegistered(status, serverIf);
        }
    }

    void onServiceAdded(int status, int serverIf, int srvcType, int srvcInstId,
                        long srvcUuidLsb, long srvcUuidMsb, int srvcHandle)
                        throws RemoteException {
        UUID uuid = new UUID(srvcUuidMsb, srvcUuidLsb);
        if (DBG) Log.d(TAG, "onServiceAdded() UUID=" + uuid + ", status=" + status
            + ", handle=" + srvcHandle);
        if (status == 0) {
            mHandleMap.addService(serverIf, srvcHandle, uuid, srvcType, srvcInstId,
                mAdvertisingServiceUuids.remove(uuid));
        }

        continueServiceDeclaration(serverIf, status, srvcHandle);
    }

    void onIncludedServiceAdded(int status, int serverIf, int srvcHandle,
                                int includedSrvcHandle) throws RemoteException {
        if (DBG) Log.d(TAG, "onIncludedServiceAdded() status=" + status
            + ", service=" + srvcHandle + ", included=" + includedSrvcHandle);
        continueServiceDeclaration(serverIf, status, srvcHandle);
    }

    void onCharacteristicAdded(int status, int serverIf,
                               long charUuidLsb, long charUuidMsb,
                               int srvcHandle, int charHandle)
                               throws RemoteException {
            UUID uuid = new UUID(charUuidMsb, charUuidLsb);
        if (DBG) Log.d(TAG, "onCharacteristicAdded() UUID=" + uuid + ", status=" + status
            + ", srvcHandle=" + srvcHandle + ", charHandle=" + charHandle);
        if (status == 0)
            mHandleMap.addCharacteristic(serverIf, charHandle, uuid, srvcHandle);
        continueServiceDeclaration(serverIf, status, srvcHandle);
    }

    void onDescriptorAdded(int status, int serverIf,
                           long descrUuidLsb, long descrUuidMsb,
                           int srvcHandle, int descrHandle)
                           throws RemoteException {
            UUID uuid = new UUID(descrUuidMsb, descrUuidLsb);
        if (DBG) Log.d(TAG, "onDescriptorAdded() UUID=" + uuid + ", status=" + status
            + ", srvcHandle=" + srvcHandle + ", descrHandle=" + descrHandle);
        if (status == 0)
            mHandleMap.addDescriptor(serverIf, descrHandle, uuid, srvcHandle);
        continueServiceDeclaration(serverIf, status, srvcHandle);
    }

    void onServiceStarted(int status, int serverIf, int srvcHandle)
            throws RemoteException {
        if (DBG) Log.d(TAG, "onServiceStarted() srvcHandle=" + srvcHandle
            + ", status=" + status);
        if (status == 0)
            mHandleMap.setStarted(serverIf, srvcHandle, true);
    }

    void onServiceStopped(int status, int serverIf, int srvcHandle)
            throws RemoteException {
        if (DBG) Log.d(TAG, "onServiceStopped() srvcHandle=" + srvcHandle
            + ", status=" + status);
        if (status == 0)
            mHandleMap.setStarted(serverIf, srvcHandle, false);
        stopNextService(serverIf, status);
    }

    void onServiceDeleted(int status, int serverIf, int srvcHandle) {
        if (DBG) Log.d(TAG, "onServiceDeleted() srvcHandle=" + srvcHandle
            + ", status=" + status);
        mHandleMap.deleteService(serverIf, srvcHandle);
    }

    void onClientConnected(String address, boolean connected, int connId, int serverIf)
            throws RemoteException {

        if (DBG) Log.d(TAG, "onConnected() connId=" + connId
            + ", address=" + address + ", connected=" + connected);

        ServerMap.App app = mServerMap.getById(serverIf);
        if (app == null) return;

        if (connected) {
            mServerMap.addConnection(serverIf, connId, address);
        } else {
            mServerMap.removeConnection(serverIf, connId);
        }

        app.callback.onServerConnectionState((byte)0, serverIf, connected, address);
    }

    void onAttributeRead(String address, int connId, int transId,
                            int attrHandle, int offset, boolean isLong)
                            throws RemoteException {
        if (VDBG) Log.d(TAG, "onAttributeRead() connId=" + connId
            + ", address=" + address + ", handle=" + attrHandle
            + ", requestId=" + transId + ", offset=" + offset);

        HandleMap.Entry entry = mHandleMap.getByHandle(attrHandle);
        if (entry == null) return;

        if (DBG) Log.d(TAG, "onAttributeRead() UUID=" + entry.uuid
            + ", serverIf=" + entry.serverIf + ", type=" + entry.type);

        mHandleMap.addRequest(transId, attrHandle);

        ServerMap.App app = mServerMap.getById(entry.serverIf);
        if (app == null) return;

        switch(entry.type) {
            case HandleMap.TYPE_CHARACTERISTIC:
            {
                HandleMap.Entry serviceEntry = mHandleMap.getByHandle(entry.serviceHandle);
                if (null != serviceEntry) {
                app.callback.onCharacteristicReadRequest(address, transId, offset, isLong,
                    serviceEntry.serviceType, serviceEntry.instance,
                    new ParcelUuid(serviceEntry.uuid), entry.instance,
                    new ParcelUuid(entry.uuid));
                }else {
                    Log.d(TAG, "null == serviceEntry");
                }
                break;
            }

            case HandleMap.TYPE_DESCRIPTOR:
            {
                HandleMap.Entry serviceEntry = mHandleMap.getByHandle(entry.serviceHandle);
                HandleMap.Entry charEntry = mHandleMap.getByHandle(entry.charHandle);
                if (null != serviceEntry && null != charEntry) {
                    app.callback.onDescriptorReadRequest(address, transId, offset, isLong,
                    serviceEntry.serviceType, serviceEntry.instance,
                    new ParcelUuid(serviceEntry.uuid), charEntry.instance,
                    new ParcelUuid(charEntry.uuid),
                    new ParcelUuid(entry.uuid));
                } else {
                 Log.d(TAG, "null == serviceEntry || null == charEntry");
                }
                break;
            }

            default:
                Log.e(TAG, "onAttributeRead() - Requested unknown attribute type.");
                break;
        }
    }

    void onAttributeWrite(String address, int connId, int transId,
                            int attrHandle, int offset, int length,
                            boolean needRsp, boolean isPrep,
                            byte[] data)
                            throws RemoteException {
        if (VDBG) Log.d(TAG, "onAttributeWrite() connId=" + connId
            + ", address=" + address + ", handle=" + attrHandle
            + ", requestId=" + transId + ", isPrep=" + isPrep
            + ", offset=" + offset);

        HandleMap.Entry entry = mHandleMap.getByHandle(attrHandle);
        if (entry == null) return;

        if (DBG) Log.d(TAG, "onAttributeWrite() UUID=" + entry.uuid
            + ", serverIf=" + entry.serverIf + ", type=" + entry.type);

        mHandleMap.addRequest(transId, attrHandle);

        ServerMap.App app = mServerMap.getById(entry.serverIf);
        if (app == null) return;

        switch(entry.type) {
            case HandleMap.TYPE_CHARACTERISTIC:
            {
                HandleMap.Entry serviceEntry = mHandleMap.getByHandle(entry.serviceHandle);
                if (null != serviceEntry) {
                app.callback.onCharacteristicWriteRequest(address, transId,
                            offset, length, isPrep, needRsp,
                            serviceEntry.serviceType, serviceEntry.instance,
                            new ParcelUuid(serviceEntry.uuid), entry.instance,
                            new ParcelUuid(entry.uuid), data);
                }else {
                    Log.d(TAG, "null == serviceEntry");
                }
                break;
            }

            case HandleMap.TYPE_DESCRIPTOR:
            {
                HandleMap.Entry serviceEntry = mHandleMap.getByHandle(entry.serviceHandle);
                HandleMap.Entry charEntry = mHandleMap.getByHandle(entry.charHandle);
                if (null != serviceEntry && null != charEntry) {
                app.callback.onDescriptorWriteRequest(address, transId,
                            offset, length, isPrep, needRsp,
                            serviceEntry.serviceType, serviceEntry.instance,
                            new ParcelUuid(serviceEntry.uuid), charEntry.instance,
                            new ParcelUuid(charEntry.uuid),
                            new ParcelUuid(entry.uuid), data);
                } else {
                    Log.d(TAG, "null == serviceEntry || null == charEntry");
                }
                break;
            }

            default:
                Log.e(TAG, "onAttributeWrite() - Requested unknown attribute type.");
                break;
        }
    }

    void onExecuteWrite(String address, int connId, int transId, int execWrite)
            throws RemoteException {
        if (DBG) Log.d(TAG, "onExecuteWrite() connId=" + connId
            + ", address=" + address + ", transId=" + transId);

        ServerMap.App app = mServerMap.getByConnId(connId);
        if (app == null) return;

        app.callback.onExecuteWrite(address, transId, execWrite == 1);
    }

    void onResponseSendCompleted(int status, int attrHandle) {
        if (DBG) Log.d(TAG, "onResponseSendCompleted() handle=" + attrHandle);
    }

    void onNotificationSent(int connId, int status) throws RemoteException {
        if (DBG) Log.d(TAG, "onNotificationSent() connId=" + connId + ", status=" + status);

        String address = mServerMap.addressByConnId(connId);
        if (address == null) return;

        ServerMap.App app = mServerMap.getByConnId(connId);
        if (app == null) return;

        if (!app.isCongested) {
            app.callback.onNotificationSent(address, status);
        } else {
            if (status == BluetoothGatt.GATT_CONNECTION_CONGESTED) {
                status = BluetoothGatt.GATT_SUCCESS;
            }
            app.queueCallback(new CallbackInfo(address, status));
        }
    }

    void onServerCongestion(int connId, boolean congested) throws RemoteException {
        if (DBG) Log.d(TAG, "onServerCongestion() - connId=" + connId + ", congested=" + congested);

        ServerMap.App app = mServerMap.getByConnId(connId);
        if (app == null) return;

        app.isCongested = congested;
        while(!app.isCongested) {
            CallbackInfo callbackInfo = app.popQueuedCallback();
            if (callbackInfo == null) return;
            app.callback.onNotificationSent(callbackInfo.address, callbackInfo.status);
        }
    }

    /**************************************************************************
     * GATT Service functions - SERVER
     *************************************************************************/

    void registerServer(UUID uuid, IBluetoothGattServerCallback callback) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "registerServer() - UUID=" + uuid);
        mServerMap.add(uuid, callback);
        gattServerRegisterAppNative(uuid.getLeastSignificantBits(),
                                    uuid.getMostSignificantBits());
    }

    void unregisterServer(int serverIf) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "unregisterServer() - serverIf=" + serverIf);

        deleteServices(serverIf);

        mServerMap.remove(serverIf);
        gattServerUnregisterAppNative(serverIf);
    }

    void serverConnect(int serverIf, String address, boolean isDirect, int transport) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "serverConnect() - address=" + address);
        gattServerConnectNative(serverIf, address, isDirect,transport);
    }

    void serverDisconnect(int serverIf, String address) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        Integer connId = mServerMap.connIdByAddress(serverIf, address);
        if (DBG) Log.d(TAG, "serverDisconnect() - address=" + address + ", connId=" + connId);

        gattServerDisconnectNative(serverIf, address, connId != null ? connId : 0);
    }

    void beginServiceDeclaration(int serverIf, int srvcType, int srvcInstanceId,
                                 int minHandles, UUID srvcUuid, boolean advertisePreferred) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "beginServiceDeclaration() - uuid=" + srvcUuid +
                                                       " serverIf=" + serverIf);
        ServiceDeclaration serviceDeclaration = addToActiveDeclaration(serverIf);
        if (null != serviceDeclaration ) {
            serviceDeclaration.addService(srvcUuid, srvcType, srvcInstanceId, minHandles,
            advertisePreferred);
        } else {
            if (DBG) Log.d(TAG, "beginServiceDeclaration: Got null from addToActiveDeclaration()");
        }
    }

    void addIncludedService(int serverIf, int srvcType, int srvcInstanceId,
                            UUID srvcUuid) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "addIncludedService() - uuid=" + srvcUuid);
        ServiceDeclaration serviceDeclaration = getActiveDeclaration(serverIf);
        if (null != serviceDeclaration) {
            serviceDeclaration.addIncludedService(srvcUuid, srvcType, srvcInstanceId);
        } else {
            Log.d(TAG,"getActiveDeclaration(serverIf) is null");
        }
    }

    void addCharacteristic(int serverIf, UUID charUuid, int properties,
                           int permissions) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "addCharacteristic() - uuid=" + charUuid);
        ServiceDeclaration serviceDeclaration = getActiveDeclaration(serverIf);
        if (null != serviceDeclaration) {
            serviceDeclaration.addCharacteristic(charUuid, properties, permissions);
        } else {
            Log.d(TAG,"getActiveDeclaration(serverIf) is null");
        }
    }

    void addDescriptor(int serverIf, UUID descUuid, int permissions) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "addDescriptor() - uuid=" + descUuid);
        ServiceDeclaration serviceDeclaration = getActiveDeclaration(serverIf);
        if (null != serviceDeclaration) {
            serviceDeclaration.addDescriptor(descUuid, permissions);
        } else {
            Log.d(TAG,"getActiveDeclaration(serverIf) is null");
        }
    }

    void endServiceDeclaration(int serverIf) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "endServiceDeclaration()" + " serverIf=" + serverIf);

        addToPendingDeclaration(getActiveDeclaration(serverIf));

        if (getActiveDeclaration(serverIf) == getPendingDeclaration()) {
            try {
                continueServiceDeclaration(serverIf, (byte)0, 0);
            } catch (RemoteException e) {
                Log.e(TAG,""+e);
            }
        }
        removeFromActiveDeclaration(serverIf);
    }

    void removeService(int serverIf, int srvcType,
                  int srvcInstanceId, UUID srvcUuid) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "removeService() - uuid=" + srvcUuid);

        int srvcHandle = mHandleMap.getServiceHandle(srvcUuid, srvcType, srvcInstanceId);
        if (srvcHandle == 0) return;
        gattServerDeleteServiceNative(serverIf, srvcHandle);
    }

    void clearServices(int serverIf) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "clearServices()");
        deleteServices(serverIf);
    }

    void sendResponse(int serverIf, String address, int requestId,
                      int status, int offset, byte[] value) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (VDBG) Log.d(TAG, "sendResponse() - address=" + address);

        int handle = 0;
        HandleMap.Entry entry = mHandleMap.getByRequestId(requestId);
        if (entry != null) handle = entry.handle;

        Integer connId;
        if(null != (connId = mServerMap.connIdByAddress(serverIf, address))) {
            gattServerSendResponseNative(serverIf, connId, requestId, (byte)status,
                                     handle, offset, value, (byte)0);
        }
        mHandleMap.deleteRequest(requestId);
    }

    void sendNotification(int serverIf, String address, int srvcType,
                                 int srvcInstanceId, UUID srvcUuid,
                                 int charInstanceId, UUID charUuid,
                                 boolean confirm, byte[] value) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (VDBG) Log.d(TAG, "sendNotification() - address=" + address);

        int srvcHandle = mHandleMap.getServiceHandle(srvcUuid, srvcType, srvcInstanceId);
        if (srvcHandle == 0) return;

        int charHandle = mHandleMap.getCharacteristicHandle(srvcHandle, charUuid, charInstanceId);
        if (charHandle == 0) return;

        Integer connId = mServerMap.connIdByAddress(serverIf, address);
        if (connId == null) return;
        if (confirm) {
            gattServerSendIndicationNative(serverIf, charHandle, connId, value);
        } else {
            gattServerSendNotificationNative(serverIf, charHandle, connId, value);
        }
    }


    /**************************************************************************
     * Private functions
     *************************************************************************/

    private int getDeviceType(BluetoothDevice device) {
        int type = gattClientGetDeviceTypeNative(device.getAddress());
        if (DBG) Log.d(TAG, "getDeviceType() - device=" + device
            + ", type=" + type);
        return type;
    }

    private void enforceAdminPermission() {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
    }

    private boolean needsPrivilegedPermissionForScan(ScanSettings settings) {
        // Regular scan, no special permission.
        if (settings == null) {
            return false;
        }
        // Hidden API for onLost/onFound
        if (settings.getCallbackType() != ScanSettings.CALLBACK_TYPE_ALL_MATCHES) {
            return true;
        }
        // Regular scan, no special permission.
        if (settings.getReportDelayMillis() == 0) {
            return false;
        }
        // Batch scan, truncated mode needs permission.
        return settings.getScanResultType() == ScanSettings.SCAN_RESULT_TYPE_ABBREVIATED;
    }

    // Enforce caller has BLUETOOTH_PRIVILEGED permission. A {@link SecurityException} will be
    // thrown if the caller app does not have BLUETOOTH_PRIVILEGED permission.
    private void enforcePrivilegedPermission() {
        enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED,
            "Need BLUETOOTH_PRIVILEGED permission");
    }

    private void continueSearch(int connId, int status) throws RemoteException {
        if (status == 0 && !mSearchQueue.isEmpty()) {
            SearchQueue.Entry svc = mSearchQueue.pop();

            if (svc.charUuidLsb == 0) {
                // Characteristic is up next
                gattClientGetCharacteristicNative(svc.connId, svc.srvcType,
                    svc.srvcInstId, svc.srvcUuidLsb, svc.srvcUuidMsb, 0, 0, 0);
            } else {
                // Descriptor is up next
                gattClientGetDescriptorNative(svc.connId, svc.srvcType,
                    svc.srvcInstId, svc.srvcUuidLsb, svc.srvcUuidMsb,
                    svc.charInstId, svc.charUuidLsb, svc.charUuidMsb, 0, 0, 0);
            }
        } else {
            ClientMap.App app = mClientMap.getByConnId(connId);
            if (app != null) {
                app.callback.onSearchComplete(mClientMap.addressByConnId(connId), status);
            }
        }
    }

    private void continueServiceDeclaration(int serverIf, int status, int srvcHandle) throws RemoteException {
        if (mServiceDeclarations.size() == 0) return;
        if (DBG) Log.d(TAG, "continueServiceDeclaration() - srvcHandle=" + srvcHandle);
        ServiceDeclaration serviceDeclaration;
        boolean finished = false;

        ServiceDeclaration.Entry entry = null;
        if (status == 0) {
            if (null != (serviceDeclaration = getPendingDeclaration()))
            entry = serviceDeclaration.getNext();
        }

        if (entry != null) {
            if (DBG) Log.d(TAG, "continueServiceDeclaration() - next entry type="
                + entry.type);
            switch(entry.type) {
                case ServiceDeclaration.TYPE_SERVICE:
                    if (entry.advertisePreferred) {
                        mAdvertisingServiceUuids.add(entry.uuid);
                    }
                    if (null != (serviceDeclaration = getPendingDeclaration())) {
                        gattServerAddServiceNative(serverIf, entry.serviceType,
                        entry.instance,
                        entry.uuid.getLeastSignificantBits(),
                        entry.uuid.getMostSignificantBits(),
                        serviceDeclaration.getNumHandles());
                    }
                    break;

                case ServiceDeclaration.TYPE_CHARACTERISTIC:
                    gattServerAddCharacteristicNative(serverIf, srvcHandle,
                        entry.uuid.getLeastSignificantBits(),
                        entry.uuid.getMostSignificantBits(),
                        entry.properties, entry.permissions);
                    break;

                case ServiceDeclaration.TYPE_DESCRIPTOR:
                    gattServerAddDescriptorNative(serverIf, srvcHandle,
                        entry.uuid.getLeastSignificantBits(),
                        entry.uuid.getMostSignificantBits(),
                        entry.permissions);
                    break;

                case ServiceDeclaration.TYPE_INCLUDED_SERVICE:
                {
                    int inclSrvc = mHandleMap.getServiceHandle(entry.uuid,
                                            entry.serviceType, entry.instance);
                    if (inclSrvc != 0) {
                        gattServerAddIncludedServiceNative(serverIf, srvcHandle,
                                                           inclSrvc);
                    } else {
                        finished = true;
                    }
                    break;
                }
            }
        } else {
            gattServerStartServiceNative(serverIf, srvcHandle,
                (byte)BluetoothDevice.TRANSPORT_BREDR | BluetoothDevice.TRANSPORT_LE);
            finished = true;
        }

        if (finished) {
            if (DBG) Log.d(TAG, "continueServiceDeclaration() - completed.");
            ServerMap.App app = mServerMap.getById(serverIf);
            if (app != null) {
                HandleMap.Entry serviceEntry = mHandleMap.getByHandle(srvcHandle);

                if (serviceEntry != null) {
                    app.callback.onServiceAdded(status, serviceEntry.serviceType,
                        serviceEntry.instance, new ParcelUuid(serviceEntry.uuid));
                } else {
                    app.callback.onServiceAdded(status, 0, 0, null);
                }
            }
            removePendingDeclaration();

            if (getPendingDeclaration() != null) {
                continueServiceDeclaration(serverIf, (byte)0, 0);
            }
        }
    }

    private void stopNextService(int serverIf, int status) throws RemoteException {
        if (DBG) Log.d(TAG, "stopNextService() - serverIf=" + serverIf
            + ", status=" + status);

        if (status == 0) {
            List<HandleMap.Entry> entries = mHandleMap.getEntries();
            for(HandleMap.Entry entry : entries) {
                if (entry.type != HandleMap.TYPE_SERVICE ||
                    entry.serverIf != serverIf ||
                    entry.started == false)
                        continue;

                gattServerStopServiceNative(serverIf, entry.handle);
                return;
            }
        }
    }

    private void deleteServices(int serverIf) {
        if (DBG) Log.d(TAG, "deleteServices() - serverIf=" + serverIf);

        /*
         * Figure out which handles to delete.
         * The handles are copied into a new list to avoid race conditions.
         */
        List<Integer> handleList = new ArrayList<Integer>();
        List<HandleMap.Entry> entries = mHandleMap.getEntries();
        for(HandleMap.Entry entry : entries) {
            if (entry.type != HandleMap.TYPE_SERVICE ||
                entry.serverIf != serverIf)
                    continue;
            handleList.add(entry.handle);
        }

        /* Now actually delete the services.... */
        for(Integer handle : handleList) {
            gattServerDeleteServiceNative(serverIf, handle);
        }
    }

    private List<UUID> parseUuids(byte[] adv_data) {
        List<UUID> uuids = new ArrayList<UUID>();

        int offset = 0;
        while(offset < (adv_data.length-2)) {
            int len = adv_data[offset++];
            if (len == 0) break;

            int type = adv_data[offset++];
            switch (type) {
                case 0x02: // Partial list of 16-bit UUIDs
                case 0x03: // Complete list of 16-bit UUIDs
                    while (len > 1) {
                        int uuid16 = adv_data[offset++];
                        uuid16 += (adv_data[offset++] << 8);
                        len -= 2;
                        uuids.add(UUID.fromString(String.format(
                            "%08x-0000-1000-8000-00805f9b34fb", uuid16)));
                    }
                    break;

                default:
                    offset += (len - 1);
                    break;
            }
        }

        return uuids;
    }

    /**************************************************************************
     * GATT Test functions
     *************************************************************************/

    void gattTestCommand(int command, UUID uuid1, String bda1,
                         int p1, int p2, int p3, int p4, int p5) {
        if (bda1 == null) bda1 = "00:00:00:00:00:00";
        if (uuid1 != null)
            gattTestNative(command, uuid1.getLeastSignificantBits(),
                       uuid1.getMostSignificantBits(), bda1, p1, p2, p3, p4, p5);
        else
            gattTestNative(command, 0,0, bda1, p1, p2, p3, p4, p5);
    }

    private native void gattTestNative(int command,
                                    long uuid1_lsb, long uuid1_msb, String bda1,
                                    int p1, int p2, int p3, int p4, int p5);

    /**************************************************************************
     * Native functions prototypes
     *************************************************************************/

    private native static void classInitNative();
    private native void initializeNative();
    private native void cleanupNative();

    private native int gattClientGetDeviceTypeNative(String address);

    private native void gattClientRegisterAppNative(long app_uuid_lsb,
                                                    long app_uuid_msb);

    private native void gattClientUnregisterAppNative(int clientIf);

    private native void gattClientConnectNative(int clientIf, String address,
            boolean isDirect, int transport);

    private native void gattClientDisconnectNative(int clientIf, String address,
            int conn_id);

    private native void gattClientRefreshNative(int clientIf, String address);

    private native void gattClientSearchServiceNative(int conn_id,
            boolean search_all, long service_uuid_lsb, long service_uuid_msb);

    private native void gattClientGetCharacteristicNative(int conn_id,
            int service_type, int service_id_inst_id, long service_id_uuid_lsb,
            long service_id_uuid_msb, int char_id_inst_id, long char_id_uuid_lsb,
            long char_id_uuid_msb);

    private native void gattClientGetDescriptorNative(int conn_id, int service_type,
            int service_id_inst_id, long service_id_uuid_lsb, long service_id_uuid_msb,
            int char_id_inst_id, long char_id_uuid_lsb, long char_id_uuid_msb,
            int descr_id_inst_id, long descr_id_uuid_lsb, long descr_id_uuid_msb);

    private native void gattClientGetIncludedServiceNative(int conn_id,
            int service_type, int service_id_inst_id,
            long service_id_uuid_lsb, long service_id_uuid_msb,
            int incl_service_id_inst_id, int incl_service_type,
            long incl_service_id_uuid_lsb, long incl_service_id_uuid_msb);

    private native void gattClientReadCharacteristicNative(int conn_id,
            int service_type, int service_id_inst_id, long service_id_uuid_lsb,
            long service_id_uuid_msb, int char_id_inst_id, long char_id_uuid_lsb,
            long char_id_uuid_msb, int authReq);

    private native void gattClientReadDescriptorNative(int conn_id, int service_type,
            int service_id_inst_id, long service_id_uuid_lsb, long service_id_uuid_msb,
            int char_id_inst_id, long char_id_uuid_lsb, long char_id_uuid_msb,
            int descr_id_inst_id, long descr_id_uuid_lsb, long descr_id_uuid_msb,
            int authReq);

    private native void gattClientWriteCharacteristicNative(int conn_id,
            int service_type, int service_id_inst_id, long service_id_uuid_lsb,
            long service_id_uuid_msb, int char_id_inst_id, long char_id_uuid_lsb,
            long char_id_uuid_msb, int write_type, int auth_req, byte[] value);

    private native void gattClientWriteDescriptorNative(int conn_id, int service_type,
            int service_id_inst_id, long service_id_uuid_lsb, long service_id_uuid_msb,
            int char_id_inst_id, long char_id_uuid_lsb, long char_id_uuid_msb,
            int descr_id_inst_id, long descr_id_uuid_lsb, long descr_id_uuid_msb,
            int write_type, int auth_req, byte[] value);

    private native void gattClientExecuteWriteNative(int conn_id, boolean execute);

    private native void gattClientRegisterForNotificationsNative(int clientIf,
            String address, int service_type, int service_id_inst_id,
            long service_id_uuid_lsb, long service_id_uuid_msb,
            int char_id_inst_id, long char_id_uuid_lsb, long char_id_uuid_msb,
            boolean enable);

    private native void gattClientReadRemoteRssiNative(int clientIf,
            String address);

    private native void gattAdvertiseNative(int client_if, boolean start);

    private native void gattClientConfigureMTUNative(int conn_id, int mtu);

    private native void gattConnectionParameterUpdateNative(int client_if, String address,
            int minInterval, int maxInterval, int latency, int timeout);

    private native void gattSetAdvDataNative(int serverIf, boolean setScanRsp, boolean inclName,
            boolean inclTxPower, int minInterval, int maxInterval,
            int appearance, byte[] manufacturerData, byte[] serviceData, byte[] serviceUuid);

    private native void gattServerRegisterAppNative(long app_uuid_lsb,
                                                    long app_uuid_msb);

    private native void gattServerUnregisterAppNative(int serverIf);

    private native void gattServerConnectNative(int server_if, String address,
                                             boolean is_direct, int transport);

    private native void gattServerDisconnectNative(int serverIf, String address,
                                              int conn_id);

    private native void gattServerAddServiceNative (int server_if,
            int service_type, int service_id_inst_id,
            long service_id_uuid_lsb, long service_id_uuid_msb,
            int num_handles);

    private native void gattServerAddIncludedServiceNative (int server_if,
            int svc_handle, int included_svc_handle);

    private native void gattServerAddCharacteristicNative (int server_if,
            int svc_handle, long char_uuid_lsb, long char_uuid_msb,
            int properties, int permissions);

    private native void gattServerAddDescriptorNative (int server_if,
            int svc_handle, long desc_uuid_lsb, long desc_uuid_msb,
            int permissions);

    private native void gattServerStartServiceNative (int server_if,
            int svc_handle, int transport );

    private native void gattServerStopServiceNative (int server_if,
                                                     int svc_handle);

    private native void gattServerDeleteServiceNative (int server_if,
                                                       int svc_handle);

    private native void gattServerSendIndicationNative (int server_if,
            int attr_handle, int conn_id, byte[] val);

    private native void gattServerSendNotificationNative (int server_if,
            int attr_handle, int conn_id, byte[] val);

    private native void gattServerSendResponseNative (int server_if,
            int conn_id, int trans_id, int status, int handle, int offset,
            byte[] val, int auth_req);
}
