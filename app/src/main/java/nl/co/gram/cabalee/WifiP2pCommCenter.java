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
// limitations under the License.package nl.cl.gram.cabalee;

package nl.co.gram.cabalee;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class WifiP2pCommCenter {
    private static final int PORT = 22225;
    private static final String DISCOVERY_SERVICE_TYPE = "_cabalee._tcp";
    private static Logger logger = Logger.getLogger("cabalee.wifip2p");
    private final ServerPort serverPort;
    private WifiP2pInfo wifiP2pInfo = null;
    private SocketComm socketComm = null;
    private WifiP2pDnsSdServiceRequest wifiP2pDnsSdServiceRequest = null;
    private WifiP2pManager.Channel wifiChannel = null;
    private final Context context;
    private final CommCenter commCenter;
    private BroadcastReceiver broadcastReceiver = null;
    private Handler handler = null;
    private Socket clientSocket = null;
    private WifiP2pManager wifiP2pManager = null;
    private ServerSocket serverSocket = null;

    private Runnable discoverWifiPeersRunnable = new Runnable() {
        @Override
        public void run() {
            discoverWifiPeers();
        }
    };

    public WifiP2pCommCenter(Context context, CommCenter commCenter, ServerPort serverPort) {
        this.context = context;
        this.commCenter = commCenter;
        this.serverPort = serverPort;
    }

    private static String wifiP2pFailure(int reason) {
        switch (reason) {
            case WifiP2pManager.BUSY:
                return "BUSY";
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "P2P_UNSUPPORTED";
            case WifiP2pManager.ERROR:
                return "ERROR";
        }
        return "UNKNOWN";
    }

    private static WifiP2pManager.ActionListener loggingListener(final String msg) {
        logger.info(msg + " requested");
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                logger.info(msg + " succeeded");
            }

            @Override
            public void onFailure(int reason) {
                logger.warning(msg + " failed: " + wifiP2pFailure(reason));
            }
        };
    }

    private void connectTo(final WifiP2pDevice wifiP2pDevice) {
        logger.info("requesting connect to " + wifiP2pDevice.deviceName);
        Long lastConnectMillis = lastConnectAttemptMillis.get(wifiP2pDevice.deviceAddress);
        Long currentMillis = SystemClock.elapsedRealtime();
        if (lastConnectMillis != null) {
            Long diffMillis = currentMillis - lastConnectMillis;
            if (diffMillis < 60_000) {
                logger.info("Last connect attempt to " + wifiP2pDevice.deviceName + " was " + diffMillis + " ago, skipping");
                return;
            }
        }
        lastConnectAttemptMillis.put(wifiP2pDevice.deviceAddress, currentMillis);
        handler.post(new Runnable() {
            @Override
            public void run() {
                logger.info("attempting connect to " + wifiP2pDevice.deviceName);
                WifiP2pConfig config = new WifiP2pConfig();
                config.deviceAddress = wifiP2pDevice.deviceAddress;
                wifiP2pManager.connect(wifiChannel, config, loggingListener("connect to " + wifiP2pDevice.deviceName + " @ " + wifiP2pDevice.deviceAddress));
            }
        });
    }

    Map<String, Long> lastConnectAttemptMillis = new HashMap<>();

    public void onCreate() {
        logger.severe("-------------------- STARTING ---------------------");

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        broadcastReceiver = receiver();
        context.registerReceiver(broadcastReceiver, intentFilter);
        wifiP2pManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        wifiChannel = wifiP2pManager.initialize(
                context,
                context.getMainLooper(),
                null);
        handler = new Handler(context.getMainLooper());
        logger.info("onCreate complete");
        registerService();
        setUpServiceDiscovery();
        handler.post(discoverWifiPeersRunnable);
    }

    public void onDestroy() {
        handler.removeCallbacks(discoverWifiPeersRunnable);
        context.unregisterReceiver(broadcastReceiver);
        wifiP2pManager.removeGroup(wifiChannel, loggingListener("removeGroup"));
        wifiP2pManager.stopPeerDiscovery(wifiChannel, loggingListener("stopPeerDiscovery"));
        wifiP2pManager.clearServiceRequests(wifiChannel, loggingListener("clearServiceRequests"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            wifiChannel.close();
        }
        try {
            if (serverSocket != null)
                serverSocket.close();
        } catch (Throwable e) {
            logger.severe("Closing server socket: " + e.getMessage());
        }
        logger.info("onDestroy complete");
    }

    private void discoverWifiPeers() {
        wifiP2pManager.discoverServices(wifiChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                logger.info("discovering wifi peers started successfully");
            }

            @Override
            public void onFailure(int reason) {
                logger.info("discovering wifi peers start failed: " + wifiP2pFailure(reason));
                switch (reason) {
                    case WifiP2pManager.BUSY:
                    case WifiP2pManager.ERROR:
                        handler.postDelayed(discoverWifiPeersRunnable, 15_000);
                }
            }
        });
    }

    private BroadcastReceiver receiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                logger.info("Wifi P2P action: " + action);
                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        logger.info("Enabled, discovering peers");
                        handler.post(discoverWifiPeersRunnable);
                    } else {
                        logger.info("Not enabled");
                        handler.removeCallbacks(discoverWifiPeersRunnable);
                    }
                } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    WifiP2pDeviceList wifiP2pDeviceList = (WifiP2pDeviceList) intent
                            .getParcelableExtra(WifiP2pManager.EXTRA_P2P_DEVICE_LIST);
                    logger.info("Got " + wifiP2pDeviceList.getDeviceList().size() + " WifiP2P peers");
                    for (WifiP2pDevice d : wifiP2pDeviceList.getDeviceList()) {
                        logger.info(" - " + d.deviceName);
                        // connectTo(d);
                    }
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    wifiP2pInfo = (WifiP2pInfo) intent
                            .getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                    logger.info("Connection to " + wifiP2pInfo);
                    if (wifiP2pInfo != null && wifiP2pInfo.groupFormed && !wifiP2pInfo.isGroupOwner && (socketComm == null || socketComm.done())) {
                        connectSocketTo(wifiP2pInfo.groupOwnerAddress);
                    }
                } else if (WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1);
                    switch (state) {
                        case WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED:
                            logger.info("Discovery started");
                            break;
                        case WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED:
                            logger.info("Discovery stopped");
                            handler.postDelayed(discoverWifiPeersRunnable, 60_000);
                            break;
                        default:
                            logger.info("Discovery state unknown");
                            break;
                    }
                } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                    WifiP2pDevice wifiP2pDevice = (WifiP2pDevice) intent
                            .getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                    logger.info("This device P2P info: " + wifiP2pDevice.deviceName + " @ " + wifiP2pDevice.deviceAddress);
                }
            }
        };
    }

    private void connectSocketTo(final InetAddress groupOwnerAddress) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                logger.severe("connecting socket to " + groupOwnerAddress);
                try {
                    clientSocket = new Socket(groupOwnerAddress, PORT);
                } catch (IOException e) {
                    logger.severe("Failed to connect to " + groupOwnerAddress + ": " + e.getMessage());
                    return;
                }
                try {
                    logger.severe("Successfully created socket, wrapping in SocketComm");
                    socketComm = new SocketComm(commCenter, clientSocket.getInputStream(), clientSocket.getOutputStream(), "wifip2pClient:" + groupOwnerAddress);
                } catch (Throwable t) {
                    logger.info("while handling socket to " + groupOwnerAddress + ": " + t.getMessage());
                    try {
                        clientSocket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        clientSocket = null;
                    }
                }
            }
        }).start();
    }

    private void registerService() {
        //  Create a string map containing information about your service.
        Map record = new HashMap();
        record.put("available", "visible");
        WifiP2pDnsSdServiceInfo serviceInfo =
                WifiP2pDnsSdServiceInfo.newInstance("cabalee", DISCOVERY_SERVICE_TYPE, record);

        wifiP2pManager.addLocalService(wifiChannel, serviceInfo, loggingListener("addLocalService"));
    }

    private void setUpServiceDiscovery() {
        WifiP2pManager.DnsSdTxtRecordListener txtListener = new WifiP2pManager.DnsSdTxtRecordListener() {
            @Override
            public void onDnsSdTxtRecordAvailable(
                    String fullDomain, Map record, WifiP2pDevice device) {
                logger.info("DnsSdTxtRecord available from " + fullDomain + ": " + record.toString());
                connectTo(device);
            }
        };
        WifiP2pManager.DnsSdServiceResponseListener servListener = new WifiP2pManager.DnsSdServiceResponseListener() {
            @Override
            public void onDnsSdServiceAvailable(String instanceName, String registrationType,
                                                WifiP2pDevice device) {
                logger.info("onDnsSdServiceAvailable: instance=" + instanceName + " reg=" + registrationType);
                if (registrationType.startsWith(DISCOVERY_SERVICE_TYPE)) {
                    connectTo(device);
                }
            }
        };

        wifiP2pManager.setDnsSdResponseListeners(wifiChannel, servListener, txtListener);
        wifiP2pDnsSdServiceRequest = WifiP2pDnsSdServiceRequest.newInstance();
        wifiP2pManager.addServiceRequest(wifiChannel, wifiP2pDnsSdServiceRequest, loggingListener("addServiceRequest"));
    }
}
