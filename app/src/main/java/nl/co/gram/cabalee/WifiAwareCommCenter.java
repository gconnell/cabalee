// Copyright 2020 The Cabalī Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package nl.co.gram.cabalee;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareNetworkInfo;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Build;
import android.os.Handler;

import androidx.annotation.RequiresApi;

import java.net.Inet6Address;
import java.net.Socket;
import java.util.List;
import java.util.logging.Logger;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class WifiAwareCommCenter extends AttachCallback {
    private static final Logger logger = Logger.getLogger("cabalee.wifiaware");
    private final Context context;
    private final CommCenter commCenter;
    private BroadcastReceiver broadcastReceiver = null;
    private WifiAwareManager wifiAwareManager = null;
    private Handler handler = null;
    private WifiAwareSession wifiAwareSession = null;
    private static final String SERVICE_NAME = "nl.co.gram.cabalee.WifiAwareService";
    private ConnectivityManager connectivityManager = null;

    public WifiAwareCommCenter(Context context, CommCenter commCenter) {
        this.context = context;
        this.commCenter = commCenter;
    }

    public void onCreate() {
        logger.info("onCreate");
        handler = new Handler(context.getMainLooper());
        IntentFilter filter =
                new IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                logger.info("Received action: " + action);
                if (WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED.equals(action)) {
                    // TODO: close down any existing sessions
                    if (wifiAwareManager.isAvailable() && wifiAwareSession == null) {
                        logger.severe("wifiAwareManager available");
                        startWifiAware();
                    } else if (!wifiAwareManager.isAvailable() && wifiAwareSession != null) {
                        logger.severe("wifiAwareManager unavailable");
                        shutDownWifiAware();
                    }
                }
            }
        };
        context.registerReceiver(broadcastReceiver, filter);
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        wifiAwareManager = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
        if (wifiAwareManager.isAvailable()) {
            logger.severe("wifi aware initially available");
            startWifiAware();
        } else {
            logger.severe("wifi aware not initially available");
        }
    }

    private void startWifiAware() {
        logger.info("starting");
        wifiAwareManager.attach(this, handler);
    }

    private void shutDownWifiAware() {
        logger.info("shutting down");
        if (wifiAwareSession != null) {
            wifiAwareSession.close();
            wifiAwareSession = null;
        }
    }

    public void onDestroy() {
        logger.info("onDestroy");
        shutDownWifiAware();
    }

    @Override
    public void onAttached(WifiAwareSession session) {
        logger.info("onAttached");
        wifiAwareSession = session;
        PublishConfig publishConfig = new PublishConfig.Builder()
                .setServiceName(SERVICE_NAME)
                .build();

        wifiAwareSession.publish(publishConfig, new DiscoverySessionCallback() {
            private PublishDiscoverySession publishDiscoverySession = null;

            @Override
            public void onPublishStarted(PublishDiscoverySession session) {
                logger.info("onPublishStarted");
                publishDiscoverySession = session;
            }
            @Override
            public void onMessageReceived(PeerHandle peerHandle, byte[] message) {
                logger.info("onMessageReceived (@publisher)");
                connectToPeer(publishDiscoverySession, peerHandle, true);
            }
        }, handler);

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder()
                .setServiceName(SERVICE_NAME)
                .build();

        wifiAwareSession.subscribe(subscribeConfig, new DiscoverySessionCallback() {
            private SubscribeDiscoverySession subscribeDiscoverySession = null;

            @Override
            public void onSubscribeStarted(SubscribeDiscoverySession session) {
                logger.info("onSubscribeStarted");
                subscribeDiscoverySession = session;
            }

            @Override
            public void onServiceDiscovered(PeerHandle peerHandle,
                                            byte[] serviceSpecificInfo, List<byte[]> matchFilter) {
                logger.info("onServiceDiscovered");
                subscribeDiscoverySession.sendMessage(peerHandle, 0, CommCenter.KEEP_ALIVE_MESSAGE.toByteArray());
                connectToPeer(subscribeDiscoverySession, peerHandle, false);
            }
        }, handler);
    }

    @Override
    public void onAttachFailed() {
        logger.info("onAttachFailed");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startWifiAware();
            }
        }, 300_000);
    }

    private void connectToPeer(DiscoverySession session, PeerHandle peerHandle, boolean isPublisher) {
        logger.info("Connecting to peer (publisher=" + isPublisher + "): " + peerHandle);
        NetworkSpecifier networkSpecifier;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiAwareNetworkSpecifier.Builder b = new WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                    .setPskPassphrase("somePassword");
            if (isPublisher) {
                b = b.setPort(22225);
            }
            networkSpecifier = b.build();
        } else {
            networkSpecifier = null;
        }
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(networkSpecifier)
                .build();
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
                logger.info("onAvailable, publisher=" + isPublisher);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities);
                logger.info("onCapabilitiesChanged, publisher=" + isPublisher);
                if (!isPublisher) {
                    logger.severe("Attempting to create wifiAware socket");
                    WifiAwareNetworkInfo peerAwareInfo = (WifiAwareNetworkInfo) networkCapabilities.getTransportInfo();
                    Inet6Address peerIpv6 = peerAwareInfo.getPeerIpv6Addr();
                    int peerPort = peerAwareInfo.getPort();
                    try {
                        Socket socket = network.getSocketFactory().createSocket(peerIpv6, peerPort);
                        new SocketComm(commCenter, socket.getInputStream(), socket.getOutputStream(), "wifiaware:" + peerIpv6.getHostAddress());
                    } catch (Throwable t) {
                        logger.severe("Socket creation failed: " + t.getMessage());
                        t.printStackTrace();
                    }
                }
            }

            @Override
            public void onLost(Network network) {
                super.onLost(network);
                logger.info("onLost");
                connectivityManager.unregisterNetworkCallback(this);
            }

            @Override
            public void onUnavailable() {
                super.onUnavailable();
                logger.info("onUnavailable");
                // This is already done
                // connectivityManager.unregisterNetworkCallback(this);
            }
        };
        connectivityManager.requestNetwork(networkRequest, callback, 10_000);
    }
}
