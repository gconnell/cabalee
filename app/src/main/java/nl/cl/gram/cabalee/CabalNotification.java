package nl.cl.gram.cabalee;

import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class CabalNotification {
    private static final Logger logger = Logger.getLogger("cabalee.channel");
    private static final AtomicInteger notificationIdGen = new AtomicInteger(3);
    private final Context context;
    private final NotificationCompat.Builder builder;
    private boolean visibleViaNetworkActivity = false;
    private final int notificationID;
    private int unreadCount = 0;
    private final ReceivingHandler rh;
    private final NotificationManager notificationManager;
    private final BroadcastReceiver broadcastReceiver;
    private final LocalBroadcastManager localBroadcastManager;
    private boolean destruction = false;

    CabalNotification(Context context, ReceivingHandler rh) {
        this.context = context;
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.rh = rh;
        notificationID = notificationIdGen.addAndGet(1);
        Intent act = new Intent(context, NetworkActivity.class);
        act.putExtra(Intents.EXTRA_NETWORK_ID, rh.id().toByteArray());
        act.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent intent = PendingIntent.getActivity(context, 0, act, PendingIntent.FLAG_UPDATE_CURRENT);
        builder = new NotificationCompat.Builder(context, CommService.MESSAGE_CHANNEL_ID)
                .setContentText("New messages received")
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentIntent(intent)
                .setAutoCancel(true)
                .setLargeIcon(Util.identicon(rh.id()));
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                byte[] id = intent.getByteArrayExtra(Intents.EXTRA_NETWORK_ID);
                if (!Arrays.equals(id, rh.id().toByteArray())) {
                    return;
                }
                if (Intents.CABAL_VISIBILITY_CHANGED.equals(action)) {
                    boolean v = intent.getBooleanExtra(Intents.EXTRA_VISIBILITY, false);
                    if (v != visibleViaNetworkActivity) {
                        changeVisibility(v);
                    }
                } else if (Intents.PAYLOAD_RECEIVED.equals(action)) {
                    incrementCount(1);
                } else if (Intents.CABAL_DESTROY_REQUESTED.equals(action)) {
                    destruction = true;
                    incrementCount(0);
                } else if (Intents.CABAL_DESTROY.equals(action)) {
                    notificationManager.cancel(notificationID);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intents.CABAL_VISIBILITY_CHANGED);
        filter.addAction(Intents.PAYLOAD_RECEIVED);
        filter.addAction(Intents.CABAL_DESTROY_REQUESTED);
        filter.addAction(Intents.CABAL_DESTROY);
        localBroadcastManager = LocalBroadcastManager.getInstance(context);
        localBroadcastManager.registerReceiver(broadcastReceiver, filter);
    }

    private void changeVisibility(boolean v) {
        visibleViaNetworkActivity = v;
        if (visibleViaNetworkActivity) {
            unreadCount = 0;
            notificationManager.cancel(notificationID);
        }
    }

    private void incrementCount(int by) {
        if (visibleViaNetworkActivity) return;
        unreadCount += by;
        NotificationCompat.Builder b = builder.setNumber(unreadCount);
        if (destruction) {
            b = b.setColor(context.getResources().getColor(R.color.destroyColor))
                    .setPriority(NotificationManager.IMPORTANCE_HIGH)
                    .setContentTitle("DESTROYING: " + rh.name());
        } else {
            b.setPriority(NotificationManager.IMPORTANCE_DEFAULT).setContentTitle(rh.name());
        }
        notificationManager.notify(notificationID, b.build());
    }
}
