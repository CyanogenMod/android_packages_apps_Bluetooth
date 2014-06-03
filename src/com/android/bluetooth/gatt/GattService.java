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
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothGattServerCallback;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;

import com.android.bluetooth.btservice.ProfileService;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.android.bluetooth.LeScanRequestArbitrator;

/**
 * Provides Bluetooth Gatt profile, as a service in
 * the Bluetooth application.
 * @hide
 */
public class GattService extends ProfileService {
    private static final boolean DBG = GattServiceConfig.DBG;
    private static final String TAG = GattServiceConfig.TAG_PREFIX + "GattService";
    private static final int DEFAULT_SCAN_INTERVAL_MILLIS = 200;

    /**
     * Max packet size for ble advertising, defined in Bluetooth Specification Version 4.0 [Vol 3].
     */
    private static final int ADVERTISING_PACKET_MAX_BYTES = 31;
    /**
     * Size overhead for advertising flag.
     */
    private static final int ADVERTISING_FLAGS_BYTES = 3;
    /**
     * Size overhead per field. Usually it's just one byte of field length and one byte of
     * field type.
     */
    private static final int FIELD_OVERHEAD_BYTES = 2;

    /**
     * Byte size of 16 bit service uuid.
     */
    private static final int SHORT_UUID_BYTES = 2;
    /**
     * Byte size of 128 bit service uuid.
     */
    private static final int FULL_UUID_BYTES = 16;

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

    private int mAdvertisingClientIf = 0;

    private byte[] mServiceData = new byte[0];
    private int mManufacturerCode = -1;
    private byte[] mManufacturerData = new byte[0];
    private Integer mAdvertisingState = BluetoothAdapter.STATE_ADVERTISE_STOPPED;
    private final Object mLock = new Object();

    /**
     * Pending service declaration queue
     */
    private List<ServiceDeclaration> mServiceDeclarations = new ArrayList<ServiceDeclaration>();

    private ServiceDeclaration addDeclaration() {
        synchronized (mServiceDeclarations) {
            mServiceDeclarations.add(new ServiceDeclaration());
        }
        return getActiveDeclaration();
    }

