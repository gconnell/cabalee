package nl.cl.gram.camarilla;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import nl.co.gram.camarilla.Payload;

public class Channel implements PayloadReceiver {
    private static final AtomicInteger notificationIdGen = new AtomicInteger(2);
    private final NotificationCompat.Builder builder;
    private boolean shown = false;
    private final int notificationID;
    private int unreadCount = 0;
    private final ReceivingHandler rh;
    private String name = null;
    final NotificationManager notificationManager;
    private final List<Payload> payloads = new ArrayList<>();

    Channel(Context context, ReceivingHandler rh) {
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.rh = rh;
        notificationID = notificationIdGen.addAndGet(1);
        Intent act = new Intent(context, NetworkActivity.class);
        act.putExtra(NetworkActivity.EXTRA_NETWORK_ID, rh.id().toByteArray());
        act.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent intent = PendingIntent.getActivity(context, 0, act, PendingIntent.FLAG_UPDATE_CURRENT);
        builder = new NotificationCompat.Builder(context, CommService.MESSAGE_CHANNEL_ID)
                .setContentTitle(Util.toTitle(rh.id().toByteArray()))
                .setContentText("New messages received")
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentIntent(intent)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setLargeIcon(Util.identicon(rh.id()));
    }

    void cabalShown() {
        notificationManager.cancel(notificationID);
        shown = true;
    }
    void cabalHidden() {
        shown = false;
    }

    @Override
    public synchronized void receivePayload(Payload p) {
        payloads.add(p);
        if (shown) return;
        unreadCount++;
        notificationManager.notify(notificationID, builder.setNumber(unreadCount).build());
    }

    public synchronized List<Payload> payloads() {
        return new ArrayList<>(payloads);
    }
}
