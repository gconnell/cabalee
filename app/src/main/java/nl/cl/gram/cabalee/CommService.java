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

package nl.cl.gram.cabalee;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Strategy;

import java.util.Base64;
import java.util.logging.Logger;

public class CommService extends Service {
    private static final Logger logger = Logger.getLogger("cabalee.commservice");
    private static final int NOTIFICATION_ID = 1;
    private static final String STOP_SERVICE = "SERVICE_KILL_THYSELF";
    private static final String CHANNEL_ID = "comm";
    public static final String MESSAGE_CHANNEL_ID = "msgs";
    private static final long KEEP_ALIVE_MILLIS = 75 * 1_000;
    private ConnectionsClient connectionsClient = null;
    private CommCenter commCenter = null;
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private static final String SERVICE_ID = "nl.co.gram.cabalee";
    private NotificationManager notificationManager = null;
    private LocalBroadcastManager localBroadcastManager = null;
    private IntentFilter intentFilter = null;
    private BroadcastReceiver broadcastReceiver = null;
    private Handler handler = null;

    IBinder iBinder = new Binder();

    @Nullable
    @Override
    public android.os.IBinder onBind(Intent intent) {
        return iBinder;
    }

    class Binder extends android.os.Binder {
        CommService svc() {
            return CommService.this;
        }
    }

    public CommCenter commCenter() { return commCenter; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (STOP_SERVICE.equals(intent.getAction())) {
            logger.severe("stopping self");
            stopForeground(true);
            return Service.START_NOT_STICKY;
        }
        return Service.START_STICKY;
    }

    @Override
    public void onTrimMemory(int level) {
        commCenter.onTrimMemory();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(getMainLooper());

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW));
            notificationManager.createNotificationChannel(new NotificationChannel(MESSAGE_CHANNEL_ID, "Cabals", NotificationManager.IMPORTANCE_DEFAULT));
        }

        startForeground(NOTIFICATION_ID, notification(0));
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        intentFilter = new IntentFilter();
        intentFilter.addAction(Intents.ACTIVE_CONNECTIONS_CHANGED);
        intentFilter.addAction(Intents.CABAL_DESTROY_REQUESTED);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intents.ACTIVE_CONNECTIONS_CHANGED.equals(action)) {
                    int num = intent.getIntExtra(Intents.EXTRA_ACTIVE_CONNECTIONS, 0);
                    notificationManager.notify(NOTIFICATION_ID, notification(num));
                } else if (Intents.CABAL_DESTROY_REQUESTED.equals(action)) {
                    final byte[] id = intent.getByteArrayExtra(Intents.EXTRA_NETWORK_ID);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            logger.warning("Destroying cabal");
                            commCenter.destroyCabal(id);
                            Intent intent = new Intent(Intents.CABAL_DESTROY);
                            intent.putExtra(Intents.EXTRA_NETWORK_ID, id);
                            localBroadcastManager.sendBroadcast(intent);
                        }
                    }, 60_000);
                }
            }
        };
        localBroadcastManager.registerReceiver(broadcastReceiver, intentFilter);
        connectionsClient = Nearby.getConnectionsClient(this);
        commCenter = new CommCenter(connectionsClient, this);
        startAdvertising();
        startDiscovery();
    }

    private Notification notification(int activeComms) {
        logger.severe("comms notification: " + activeComms);
        String description = activeComms == 0 ? "Waiting from connections" : "Active connections: " + activeComms;
        Intent main = new Intent(this, MainActivity.class);
        main.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent intent = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT);
        int icon;
        switch (activeComms) {
            case 0:
                icon = R.drawable.ic_stat_name_0;
                break;
            case 1:
                icon = R.drawable.ic_stat_name_1;
                break;
            case 2:
                icon = R.drawable.ic_stat_name_2;
                break;
            case 3:
                icon = R.drawable.ic_stat_name_3;
                break;
            default:
                icon = R.drawable.ic_stat_name_4;
                break;
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(description)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(icon)
                .setContentIntent(intent)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        logger.severe("Stopping CommService");
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        connectionsClient.stopAllEndpoints();
        notificationManager.cancel(NOTIFICATION_ID);
        localBroadcastManager.unregisterReceiver(broadcastReceiver);
    }

    private void startDiscovery() {
        DiscoveryOptions discoveryOptions =
                new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        EndpointDiscoveryCallback callback = new EndpointDiscoveryCallback() {
            @Override
            public void onEndpointFound(@NonNull String s, @NonNull DiscoveredEndpointInfo discoveredEndpointInfo) {
                logger.info("onEndpointFound: " + s);
                connectionsClient
                        .requestConnection("cabalee", s, commCenter)
                        .addOnSuccessListener(
                                (Void unused) -> {
                                    logger.info("requesting connection to " + s + " succeeded");
                                })
                        .addOnFailureListener(
                                (Exception e) -> {
                                    logger.info("requesting connection to " + s + " failed: " + e.getMessage());
                                });
            }

            @Override
            public void onEndpointLost(@NonNull String s) {
                logger.info("onEndpointLost: " + s);
            }
        };
        connectionsClient
                .startDiscovery(SERVICE_ID, callback, discoveryOptions)
                .addOnSuccessListener(
                        (Void unused) -> {
                            logger.info("Discovering started");
                        })
                .addOnFailureListener(
                        (Exception e) -> {
                            logger.severe("Discovering failed: " + e.getMessage());
                            e.printStackTrace();
                        });
    }

    private void startAdvertising() {
        AdvertisingOptions advertisingOptions =
                new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        connectionsClient
                .startAdvertising("cabalee", SERVICE_ID, commCenter, advertisingOptions)
                .addOnSuccessListener(
                        (Void unused) -> {
                            logger.info("Advertizing started");
                        })
                .addOnFailureListener(
                        (Exception e) -> {
                            logger.severe("Advertizing failed: " + e.getMessage());
                            e.printStackTrace();
                        });
    }
}