    private ServiceDeclaration getActiveDeclaration() {
        synchronized (mServiceDeclarations) {
            if (mServiceDeclarations.size() > 0)
                return mServiceDeclarations.get(mServiceDeclarations.size() - 1);
        }
        return null;
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

    /**
     * List of clients interested in scan results.
     */
    private List<ScanClient> mScanQueue = new ArrayList<ScanClient>();

    private ScanClient getScanClient(int appIf, boolean isServer) {
        for(ScanClient client : mScanQueue) {
            if (client.appIf == appIf && client.isServer == isServer) {
                return client;
            }
        }
        return null;
    }

    private void removeScanClient(int appIf, boolean isServer) {
        for(ScanClient client : mScanQueue) {
          if (client.appIf == appIf && client.isServer == isServer) {
                mScanQueue.remove(client);
                break;
            }
        }
    }

    /**
     * Reliable write queue
     */
    private Set<String> mReliableQueue = new HashSet<String>();

    static {
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
        return true;
    }

    protected boolean stop() {
        if (DBG) Log.d(TAG, "stop()");
        mClientMap.clear();
        mServerMap.clear();
        mSearchQueue.clear();
        mScanQueue.clear();
        mHandleMap.clear();
        mServiceDeclarations.clear();
        mReliableQueue.clear();
        return true;
    }

    protected boolean cleanup() {
        if (DBG) Log.d(TAG, "cleanup()");
        cleanupNative();
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

        public void binderDied() {
            if (DBG) Log.d(TAG, "Binder is dead - unregistering client (" + mAppIf + ")!");
            if (mAdvertisingClientIf == mAppIf) {
                stopAdvertising(true);  // force stop advertising.
            } else {
                stopScan(mAppIf, false);
            }
            unregisterClient(mAppIf);
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

        public void startScan(int appIf, boolean isServer) {
            GattService service = getService();
            if (service == null) return;
            service.startScan(appIf, isServer);
        }

        public void startScanWithUuids(int appIf, boolean isServer, ParcelUuid[] ids) {
            GattService service = getService();
            if (service == null) return;
            UUID[] uuids = new UUID[ids.length];
            for(int i = 0; i != ids.length; ++i) {
                uuids[i] = ids[i].getUuid();
            }
            service.startScanWithUuids(appIf, isServer, uuids);
        }

        public void stopScan(int appIf, boolean isServer) {
            GattService service = getService();
            if (service == null) return;
            service.stopScan(appIf, isServer);
        }

        public void clientConnect(int clientIf, String address, boolean isDirect) {
            GattService service = getService();
            if (service == null) return;
            service.clientConnect(clientIf, address, isDirect);
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

        public void serverConnect(int serverIf, String address, boolean isDirect) {
            GattService service = getService();
            if (service == null) return;
            service.serverConnect(serverIf, address, isDirect);
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
        public void startAdvertising(int appIf) throws RemoteException {
            GattService service = getService();
            if (service == null) return;
            service.startAdvertising(appIf);
        }

        @Override
        public boolean isAdvertising() {
            GattService service = getService();
            if (service == null) return false;
            return service.isAdvertising();
        }

        @Override
        public void stopAdvertising() throws RemoteException {
            GattService service = getService();
            if (service == null) return;
            service.stopAdvertising();
        }

        @Override
        public boolean setAdvServiceData(byte[] serviceData) throws RemoteException {
            GattService service = getService();
            if (service == null) return false;
            return service.setAdvServiceData(serviceData);
        }

        @Override
        public byte[] getAdvServiceData() throws RemoteException {
            GattService service = getService();
            if (service == null) return null;
            return service.getAdvServiceData();
        }

        @Override
        public boolean setAdvManufacturerCodeAndData(int manufactureCode, byte[] manufacturerData)
            throws RemoteException {
            GattService service = getService();
            if (service == null) return false;
            return service.setAdvManufacturerCodeAndData(manufactureCode, manufacturerData);
        }

        @Override
        public byte[] getAdvManufacturerData() throws RemoteException {
            GattService service = getService();
            if (service == null) return null;
            return service.getAdvManufacturerData();
        }

        @Override
        public List<ParcelUuid> getAdvServiceUuids() throws RemoteException {
            GattService service = getService();
            if (service == null) return null;
            return service.getAdvServiceUuids();
        }

        @Override
        public void removeAdvManufacturerCodeAndData(int manufacturerCode) throws RemoteException {
            GattService service = getService();
            if (service == null) return;
            service.removeAdvManufacturerCodeAndData(manufacturerCode);
        }
    };

    /**************************************************************************
     * Callback functions - CLIENT
     *************************************************************************/

    void onScanResult(String address, int rssi, byte[] adv_data) {
        if (DBG) Log.d(TAG, "onScanResult() - address=" + address
                    + ", rssi=" + rssi);

        List<UUID> remoteUuids = parseUuids(adv_data);
        for (ScanClient client : mScanQueue) {
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
                ClientMap.App app = mClientMap.getById(client.appIf);
                if (app != null) {
                    try {
                        app.callback.onScanResult(address, rssi, adv_data);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Exception: " + e);
                        mClientMap.remove(client.appIf);
                        mScanQueue.remove(client);
                    }
                }
            } else {
                ServerMap.App app = mServerMap.getById(client.appIf);
                if (app != null) {
                    try {
                        app.callback.onScanResult(address, rssi, adv_data);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Exception: " + e);
                        mServerMap.remove(client.appIf);
                        mScanQueue.remove(client);
                    }
                }
            }
        }
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
            app.callback.onClientConnectionState(status, clientIf, true, address);
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

        if (DBG) Log.d(TAG, "onSearchResult() - address=" + address + ", uuid=" + uuid);

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

        if (DBG) Log.d(TAG, "onGetCharacteristic() - address=" + address
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

        if (DBG) Log.d(TAG, "onGetDescriptor() - address=" + address
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

        if (DBG) Log.d(TAG, "onGetIncludedService() - address=" + address
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

        if (DBG) Log.d(TAG, "onNotify() - address=" + address
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

        if (DBG) Log.d(TAG, "onReadCharacteristic() - address=" + address
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

        if (DBG) Log.d(TAG, "onWriteCharacteristic() - address=" + address
            + ", status=" + status);

        ClientMap.App app = mClientMap.getByConnId(connId);
        if (app != null) {
            app.callback.onCharacteristicWrite(address, status, srvcType,
                        srvcInstId, new ParcelUuid(srvcUuid),
                        charInstId, new ParcelUuid(charUuid));
        }
    }

    void onExecuteCompleted(int connId, int status) throws RemoteException {
        String address = mClientMap.addressByConnId(connId);
        if (DBG) Log.d(TAG, "onExecuteCompleted() - address=" + address
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

        if (DBG) Log.d(TAG, "onReadDescriptor() - address=" + address
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

        if (DBG) Log.d(TAG, "onWriteDescriptor() - address=" + address
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

    void onAdvertiseCallback(int status, int clientIf) throws RemoteException {
        if (DBG) Log.d(TAG, "onClientListen() status=" + status);
        synchronized (mLock) {
            if (DBG) Log.d(TAG, "state" + mAdvertisingState);
            // Invalid advertising state
            if (mAdvertisingState == BluetoothAdapter.STATE_ADVERTISE_STARTED ||
                    mAdvertisingState == BluetoothAdapter.STATE_ADVERTISE_STOPPED) {
                Log.e(TAG, "invalid callback state " + mAdvertisingState);
                return;
            }

            // Force stop advertising, no callback.
            if (mAdvertisingState == BluetoothAdapter.STATE_ADVERTISE_FORCE_STOPPING) {
                mAdvertisingState = BluetoothAdapter.STATE_ADVERTISE_STOPPED;
                mAdvertisingClientIf = 0;
                sendBroadcast(new Intent(
                        BluetoothAdapter.ACTION_BLUETOOTH_ADVERTISING_STOPPED));
                return;
            }

            if (mAdvertisingState == BluetoothAdapter.STATE_ADVERTISE_STARTING) {
                if (status == 0) {
                    mAdvertisingClientIf = clientIf;
                    mAdvertisingState = BluetoothAdapter.STATE_ADVERTISE_STARTED;
                    sendBroadcast(new Intent(
                            BluetoothAdapter.ACTION_BLUETOOTH_ADVERTISING_STARTED));
                } else {
                    mAdvertisingState = BluetoothAdapter.STATE_ADVERTISE_STOPPED;
                }
            } else if (mAdvertisingState == BluetoothAdapter.STATE_ADVERTISE_STOPPING) {
                if (status == 0) {
                    mAdvertisingState = BluetoothAdapter.STATE_ADVERTISE_STOPPED;
                    sendBroadcast(new Intent(
                            BluetoothAdapter.ACTION_BLUETOOTH_ADVERTISING_STOPPED));
                    mAdvertisingClientIf = 0;
                } else {
                    mAdvertisingState = BluetoothAdapter.STATE_ADVERTISE_STARTED;
                }
            }
        }
        ClientMap.App app = mClientMap.getById(clientIf);
        if (app == null || app.callback == null) {
            Log.e(TAG, "app or callback is null");
            return;
        }
        app.callback.onAdvertiseStateChange(mAdvertisingState, status);
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

    void startScan(int appIf, boolean isServer) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");

        if (DBG) Log.d(TAG, "startScan() - queue=" + mScanQueue.size());

        if (getScanClient(appIf, isServer) == null &&
            LeScanRequestArbitrator.instance().RequestLeScan(LeScanRequestArbitrator.LE_NORMAL_SCAN_TYPE)) {
            if (DBG) Log.d(TAG, "startScan() - adding client=" + appIf);
            mScanQueue.add(new ScanClient(appIf, isServer));
            if(mScanQueue.size()==1)//start scan only if it is not already started
            {
                gattClientScanNative(appIf, true);
            }
            else
            {
                Log.d(TAG, "startScan scan already in progress for appifs-queue=" + mScanQueue.size());
            }
        }
    }

    void startScanWithUuids(int appIf, boolean isServer, UUID[] uuids) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");

        if (DBG) Log.d(TAG, "startScanWithUuids() - queue=" + mScanQueue.size());

        if (getScanClient(appIf, isServer) == null &&
            LeScanRequestArbitrator.instance().RequestLeScan(LeScanRequestArbitrator.LE_NORMAL_SCAN_TYPE)) {
            if (DBG) Log.d(TAG, "startScanWithUuids() - adding client=" + appIf);
            mScanQueue.add(new ScanClient(appIf, isServer, uuids));
            if(mScanQueue.size()==1)//start scan only if it is not already started
            {
                gattClientScanNative(appIf, true);
            }
            else
            {
                Log.d(TAG, "startScanWithUuids scan already in progress for appifs-queue=" + mScanQueue.size());
            }
        }
    }

    void stopScan(int appIf, boolean isServer) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH_ADMIN permission");

        if (DBG) Log.d(TAG, "stopScan() - queue=" + mScanQueue.size());
        if (getScanClient(appIf, isServer) != null) {
            LeScanRequestArbitrator.instance().StopLeScan(LeScanRequestArbitrator.LE_NORMAL_SCAN_TYPE);
            removeScanClient(appIf, isServer);
        }

        if (mScanQueue.isEmpty()) {
            if (DBG) Log.d(TAG, "stopScan() - queue empty; stopping scan");
            gattClientScanNative(appIf, false);
        }
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

    void clientConnect(int clientIf, String address, boolean isDirect) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "clientConnect() - address=" + address + ", isDirect=" + isDirect);
        gattClientConnectNative(clientIf, address, isDirect);
    }

    void clientDisconnect(int clientIf, String address) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (DBG) Log.d(TAG, "clientDisconnect() - address=" + address + ", connId=" + connId);

        gattClientDisconnectNative(clientIf, address, connId != null ? connId : 0);
    }

    synchronized boolean setAdvServiceData(byte[] serviceData) {
        enforcePrivilegedPermission();
        if (serviceData == null) return false;
        // Calculate how many more bytes are needed for advertising service data field.
        int extraBytes = (mServiceData == null) ?
                FIELD_OVERHEAD_BYTES + serviceData.length :
                    serviceData.length - mServiceData.length;
        if (getAvailableSize() < extraBytes) {
            Log.e(TAG, "cannot set service data, available size " + getAvailableSize());
            return false;
        }
        mServiceData = serviceData;
        return true;
    }

    byte[] getAdvServiceData() {
        enforcePrivilegedPermission();
        return mServiceData;
    }

    synchronized boolean setAdvManufacturerCodeAndData(
        int manufacturerCode, byte[] manufacturerData) {
        enforcePrivilegedPermission();
        if (manufacturerCode <= 0 || manufacturerData == null) {
            return false;
        }
        if (mManufacturerCode > 0 && mManufacturerData != null) {
            Log.e(TAG, "manufacture data is already set");
            return false;
        }
        if (getAvailableSize() <
            FIELD_OVERHEAD_BYTES + manufacturerData.length) {
            Log.e(TAG, "cannot set manu data, available size " + getAvailableSize());
            return false;
        }
        this.mManufacturerCode = manufacturerCode;
        this.mManufacturerData = manufacturerData;
        return true;
    }

    void removeAdvManufacturerCodeAndData(int manufacturerCode) {
        enforcePrivilegedPermission();
        if (mManufacturerCode != manufacturerCode) {
            return;
        }
        mManufacturerCode = -1;
        mManufacturerData = new byte[0];
    }

    byte[] getAdvManufacturerData() {
        enforcePrivilegedPermission();
        return mManufacturerData;
    }

    synchronized List<ParcelUuid> getAdvServiceUuids() {
        enforcePrivilegedPermission();;
        boolean fullUuidFound = false;
        List<ParcelUuid> serviceUuids = new ArrayList<ParcelUuid>();
        for (HandleMap.Entry entry : mHandleMap.mEntries) {
            if (entry.advertisePreferred) {
                ParcelUuid parcelUuid = new ParcelUuid(entry.uuid);
                if (BluetoothUuid.isShortUuid(parcelUuid)) {
                    serviceUuids.add(parcelUuid);
                } else {
                    // Allow at most one 128 bit service uuid to be advertised.
                    if (!fullUuidFound) {
                      fullUuidFound = true;
                      serviceUuids.add(parcelUuid);
                    }
                }
            }
        }
        return serviceUuids;
    }

    boolean isAdvertising() {
        enforcePrivilegedPermission();
        return mAdvertisingState != BluetoothAdapter.STATE_ADVERTISE_STOPPED;
    }

    void startAdvertising(int clientIf) {
        enforcePrivilegedPermission();
        if (DBG) Log.d(TAG, "start advertising for app - " + clientIf);
        List<ParcelUuid> serviceUuids = getAdvServiceUuids();
        int advertisingServiceUuidLength = serviceUuids == null ? 0 : serviceUuids.size();

        // Note according to Bluetooth Spec Version 4.0, for advertising and scan response data
        // "all numerical multi-byte entities and values shall use little-endian byte order".
        ByteBuffer advertisingUuidBytes = ByteBuffer.allocate(advertisingServiceUuidLength * 16)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (ParcelUuid parcelUuid : serviceUuids) {
            UUID uuid = parcelUuid.getUuid();
            // Least signifcant bits first as the advertising uuid should be in little-endian.
            advertisingUuidBytes.putLong(uuid.getLeastSignificantBits())
                    .putLong(uuid.getMostSignificantBits());
        }

        // Set advertising data.
        gattSetAdvDataNative(clientIf,
                false,  // not scan response data
                false,  // no device name
                false,  // no tx power included
                DEFAULT_SCAN_INTERVAL_MILLIS,
                DEFAULT_SCAN_INTERVAL_MILLIS,
                0,  // no appearance limit
                mManufacturerData,
                mServiceData,
                advertisingUuidBytes.array());

        // Start advertising if advertising is not already started.
        if (!isAdvertising()) {
            gattAdvertiseNative(clientIf, true);
            mAdvertisingClientIf = clientIf;
            mAdvertisingState = BluetoothAdapter.STATE_ADVERTISE_STARTING;
        }
    }

    void stopAdvertising() {
        stopAdvertising(false);
    }

    void stopAdvertising(boolean forceStop) {
        enforcePrivilegedPermission();
        gattAdvertiseNative(mAdvertisingClientIf, false);
        synchronized (mLock) {
            if (forceStop) {
                mAdvertisingState = BluetoothAdapter.STATE_ADVERTISE_FORCE_STOPPING;
            } else {
                mAdvertisingState = BluetoothAdapter.STATE_ADVERTISE_STOPPING;
            }
        }
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

        if (DBG) Log.d(TAG, "readCharacteristic() - address=" + address);

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

        if (DBG) Log.d(TAG, "writeCharacteristic() - address=" + address);

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

        if (DBG) Log.d(TAG, "readDescriptor() - address=" + address);

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

        if (DBG) Log.d(TAG, "writeDescriptor() - address=" + address);

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
        if (DBG) Log.d(TAG, "onAttributeRead() connId=" + connId
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
                app.callback.onCharacteristicReadRequest(address, transId, offset, isLong,
                    serviceEntry.serviceType, serviceEntry.instance,
                    new ParcelUuid(serviceEntry.uuid), entry.instance,
                    new ParcelUuid(entry.uuid));
                break;
            }

            case HandleMap.TYPE_DESCRIPTOR:
            {
                HandleMap.Entry serviceEntry = mHandleMap.getByHandle(entry.serviceHandle);
                HandleMap.Entry charEntry = mHandleMap.getByHandle(entry.charHandle);
                app.callback.onDescriptorReadRequest(address, transId, offset, isLong,
                    serviceEntry.serviceType, serviceEntry.instance,
                    new ParcelUuid(serviceEntry.uuid), charEntry.instance,
                    new ParcelUuid(charEntry.uuid),
                    new ParcelUuid(entry.uuid));
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
        if (DBG) Log.d(TAG, "onAttributeWrite() connId=" + connId
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
                app.callback.onCharacteristicWriteRequest(address, transId,
                            offset, length, isPrep, needRsp,
                            serviceEntry.serviceType, serviceEntry.instance,
                            new ParcelUuid(serviceEntry.uuid), entry.instance,
                            new ParcelUuid(entry.uuid), data);
                break;
            }

            case HandleMap.TYPE_DESCRIPTOR:
            {
                HandleMap.Entry serviceEntry = mHandleMap.getByHandle(entry.serviceHandle);
                HandleMap.Entry charEntry = mHandleMap.getByHandle(entry.charHandle);
                app.callback.onDescriptorWriteRequest(address, transId,
                            offset, length, isPrep, needRsp,
                            serviceEntry.serviceType, serviceEntry.instance,
                            new ParcelUuid(serviceEntry.uuid), charEntry.instance,
                            new ParcelUuid(charEntry.uuid),
                            new ParcelUuid(entry.uuid), data);
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

    void serverConnect(int serverIf, String address, boolean isDirect) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "serverConnect() - address=" + address);
        gattServerConnectNative(serverIf, address, isDirect);
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

        if (DBG) Log.d(TAG, "beginServiceDeclaration() - uuid=" + srvcUuid);
        ServiceDeclaration serviceDeclaration = addDeclaration();
        serviceDeclaration.addService(srvcUuid, srvcType, srvcInstanceId, minHandles,
            advertisePreferred);
    }

    void addIncludedService(int serverIf, int srvcType, int srvcInstanceId,
                            UUID srvcUuid) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "addIncludedService() - uuid=" + srvcUuid);
        getActiveDeclaration().addIncludedService(srvcUuid, srvcType, srvcInstanceId);
    }

    void addCharacteristic(int serverIf, UUID charUuid, int properties,
                           int permissions) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "addCharacteristic() - uuid=" + charUuid);
        getActiveDeclaration().addCharacteristic(charUuid, properties, permissions);
    }

    void addDescriptor(int serverIf, UUID descUuid, int permissions) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "addDescriptor() - uuid=" + descUuid);
        getActiveDeclaration().addDescriptor(descUuid, permissions);
    }

    void endServiceDeclaration(int serverIf) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "endServiceDeclaration()");

        if (getActiveDeclaration() == getPendingDeclaration()) {
            try {
                continueServiceDeclaration(serverIf, (byte)0, 0);
            } catch (RemoteException e) {
                Log.e(TAG,""+e);
            }
        }
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

        if (DBG) Log.d(TAG, "sendResponse() - address=" + address);

        int handle = 0;
        HandleMap.Entry entry = mHandleMap.getByRequestId(requestId);
        if (entry != null) handle = entry.handle;

        int connId = mServerMap.connIdByAddress(serverIf, address);
        gattServerSendResponseNative(serverIf, connId, requestId, (byte)status,
                                     handle, offset, value, (byte)0);
        mHandleMap.deleteRequest(requestId);
    }

    void sendNotification(int serverIf, String address, int srvcType,
                                 int srvcInstanceId, UUID srvcUuid,
                                 int charInstanceId, UUID charUuid,
                                 boolean confirm, byte[] value) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (DBG) Log.d(TAG, "sendNotification() - address=" + address);

        int srvcHandle = mHandleMap.getServiceHandle(srvcUuid, srvcType, srvcInstanceId);
        if (srvcHandle == 0) return;

        int charHandle = mHandleMap.getCharacteristicHandle(srvcHandle, charUuid, charInstanceId);
        if (charHandle == 0) return;

        int connId = mServerMap.connIdByAddress(serverIf, address);
        if (connId == 0) return;

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

    private synchronized int getAvailableSize() {
        enforcePrivilegedPermission();
        int availableSize = ADVERTISING_PACKET_MAX_BYTES - ADVERTISING_FLAGS_BYTES;

        for (ParcelUuid parcelUuid : getAdvServiceUuids()) {
            if (BluetoothUuid.isShortUuid(parcelUuid)) {
                availableSize -= FIELD_OVERHEAD_BYTES + SHORT_UUID_BYTES;
            } else {
                availableSize -= FIELD_OVERHEAD_BYTES + FULL_UUID_BYTES;
            }
        }
        if (mManufacturerCode > 0 && mManufacturerData != null) {
            availableSize -= (FIELD_OVERHEAD_BYTES + mManufacturerData.length);
        }
        if (mServiceData != null) {
            availableSize -= (FIELD_OVERHEAD_BYTES + mServiceData.length);
        }
        return availableSize;
    }

    // Enforce caller has BLUETOOTH_PRIVILEGED permission. A {@link SecurityException} will be
    // thrown if the caller app does not have BLUETOOTH_PRIVILEGED permission.
    private void enforcePrivilegedPermission() {
        enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED,
            "Need BLUETOOTH_PRIVILEGED permission");
    }

