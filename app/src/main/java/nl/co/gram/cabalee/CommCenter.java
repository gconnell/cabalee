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

import android.content.Intent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.protobuf.ByteString;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class CommCenter {
    private static final Logger logger = Logger.getLogger("cabalee.center");
    private final CommService commService;
    private Map<String, Comm> commsByName = new HashMap<>();
    private Map<ByteString, ReceivingHandler> messageHandlers = new HashMap<>();
    private IDSet recentMessageIDs = new IDSet(60_000_000_000L, 15);
    private final LocalBroadcastManager localBroadcastManager;
    public static final ByteString KEEP_ALIVE_MESSAGE = ByteString.copyFrom(new byte[]{MsgType.KEEPALIVE_MESSAGE_V1_VALUE});

    CommCenter(CommService svc) {
        this.commService = svc;
        localBroadcastManager = LocalBroadcastManager.getInstance(svc);
    }

    public synchronized Collection<Comm> activeComms() {
        return new ArrayList<>(commsByName.values());
    }

    public synchronized void addComm(Comm comm) {
        logger.info("Adding comm: " + comm.name());
        commsByName.put(comm.name(), comm);
        broadcastActive();
    }

    public synchronized void removeComm(Comm comm) {
        logger.info("Removing comm: " + comm.name());
        commsByName.remove(comm.name());
        broadcastActive();
    }

    private void broadcastActive() {
        for (String comm : commsByName.keySet()) {
            logger.info("  - comm: " + comm);
        }
        Intent intent = new Intent(Intents.ACTIVE_CONNECTIONS_CHANGED);
        intent.putExtra(Intents.EXTRA_ACTIVE_CONNECTIONS, commsByName.size());
        localBroadcastManager.sendBroadcast(intent);
    }

    public synchronized void sendToAll(ByteString payload, String except) {
        logger.info("sending to all");
        for (Map.Entry<String, Comm> entry : commsByName.entrySet()) {
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
        if (messageHandlers.containsKey(rh.id())) {
            rh = messageHandlers.get(rh.id());
        } else {
            messageHandlers.put(rh.id(), rh);
        }
        return rh;
    }

    public void handlePayloadBytes(String from, ByteString bs) {
        if (bs.size() < 1) {
           logger.severe("payload is too small");
        }
        switch (bs.byteAt(0)) {
            case MsgType.CABAL_MESSAGE_V1_VALUE: {
                logger.info("Transport");
                handleTransport(from, bs.substring(1));
                break;
            }
            case MsgType.KEEPALIVE_MESSAGE_V1_VALUE: {
                logger.info("Received (ignoring) keepalive");
                break;
            }
            default:
                logger.severe("unsupported type " + bs.byteAt(0));
        }
    }

    private void handleTransport(String remote, ByteString t) {
        if (!broadcastTransport(t, remote)) {
            return;
        }
        Collection<ReceivingHandler> rhs;
        synchronized (this) {
            rhs = new ArrayList<>(messageHandlers.values());
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
