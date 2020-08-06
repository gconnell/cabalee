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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import nl.co.gram.cabalee.Hello;
import nl.co.gram.cabalee.MsgType;
import nl.co.gram.cabalee.Transport;

public class CommCenter extends ConnectionLifecycleCallback {
    private static final Logger logger = Logger.getLogger("cabalee.center");
    private final long localID;
    private final CommService commService;
    private Map<Long, Comm> commsByID = new HashMap<>();
    private Map<String, Comm> commsByRemote = new HashMap<>();
    private final ConnectionsClient connectionsClient;
    private Map<ByteString, TransportHandlerInterface> messageHandlers = new HashMap<>();
    private LinkedList<Set<ByteString>> recentUniqueMessages = new LinkedList<>();
    private final static int PER_SET_RECENT = 16 * 1024;
    private final static int NUM_SETS_RECENT = 8;
    private final LocalBroadcastManager localBroadcastManager;

    CommCenter(ConnectionsClient connectionsClient, CommService svc) {
        localID = Util.newRandomID();
        this.connectionsClient = connectionsClient;
        this.commService = svc;
        localBroadcastManager = LocalBroadcastManager.getInstance(svc);
    }
    public long id() { return localID; }

    public synchronized Collection<Comm> activeComms() {
        return new ArrayList<>(commsByID.values());
    }

    public synchronized void sendToAll(@NonNull Object proto, Collection<Long> except) {
        if (commsByID.isEmpty()) return;
        logger.info("sending to all: " + proto.getClass());
        for (Comm c : commsByID.values()) {
            if (!except.contains(c.remoteID())) {
                c.sendProto(proto);
            }
        }
    }

    public void broadcastTransport(Transport t) {
        Transport out = t.toBuilder()
                .addPath(id())
                .build();
        sendToAll(out, out.getPathList());
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
        List<ReceivingHandler> out = new ArrayList<>();
        for (TransportHandlerInterface thi : messageHandlers.values()) {
            if (thi instanceof ReceivingHandler) {
                out.add((ReceivingHandler) thi);
            }
        }
        return out;
    }

    synchronized public ReceivingHandler receiver(ByteString id) {
        TransportHandlerInterface handler = messageHandlers.get(id);
        if (handler != null && handler instanceof ReceivingHandler) {
            return (ReceivingHandler) handler;
        }
        return null;
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
                c.sendProto(Hello.newBuilder().setId(id()).build());
                break;
            case IDENTIFIED:
                commsByID.put(c.remoteID(), c);
                break;
            case DISCONNECTING:
                disconnect(c.remote());
                break;
            case DISCONNECTED:
                commsByID.remove(c.remoteID());
                commsByRemote.remove(c.remote());
                c.close();
                break;
        }
        Intent intent = new Intent(Intents.ACTIVE_CONNECTIONS_CHANGED);
        intent.putExtra(Intents.EXTRA_ACTIVE_CONNECTIONS, commsByID.size());
        localBroadcastManager.sendBroadcast(intent);
    }

    private synchronized void disconnect(String remote) {
        connectionsClient.disconnectFromEndpoint(remote);
    }

    void sendProto(@NonNull Object o, String remote) {
        logger.info("Sending " + o.getClass() + " to " + this);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            if (o instanceof Hello) {
                buf.write(MsgType.HELLO_VALUE);
                ((Hello) o).writeTo(buf);
            } else if (o instanceof Transport) {
                buf.write(MsgType.TRANSPORT_VALUE);
                ((Transport) o).writeTo(buf);
            } else {
                throw new RuntimeException("unsupported object to send: " + o.getClass());
            }
        } catch (IOException e) {
            throw new RuntimeException("writing to byte array failed", e);
        }
        connectionsClient.sendPayload(remote, Payload.fromBytes(buf.toByteArray()));
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

    private boolean discardTransport(Transport t) {
        if (t.getNetworkId().size() != 32) {
            logger.severe("invaild transport, network ID wrong size");
            return true;
        }
        if (t.getPathList().contains(localID)) {
            logger.info("discarding transport loop with path: " + t.getPathList());
        }
        MessageDigest d;
        try {
            d = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("no sha256");
        }
        d.update((byte) t.getNetworkId().size());
        d.update(t.getNetworkId().asReadOnlyByteBuffer());
        d.update(t.getPayload().asReadOnlyByteBuffer());
        ByteString uid = ByteString.copyFrom(d.digest());
        // we do some mild trickiness to get constant-time checks for uniqueness while also
        // aging out older IDs, by having a fixed number of constant-time-lookup sets,
        // in a linked list which allows constant time removal and addition of sets.
        synchronized (this) {
            if (recentUniqueMessages.isEmpty()) {
                recentUniqueMessages.add(new HashSet<>());
            }
            Set<ByteString> latest = null;
            for (Set<ByteString> ids : recentUniqueMessages) {
                if (ids.contains(uid)) {
                    logger.info("discarding duplicate message ID");
                    return true;
                }
                latest = ids;
            }
            if (latest.size() >= PER_SET_RECENT) {
                latest = new HashSet<>();
                recentUniqueMessages.add(latest);
                if (recentUniqueMessages.size() > NUM_SETS_RECENT) {
                    recentUniqueMessages.removeFirst();
                }
            }
            latest.add(uid);
            return false;
        }
    }

    public void handleTransport(long from, Transport t) {
        if (discardTransport(t)) {
            return;
        }
        TransportHandlerInterface h;
        synchronized (this) {
            h = messageHandlers.get(t.getNetworkId());
            if (h == null) {
                h = new RebroadcastHandler(this);
                messageHandlers.put(t.getNetworkId(), h);
            }
        }
        logger.info("handling network " + Util.toHex(t.getNetworkId().toByteArray()) + " with: " + h.type());
        h.handleTransport(from, t);
    }
}