    private void continueSearch(int connId, int status) throws RemoteException {
        Log.d(TAG, "continueSearch() - connid=" + connId + ", status=" + status);

        if(mSearchQueue.isEmpty())
            Log.d(TAG,"Queue is completely empty");
        if(mSearchQueue.isEmpty(connId))
            Log.d(TAG,"continueSearch():Queue is empty for connid=" + connId);
        if (status == 0 && !mSearchQueue.isEmpty(connId)) {
            SearchQueue.Entry svc = mSearchQueue.pop(connId);

            //verify once that the popped value is correct
            if(svc.connId!=connId)
                Log.d(TAG,"continueSearch(): connid of popped value not matching: input=" + connId + "and popped="+svc.connId);

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
                Log.d(TAG,"continueSearch(): calling searchcomplete in frameworks");
                app.callback.onSearchComplete(mClientMap.addressByConnId(connId), status);
            }
        }
    }

    private void continueServiceDeclaration(int serverIf, int status, int srvcHandle) throws RemoteException {
        if (mServiceDeclarations.size() == 0) return;
        if (DBG) Log.d(TAG, "continueServiceDeclaration() - srvcHandle=" + srvcHandle);

        boolean finished = false;

        ServiceDeclaration.Entry entry = null;
        if (status == 0)
            entry = getPendingDeclaration().getNext();

        if (entry != null) {
            if (DBG) Log.d(TAG, "continueServiceDeclaration() - next entry type="
                + entry.type);
            switch(entry.type) {
                case ServiceDeclaration.TYPE_SERVICE:
                    if (entry.advertisePreferred) {
                        mAdvertisingServiceUuids.add(entry.uuid);
                    }
                    gattServerAddServiceNative(serverIf, entry.serviceType,
                        entry.instance,
                        entry.uuid.getLeastSignificantBits(),
                        entry.uuid.getMostSignificantBits(),
                        getPendingDeclaration().getNumHandles());
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
            gattServerStartServiceNative(serverIf, srvcHandle, (byte)2 /*BREDR/LE*/);
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

    private native void gattClientScanNative(int clientIf, boolean start);

    private native void gattClientConnectNative(int clientIf, String address,
            boolean isDirect);

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

    private native void gattSetAdvDataNative(int serverIf, boolean setScanRsp, boolean inclName,
            boolean inclTxPower, int minInterval, int maxInterval,
            int appearance, byte[] manufacturerData, byte[] serviceData, byte[] serviceUuid);

    private native void gattServerRegisterAppNative(long app_uuid_lsb,
                                                    long app_uuid_msb);

    private native void gattServerUnregisterAppNative(int serverIf);

    private native void gattServerConnectNative(int server_if, String address,
                                             boolean is_direct);

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
