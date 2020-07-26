package nl.cl.gram.outernet;

import android.os.Handler;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.Payload;
import com.google.protobuf.ByteString;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import nl.co.gram.outernet.Hello;
import nl.co.gram.outernet.MsgType;
import nl.co.gram.outernet.Transport;

public class CommCenter extends ConnectionLifecycleCallback {
    private static final Logger logger = Logger.getLogger("outernet.center");
    private final long localID;
    private Map<Long, Comm> commsByID = new HashMap<>();
    private Map<String, Comm> commsByRemote = new HashMap<>();
    private final ConnectionsClient connectionsClient;
    private Map<ByteString, TransportHandlerInterface> messageHandlers = new HashMap<>();

    CommCenter(ConnectionsClient connectionsClient) {
        localID = Util.newRandomID();
        this.connectionsClient = connectionsClient;
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

    private synchronized Comm commFor(String remote) {
        Comm c = commsByRemote.get(remote);
        if (c == null) {
            c = new Comm(this, remote);
            commsByRemote.put(remote, c);
        }
        return c;
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

    public void setReceiver(ReceivingHandler rh) {
        synchronized (this) {
            messageHandlers.put(rh.id(), rh);
        }
    }

    public void handleTransport(long from, Transport t) {
        if (t.getPathList().contains(localID)) {
            logger.info("discarding transport loop with path: " + t.getPathList());
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
