package nl.cl.gram.outernet;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.logging.Logger;

import nl.co.gram.outernet.Hello;
import nl.co.gram.outernet.Hop;
import nl.co.gram.outernet.MsgType;
import nl.co.gram.outernet.RouteToServiceRequest;
import nl.co.gram.outernet.RouteToServiceResponse;

public class Comm extends PayloadCallback {
    private static final Logger logger = Logger.getLogger("outernet.comm"); 
    private final String remote;
    private State state = State.STARTING;
    private final CommCenter commCenter;
    private long remoteClient = -1;

    public enum State {
        STARTING,
        ACCEPTING,
        CONNECTED,
        IDENTIFIED,
        DISCONNECTING,
        DISCONNECTED;
    }

    public Comm(CommCenter commCenter, String remote) {
        this.commCenter = commCenter;
        this.remote = remote;
    }

    public State state() {
        return state;
    }
    public String toString() {
        return remote + "=" + remoteClient;
    }
    void setState(State s) {
        state = s;
        commCenter.recheckState(this);
    }

    public long remoteID() {
        return remoteClient;
    }
    public String remote() {
        return remote;
    }

    public void sendProto(@NonNull Object proto) {
        commCenter.sendProto(proto, remote);
    }

    private void handlePayloadBytes(byte[] bytes) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        switch (in.read()) {
            case MsgType.HELLO_VALUE: {
                Hello h = Hello.parseFrom(in);
                logger.info("Hello from " + remoteID() + ": " + h.getId());
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
                Hop hop = commCenter.myHop();
                commCenter.sendToAll(remoteID(), rreq.toBuilder()
                        .addOutbound(hop)
                        .build());
                if (commCenter.hasService(rreq.getService())) {
                    commCenter.sendTo(remoteID(), RouteToServiceResponse.newBuilder()
                            .setReq(rreq.getReq())
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
                if (hopIdx < 0 || hopIdx >= rresp.getOutboundCount()) {
                    throw new IOException("rresp index out of range: 1 <= " + hopIdx + " < " + rresp.getOutboundCount());
                }
                Hop hop = rresp.getOutbound(hopIdx);
                if (hop.getId() != commCenter.id()) {
                    throw new IOException("rresp got hop mismatch, want " + commCenter.id() + ", got " + hop.getId());
                }
                if (hopIdx == 0) {
                    commCenter.handleRouteResponse(rresp.toBuilder().addInbound(commCenter.myHop()).build());
                } else {
                    commCenter.sendTo(
                            rresp.getOutbound(hopIdx - 1).getId(),
                            rresp.toBuilder().addInbound(commCenter.myHop()).build());
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

    @Override
    public void onPayloadReceived(@NonNull String s, @NonNull Payload payload) {
        logger.info("onPayloadReceived from " + s);
        try {
            handlePayload(payload);
        } catch (IOException e) {
            logger.severe("handling payload: " + e.getMessage());
            setState(State.DISCONNECTING);
        }
    }

    @Override
    public void onPayloadTransferUpdate(@NonNull String s, @NonNull PayloadTransferUpdate payloadTransferUpdate) {
        logger.fine("onPayloadTransferUpdate " + s);
    }
}
