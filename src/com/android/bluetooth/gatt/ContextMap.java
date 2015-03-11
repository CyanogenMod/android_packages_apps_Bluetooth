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

import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.IInterface;
import android.os.RemoteException;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper class that keeps track of registered GATT applications.
 * This class manages application callbacks and keeps track of GATT connections.
 * @hide
 */
/*package*/ class ContextMap<T> {
    private static final String TAG = GattServiceConfig.TAG_PREFIX + "ContextMap";

    /**
     * Connection class helps map connection IDs to device addresses.
     */
    class Connection {
        int connId;
        String address;
        int appId;

        Connection(int connId, String address,int appId) {
            this.connId = connId;
            this.address = address;
            this.appId = appId;
        }
    }

    /**
     * Application entry mapping UUIDs to appIDs and callbacks.
     */
    class App {
        /** The UUID of the application */
        UUID uuid;

        /** The id of the application */
        int id;

        /** Application callbacks */
        T callback;

        /** Death receipient */
        private IBinder.DeathRecipient mDeathRecipient;

        /** Flag to signal that transport is congested */
        Boolean isCongested = false;

        /** Internal callback info queue, waiting to be send on congestion clear */
        private List<CallbackInfo> congestionQueue = new ArrayList<CallbackInfo>();

        /**
         * Creates a new app context.
         */
        App(UUID uuid, T callback) {
            this.uuid = uuid;
            this.callback = callback;
        }

        /**
         * Link death recipient
         */
        void linkToDeath(IBinder.DeathRecipient deathRecipient) {
            try {
                IBinder binder = ((IInterface)callback).asBinder();
                binder.linkToDeath(deathRecipient, 0);
                mDeathRecipient = deathRecipient;
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to link deathRecipient for app id " + id);
            }
        }

        /**
         * Unlink death recipient
         */
        void unlinkToDeath() {
            if (mDeathRecipient != null) {
                try {
                    IBinder binder = ((IInterface)callback).asBinder();
                    binder.unlinkToDeath(mDeathRecipient,0);
                } catch (NoSuchElementException e) {
                    Log.e(TAG, "Unable to unlink deathRecipient for app id " + id);
                }
            }
        }

        void queueCallback(CallbackInfo callbackInfo) {
            congestionQueue.add(callbackInfo);
        }

        CallbackInfo popQueuedCallback() {
            if (congestionQueue.size() == 0) return null;
            return congestionQueue.remove(0);
        }
    }

    /** Our internal application list */
    List<App> mApps = new ArrayList<App>();

    /** Internal list of connected devices **/
    Set<Connection> mConnections = new HashSet<Connection>();

    /**
     * Add an entry to the application context list.
     */
    void add(UUID uuid, T callback) {
        synchronized (mApps) {
            mApps.add(new App(uuid, callback));
        }
    }

    /**
     * Remove the context for a given UUID
     */
    void remove(UUID uuid) {
        synchronized (mApps) {
            Iterator<App> i = mApps.iterator();
            while(i.hasNext()) {
                App entry = i.next();
                if (entry.uuid.equals(uuid)) {
                    entry.unlinkToDeath();
                    i.remove();
                    break;
                }
            }
        }
    }

    /**
     * Remove the context for a given application ID.
     */
    void remove(int id) {
        synchronized (mApps) {
            Iterator<App> i = mApps.iterator();
            while(i.hasNext()) {
                App entry = i.next();
                if (entry.id == id) {
                    entry.unlinkToDeath();
                    i.remove();
                    break;
                }
            }
        }
    }

    /**
     * Add a new connection for a given application ID.
     */
    void addConnection(int id, int connId, String address) {
        synchronized (mConnections) {
            App entry = getById(id);
            if (entry != null){
                mConnections.add(new Connection(connId, address, id));
            }
        }
    }

    /**
     * Remove a connection with the given ID.
     */
    void removeConnection(int id, int connId) {
        synchronized (mConnections) {
            Iterator<Connection> i = mConnections.iterator();
            while(i.hasNext()) {
                Connection connection = i.next();
                if (connection.connId == connId) {
                    i.remove();
                    break;
                }
            }
        }
    }

    /**
     * Get an application context by ID.
     */
    App getById(int id) {
        Iterator<App> i = mApps.iterator();
        while(i.hasNext()) {
            App entry = i.next();
            if (entry.id == id) return entry;
        }
        Log.e(TAG, "Context not found for ID " + id);
        return null;
    }

    /**
     * Get an application context by UUID.
     */
    App getByUuid(UUID uuid) {
        Iterator<App> i = mApps.iterator();
        while(i.hasNext()) {
            App entry = i.next();
            if (entry.uuid.equals(uuid)) return entry;
        }
        Log.e(TAG, "Context not found for UUID " + uuid);
        return null;
    }

    /**
     * Get the device addresses for all connected devices
     */
    Set<String> getConnectedDevices() {
        Set<String> addresses = new HashSet<String>();
        Iterator<Connection> i = mConnections.iterator();
        while(i.hasNext()) {
            Connection connection = i.next();
            addresses.add(connection.address);
        }
        return addresses;
    }

    /**
     * Get an application context by a connection ID.
     */
    App getByConnId(int connId) {
        Iterator<Connection> ii = mConnections.iterator();
        while(ii.hasNext()) {
            Connection connection = ii.next();
            if (connection.connId == connId){
                return getById(connection.appId);
            }
        }
        return null;
    }

    /**
     * Returns a connection ID for a given device address.
     */
    Integer connIdByAddress(int id, String address) {
        App entry = getById(id);
        if (entry == null) return null;

        Iterator<Connection> i = mConnections.iterator();
        while(i.hasNext()) {
            Connection connection = i.next();
            if (connection.address.equals(address) && connection.appId == id)
                return connection.connId;
        }
        return null;
    }

    /**
     * Returns the device address for a given connection ID.
     */
    String addressByConnId(int connId) {
        Iterator<Connection> i = mConnections.iterator();
        while(i.hasNext()) {
            Connection connection = i.next();
            if (connection.connId == connId) return connection.address;
        }
        return null;
    }

    List<Connection> getConnectionByApp(int appId) {
        List<Connection> currentConnections = new ArrayList<Connection>();
        Iterator<Connection> i = mConnections.iterator();
        while(i.hasNext()) {
            Connection connection = i.next();
            if (connection.appId == appId)
                currentConnections.add(connection);
        }
        return currentConnections;
    }

    /**
     * Erases all application context entries.
     */
    void clear() {
        synchronized (mApps) {
            Iterator<App> i = mApps.iterator();
            while(i.hasNext()) {
                App entry = i.next();
                entry.unlinkToDeath();
                i.remove();
            }
        }

        synchronized (mConnections) {
            mConnections.clear();
        }
    }

    /**
     * Returns connect device map with addr and appid
     */
    Map<Integer, String> getConnectedMap(){
        Map<Integer, String> connectedmap = new HashMap<Integer, String>();
        for(Connection conn: mConnections){
            connectedmap.put(conn.appId, conn.address);
        }
        return connectedmap;
    }

    /**
     * Logs debug information.
     */
    void dump(StringBuilder sb) {
        sb.append("  Entries: " + mApps.size() + "\n");

        Iterator<App> i = mApps.iterator();
        while(i.hasNext()) {
            App entry = i.next();
            List<Connection> connections = getConnectionByApp(entry.id);

            sb.append("\n  Application Id: " + entry.id + "\n");
            sb.append("  UUID: " + entry.uuid + "\n");
            sb.append("  Connections: " + connections.size() + "\n");

            Iterator<Connection> ii = connections.iterator();
            while(ii.hasNext()) {
                Connection connection = ii.next();
                sb.append("    " + connection.connId + ": " + connection.address + "\n");
            }
        }
    }
}
