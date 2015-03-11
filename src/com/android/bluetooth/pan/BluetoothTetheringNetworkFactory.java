/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.bluetooth.pan;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpResults;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkRequest;
import android.net.NetworkUtils;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Slog;

import com.android.bluetooth.pan.PanService;
import com.android.internal.util.AsyncChannel;

/**
 * This class tracks the data connection associated with Bluetooth
 * reverse tethering. PanService calls it when a reverse tethered
 * connection needs to be activated or deactivated.
 *
 * @hide
 */
public class BluetoothTetheringNetworkFactory extends NetworkFactory {
    private static final String NETWORK_TYPE = "Bluetooth Tethering";
    private static final String TAG = "BluetoothTetheringNetworkFactory";
    private static final int NETWORK_SCORE = 69;

    private final NetworkCapabilities mNetworkCapabilities;
    private final Context mContext;
    private final PanService mPanService;

    // All accesses to these must be synchronized(this).
    private final NetworkInfo mNetworkInfo;
    private LinkProperties mLinkProperties;
    private NetworkAgent mNetworkAgent;

    public BluetoothTetheringNetworkFactory(Context context, Looper looper, PanService panService) {
        super(looper, context, NETWORK_TYPE, new NetworkCapabilities());

        mContext = context;
        mPanService = panService;

        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_BLUETOOTH, 0, NETWORK_TYPE, "");
        mLinkProperties = new LinkProperties();
        mNetworkCapabilities = new NetworkCapabilities();
        initNetworkCapabilities();
        setCapabilityFilter(mNetworkCapabilities);
    }

    // Called by NetworkFactory when PanService and NetworkFactory both desire a Bluetooth
    // reverse-tether connection.  A network interface for Bluetooth reverse-tethering can be
    // assumed to be available because we only register our NetworkFactory when it is so.
    @Override
    protected void startNetwork() {
        // TODO: Handle DHCP renew.
        Thread dhcpThread = new Thread(new Runnable() {
            public void run() {
                LinkProperties linkProperties;
                synchronized (BluetoothTetheringNetworkFactory.this) {
                    linkProperties = mLinkProperties;
                    if (linkProperties.getInterfaceName() == null) {
                        Slog.e(TAG, "attempted to reverse tether without interface name");
                        return;
                    }
                    log("dhcpThread(+" + linkProperties.getInterfaceName() +
                            "): mNetworkInfo=" + mNetworkInfo);
                }

                DhcpResults dhcpResults = new DhcpResults();
                // TODO: Handle DHCP renewals better.
                // In general runDhcp handles DHCP renewals for us, because
                // the dhcp client stays running, but if the renewal fails,
                // we will lose our IP address and connectivity without
                // noticing.
                if (!NetworkUtils.runDhcp(linkProperties.getInterfaceName(), dhcpResults)) {
                    Slog.e(TAG, "DHCP request error:" + NetworkUtils.getDhcpError());
                    synchronized(BluetoothTetheringNetworkFactory.this) {
                        setScoreFilter(-1);
                    }
                    return;
                }

                synchronized(BluetoothTetheringNetworkFactory.this) {
                    mLinkProperties = dhcpResults.toLinkProperties(
                            linkProperties.getInterfaceName());
                    mNetworkInfo.setIsAvailable(true);
                    mNetworkInfo.setDetailedState(DetailedState.CONNECTED, null, null);

                    // Create our NetworkAgent.
                    mNetworkAgent = new NetworkAgent(getLooper(), mContext, NETWORK_TYPE,
                            mNetworkInfo, mNetworkCapabilities, mLinkProperties, NETWORK_SCORE) {
                        public void unwanted() {
                            BluetoothTetheringNetworkFactory.this.onCancelRequest();
                        };
                    };
                }
            }
        });
        dhcpThread.start();
    }

    // Called from NetworkFactory to indicate ConnectivityService no longer desires a Bluetooth
    // reverse-tether network.
    @Override
    protected void stopNetwork() {
        // Let NetworkAgent disconnect do the teardown.
    }

    // Called by the NetworkFactory, NetworkAgent or PanService to tear down network.
    private synchronized void onCancelRequest() {
        if (!TextUtils.isEmpty(mLinkProperties.getInterfaceName())) {
            NetworkUtils.stopDhcp(mLinkProperties.getInterfaceName());
        }
        mLinkProperties.clear();
        mNetworkInfo.setDetailedState(DetailedState.DISCONNECTED, null, null);
        if (mNetworkAgent != null) {
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
            mNetworkAgent = null;
        }
        for (BluetoothDevice device : mPanService.getConnectedDevices()) {
             mPanService.disconnect(device);
        }
    }

    // Called by PanService when a network interface for Bluetooth reverse-tethering
    // becomes available.  We register our NetworkFactory at this point.
    public void startReverseTether(final String iface) {
        if (iface == null || TextUtils.isEmpty(iface)) {
            Slog.e(TAG, "attempted to reverse tether with empty interface");
            return;
        }
        synchronized(this) {
            if (mLinkProperties.getInterfaceName() != null) {
                Slog.e(TAG, "attempted to reverse tether while already in process");
                return;
            }
            mLinkProperties = new LinkProperties();
            mLinkProperties.setInterfaceName(iface);
            // Advertise ourselves to ConnectivityService.
            register();
            setScoreFilter(NETWORK_SCORE);
        }
    }

    // Called by PanService when a network interface for Bluetooth reverse-tethering
    // goes away.  We stop advertising ourselves to ConnectivityService at this point.
    public synchronized void stopReverseTether() {
        if (TextUtils.isEmpty(mLinkProperties.getInterfaceName())) {
            Slog.e(TAG, "attempted to stop reverse tether with nothing tethered");
            return;
        }
        onCancelRequest();
        setScoreFilter(-1);
        unregister();
    }

    private void initNetworkCapabilities() {
        mNetworkCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH);
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        // Bluetooth v3 and v4 go up to 24 Mbps.
        // TODO: Adjust this to actual connection bandwidth.
        mNetworkCapabilities.setLinkUpstreamBandwidthKbps(24 * 1000);
        mNetworkCapabilities.setLinkDownstreamBandwidthKbps(24 * 1000);
    }
}
