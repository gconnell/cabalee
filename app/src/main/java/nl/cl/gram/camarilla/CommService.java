package nl.cl.gram.camarilla;

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
import com.google.protobuf.ByteString;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class CommService extends Service {
    private static final Logger logger = Logger.getLogger("camarilla.commservice");
    private static final int NOTIFICATION_ID = 1;
    private static final String STOP_SERVICE = "SERVICE_KILL_THYSELF";
    private static final String CHANNEL_ID = "comm";
    private static final String MESSAGE_CHANNEL_ID = "msgs";
    private ConnectionsClient connectionsClient = null;
    private CommCenter commCenter = null;
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private static final String SERVICE_ID = "nl.co.gram.camarilla";
    private NotificationManager notificationManager = null;
    private static final AtomicInteger notificationIdGen = new AtomicInteger(2);

    public CommService() {
    }

    IBinder iBinder = new Binder();

    @Nullable
    @Override
    public android.os.IBinder onBind(Intent intent) {
        return iBinder;
    }

    public void updateState() {
        notificationManager.notify(NOTIFICATION_ID, notification());
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
    public void onCreate() {
        super.onCreate();

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Camarilla", NotificationManager.IMPORTANCE_LOW));
            notificationManager.createNotificationChannel(new NotificationChannel(MESSAGE_CHANNEL_ID, "Cabals", NotificationManager.IMPORTANCE_DEFAULT));
        }

        startForeground(NOTIFICATION_ID, notification());

        connectionsClient = Nearby.getConnectionsClient(this);
        commCenter = new CommCenter(connectionsClient, this);
        startAdvertising();
        startDiscovery();
    }

    private Notification notification() {
        Intent main = new Intent(this, MainActivity.class);
        main.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent intent = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT);
        String description = "Connections: " + (commCenter == null ? 0 : commCenter.activeComms().size());
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Camarilla")
                .setContentText(description)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentIntent(intent)
                .build();
    }

    private final Map<ByteString, Integer> notificationsForReceivers = new HashMap<>();
    protected synchronized void notificationFor(ReceivingHandler rh) {
        Integer id;
        if (notificationsForReceivers.containsKey(rh.id())) {
            id = notificationsForReceivers.get(rh.id());
            if (id == null) {
                // Explicitly added null means "don't notify for this"
                return;
            }
        } else {
            id = notificationIdGen.addAndGet(1);
            notificationsForReceivers.put(rh.id(), id);
        }
        Intent act = new Intent(this, NetworkActivity.class);
        act.putExtra(NetworkActivity.EXTRA_NETWORK_ID, rh.id().toByteArray());
        act.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent intent = PendingIntent.getActivity(this, 0, act, PendingIntent.FLAG_UPDATE_CURRENT);
        notificationManager.notify(id, new NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
                .setContentTitle(Util.toTitle(rh.id().toByteArray()))
                .setContentText("New message(s) received")
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentIntent(intent)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setLargeIcon(Util.identicon(rh.id()))
                .build());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        logger.severe("Stopping CommService");
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        connectionsClient.stopAllEndpoints();
        notificationManager.cancel(NOTIFICATION_ID);
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
