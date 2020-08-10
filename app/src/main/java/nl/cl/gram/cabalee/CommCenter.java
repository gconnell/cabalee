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

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.Payload;
import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import nl.co.gram.cabalee.MsgType;

public class CommCenter extends ConnectionLifecycleCallback {
    private static final Logger logger = Logger.getLogger("cabalee.center");
    private final CommService commService;
    private Map<String, Comm> commsByRemote = new HashMap<>();
    private final ConnectionsClient connectionsClient;
    private Map<ByteString, ReceivingHandler> messageHandlers = new HashMap<>();
    private IDSet recentMessageIDs = new IDSet(60_000_000_000L, 15);
    private final LocalBroadcastManager localBroadcastManager;

    CommCenter(ConnectionsClient connectionsClient, CommService svc) {
        this.connectionsClient = connectionsClient;
        this.commService = svc;
        localBroadcastManager = LocalBroadcastManager.getInstance(svc);
    }

    public synchronized Collection<Comm> activeComms() {
        return new ArrayList<>(commsByRemote.values());
    }

    public synchronized void sendToAll(ByteString payload, String except) {
        logger.info("sending to all");
        for (Map.Entry<String, Comm> entry : commsByRemote.entrySet()) {
            if (except != null && except.equals(entry.getKey())) {
                continue;
            }
            entry.getValue().sendPayload(payload);
        }
    }

    private static final byte[] TRANSPORT_PREFIX = {MsgType.CABAL_MESSAGE_V1_VALUE};
    public boolean broadcastTransport(ByteString t, String from) {
        if (recentMessageIDs.checkAndAdd(Util.transportID(t))) {
            return false;
        }
        ByteString payload = ByteString.copyFrom(TRANSPORT_PREFIX).concat(t);
        sendToAll(payload, from);
        return true;
    }

    private synchronized Comm commFor(String remote) {
        Comm c = commsByRemote.get(remote);
        if (c == null) {
            c = new Comm(this, remote);
            commsByRemote.put(remote, c);
        }
        return c;
    }

    synchronized public List<ReceivingHandler> receivers() {
        return new ArrayList<>(messageHandlers.values());
    }

    synchronized public ReceivingHandler receiver(ByteString id) {
        return messageHandlers.get(id);
    }

    synchronized public ReceivingHandler forKey(byte[] key) {
        ReceivingHandler rh;
        if (key == null) {
            rh = new ReceivingHandler(this, commService);
        } else {
            rh = new ReceivingHandler(key, this, commService);
        }
        if (messageHandlers.containsKey(rh.id()) && messageHandlers.get(rh.id()) instanceof ReceivingHandler) {
            rh = (ReceivingHandler) messageHandlers.get(rh.id());
        } else {
            messageHandlers.put(rh.id(), rh);
        }
        return rh;
    }

    synchronized void recheckState(Comm c) {
        switch (c.state()) {
            case CONNECTED:
                break;
            case DISCONNECTING:
                disconnect(c.remote());
                break;
            case DISCONNECTED:
                commsByRemote.remove(c.remote());
                c.close();
                break;
        }
        Intent intent = new Intent(Intents.ACTIVE_CONNECTIONS_CHANGED);
        intent.putExtra(Intents.EXTRA_ACTIVE_CONNECTIONS, commsByRemote.size());
        localBroadcastManager.sendBroadcast(intent);
    }

    private synchronized void disconnect(String remote) {
        connectionsClient.disconnectFromEndpoint(remote);
    }

    void sendPayload(ByteString bs, String remote) {
        logger.info("Sending to " + this);
        connectionsClient.sendPayload(remote, Payload.fromBytes(bs.toByteArray()));
    }

    @Override
    public void onConnectionInitiated(@NonNull String s, @NonNull ConnectionInfo connectionInfo) {
        logger.info("onConnectionInitiated: " + s);
        Comm c = commFor(s);
        connectionsClient.acceptConnection(s, c);
        c.setState(Comm.State.ACCEPTING);
    }

    @Override
    public void onConnectionResult(@NonNull String s, @NonNull ConnectionResolution connectionResolution) {
        logger.info("onConnectionResult: " + s);
        Comm c = commFor(s);
        switch (connectionResolution.getStatus().getStatusCode()) {
            case ConnectionsStatusCodes.STATUS_OK:
                logger.severe("connection " + s + " OK");
                c.setState(Comm.State.CONNECTED);
                break;
            case ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED:
                logger.info("connection " + s + " REJECTED");
                c.setState(Comm.State.DISCONNECTED);
                break;
            case ConnectionsStatusCodes.STATUS_ERROR:
                logger.info("connection " + s + "ERROR");
                c.setState(Comm.State.DISCONNECTED);
                break;
        }
    }

    @Override
    public void onDisconnected(@NonNull String s) {
        logger.info("onDisconnected: " + s);
        commFor(s).setState(Comm.State.DISCONNECTED);
    }

    public void handleTransport(String remote, ByteString t) {
        if (!broadcastTransport(t, remote)) {
            return;
        }
        Collection<ReceivingHandler> rhs;
        synchronized (this) {
            rhs = new ArrayList<ReceivingHandler>(messageHandlers.values());
        }
        for (ReceivingHandler rh : rhs) {
            if (rh.handleTransport(t)) {
                break;
            }
        }
    }

    public void onTrimMemory() {
        recentMessageIDs.trimMemory();
    }

    public synchronized void destroyCabal(byte[] id) {
        messageHandlers.remove(ByteString.copyFrom(id));
    }
}
