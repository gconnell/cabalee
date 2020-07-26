package nl.cl.gram.outernet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Strategy;

import java.util.logging.Logger;

public class CommService extends Service {
    private static final Logger logger = Logger.getLogger("outernet.commservice");
    private static final int NOTIFICATION_ID = 1;
    private static final String STOP_SERVICE = "SERVICE_KILL_THYSELF";
    private static final String CHANNEL_ID = "nl.co.gram.outernet.CommServiceChannel";
    private static final String DESCRIPTION = "Outernet";
    private ConnectionsClient connectionsClient = null;
    private CommCenter commCenter = null;
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private static final String SERVICE_ID = "nl.co.gram.outernet";

    public CommService() {
    }

    IBinder iBinder = new Binder();

    @Nullable
    @Override
    public android.os.IBinder onBind(Intent intent) {
        return iBinder;
    }

    class Binder extends android.os.Binder {
        CommCenter commCenter() {
            return CommService.this.commCenter;
        }
    }

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
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Notifications", NotificationManager.IMPORTANCE_LOW);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);
        }
        Intent stopSelf = new Intent(this, getClass());
        stopSelf.setAction(STOP_SERVICE);
        PendingIntent intent = PendingIntent.getService(this, 0, stopSelf, PendingIntent.FLAG_CANCEL_CURRENT);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Outernet")
                .setContentText(DESCRIPTION)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(intent)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        connectionsClient = Nearby.getConnectionsClient(this);
        commCenter = new CommCenter(connectionsClient);
        startAdvertising();
        startDiscovery();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        connectionsClient.stopAllEndpoints();
    }

    private void startDiscovery() {
        DiscoveryOptions discoveryOptions =
                new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        EndpointDiscoveryCallback callback = new EndpointDiscoveryCallback() {
            @Override
            public void onEndpointFound(@NonNull String s, @NonNull DiscoveredEndpointInfo discoveredEndpointInfo) {
                logger.info("onEndpointFound: " + s);
                connectionsClient
                        .requestConnection(Long.toString(commCenter.id()), s, commCenter)
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
                .startAdvertising(Long.toString(commCenter.id()), SERVICE_ID, commCenter, advertisingOptions)
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
