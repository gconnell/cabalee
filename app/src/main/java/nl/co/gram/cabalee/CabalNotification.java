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
    private final Cabal rh;
    private final NotificationManager notificationManager;
    private final BroadcastReceiver broadcastReceiver;
    private final LocalBroadcastManager localBroadcastManager;
    private boolean destruction = false;

    CabalNotification(Context context, Cabal rh) {
        this.context = context;
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.rh = rh;
        notificationID = notificationIdGen.addAndGet(1);
        Intent act = new Intent(context, CabalActivity.class);
        act.putExtra(Intents.EXTRA_NETWORK_ID, rh.id().toByteArray());
        act.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent intent = PendingIntent.getActivity(context, 0, act, PendingIntent.FLAG_UPDATE_CURRENT);
        builder = new NotificationCompat.Builder(context, CommService.MESSAGE_CHANNEL_ID)
                .setContentText("New messages received")
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentIntent(intent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setLargeIcon(Util.identicon(Util.IDENTICON_CABAL, rh.id()));
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
                    changeVisibility(v);
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
        NotificationCompat.Builder b = builder
                .setContentTitle(rh.name())
                .setNumber(unreadCount);
        if (destruction) {
            b = b.setColor(context.getResources().getColor(R.color.destroyColor))
                    .setColorized(true)
                    .setSmallIcon(R.drawable.ic_baseline_whatshot_24)
                    .setSubText(context.getResources().getString(R.string.self_destruct))
                    .setPriority(NotificationManager.IMPORTANCE_HIGH);
        } else {
            b.setPriority(NotificationManager.IMPORTANCE_DEFAULT);
        }
        notificationManager.notify(notificationID, b.build());
    }
}
