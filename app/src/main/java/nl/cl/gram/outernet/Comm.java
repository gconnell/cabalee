package nl.cl.gram.outernet;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import nl.co.gram.outernet.Hello;
import nl.co.gram.outernet.Hop;
import nl.co.gram.outernet.MsgType;
import nl.co.gram.outernet.RouteToServiceRequest;
import nl.co.gram.outernet.RouteToServiceResponse;

public class Comm extends ConnectionLifecycleCallback {
    private static final Logger logger = Logger.getLogger("outernet.comm"); 
    private final String remote;
    private State state = State.STARTING;
    private final CommCenter commCenter;
    private final ConnectionsClient connectionsClient;
    private long remoteClient = -1;

    public enum State {
        STARTING,
        ACCEPTING,
        CONNECTED,
        IDENTIFIED,
        DISCONNECTED;
    }

    public Comm(CommCenter commCenter, ConnectionsClient connectionsClient, String remote) {
        this.commCenter = commCenter;
        this.connectionsClient = connectionsClient;
        this.remote = remote;
    }

    public State state() {
        return state;
    }
    public String toString() {
        return remote + "=" + remoteClient;
    }
    private void setState(State s) {
        state = s;
        commCenter.recheckState(this);
    }

    public long remote() {
        return remoteClient;
    }

    public void sendProto(@NonNull Object o) {
        logger.info("Sending " + o.getClass() + " to " + this);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            if (o instanceof Hello) {
                buf.write(MsgType.HELLO_VALUE);
                ((Hello) o).writeTo(buf);
            } else if (o instanceof RouteToServiceRequest) {
                buf.write(MsgType.ROUTE_TO_SERVICE_REQUEST_VALUE);
                ((RouteToServiceRequest) o).writeTo(buf);
            } else if (o instanceof RouteToServiceResponse) {
                buf.write(MsgType.ROUTE_TO_SERVICE_RESPONSE_VALUE);
                ((RouteToServiceResponse) o).writeTo(buf);
            } else {
                throw new RuntimeException("unsupported object to send: " + o.getClass());
            }
        } catch (IOException e) {
            throw new RuntimeException("writing to byte array failed", e);
        }
        connectionsClient.sendPayload(remote, Payload.fromBytes(buf.toByteArray()));
    }

    private Hop myHop() {
        return Hop.newBuilder()
                .setId(commCenter.id())
                .setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos())
                .build();
    }

    private void handlePayloadBytes(byte[] bytes) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        switch (in.read()) {
            case MsgType.HELLO_VALUE: {
                Hello h = Hello.parseFrom(in);
                remoteClient = h.getId();
                setState(State.IDENTIFIED);
                break;
            }
            case MsgType.ROUTE_TO_SERVICE_REQUEST_VALUE: {
                RouteToServiceRequest rreq = RouteToServiceRequest.parseFrom(in);
                for (Hop hop : rreq.getOutboundList()) {
                    if (hop.getId() == commCenter.id()) {
                        return;
                    }
                }
                Hop hop = myHop();
                commCenter.sendToAll(remote(), rreq.toBuilder()
                        .addOutbound(hop)
                        .build());
                if (commCenter.hasService(rreq.getService())) {
                    commCenter.sendTo(remote(), RouteToServiceResponse.newBuilder()
                            .setService(rreq.getService())
                            .addAllOutbound(rreq.getOutboundList())
                            .addOutbound(hop)
                            .addInbound(hop)
                            .build());
                }
                break;
            }
            case MsgType.ROUTE_TO_SERVICE_RESPONSE_VALUE: {
                RouteToServiceResponse rresp = RouteToServiceResponse.parseFrom(in);
                int hopIdx = rresp.getOutboundCount() - rresp.getInboundCount() - 1;
                if (hopIdx < 1 || hopIdx >= rresp.getOutboundCount()) {
                    throw new IOException("rresp index out of range: 1 <= " + hopIdx + " < " + rresp.getOutboundCount());
                }
                Hop hop = rresp.getOutbound(hopIdx);
                if (hop.getId() != commCenter.id()) {
                    throw new IOException("rresp got hop mismatch, want " + commCenter.id() + ", got " + hop.getId());
                }
                if (hopIdx == 1) {
                    commCenter.handleRouteResponse(rresp.toBuilder().addInbound(myHop()).build());
                } else {
                    commCenter.sendTo(
                            rresp.getOutbound(hopIdx - 1).getId(),
                            rresp.toBuilder().addInbound(myHop()).build());
                }
                break;
            }
            default:
                throw new IOException("unsupported type " + bytes[0]);
        }
    }

    private void handlePayload(@NonNull Payload payload) throws IOException {
        if (payload.getType() == Payload.Type.BYTES) {
            handlePayloadBytes(payload.asBytes());
        }
    }

    private void disconnect() {
        connectionsClient.disconnectFromEndpoint(remote);
    }

    @Override
    public void onConnectionInitiated(@NonNull String s, @NonNull ConnectionInfo connectionInfo) {
        logger.info("onConnectionInitiated: " + s);
        setState(State.ACCEPTING);
        connectionsClient.acceptConnection(s, new PayloadCallback() {
            @Override
            public void onPayloadReceived(@NonNull String s, @NonNull Payload payload) {
                logger.info("onPayloadReceived from " + s);
                try {
                    handlePayload(payload);
                } catch (IOException e) {
                    logger.severe("handling payload: " + e.getMessage());
                    disconnect();
                }
            }

            @Override
            public void onPayloadTransferUpdate(@NonNull String s, @NonNull PayloadTransferUpdate payloadTransferUpdate) {
                logger.info("onPayloadTransferUpdate " + s);
            }
        });
    }

    @Override
    public void onConnectionResult(@NonNull String s, @NonNull ConnectionResolution connectionResolution) {
        logger.info("onConnectionResult: " + s);
        switch (connectionResolution.getStatus().getStatusCode()) {
            case ConnectionsStatusCodes.STATUS_OK:
                logger.severe("connection " + s + " OK");
                setState(State.CONNECTED);
                Payload p = Payload.fromBytes(s.getBytes(StandardCharsets.UTF_8));
                connectionsClient.sendPayload(s, p);
                break;
            case ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED:
                logger.info("connection " + s + " REJECTED");
                setState(State.DISCONNECTED);
                break;
            case ConnectionsStatusCodes.STATUS_ERROR:
                logger.info("connection " + s + "ERROR");
                setState(State.DISCONNECTED);
                break;
        }
    }

    @Override
    public void onDisconnected(@NonNull String s) {
        logger.info("onDisconnected: " + s);
        setState(State.DISCONNECTED);
    }
}
