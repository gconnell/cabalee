package nl.cl.gram.cabalee;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.logging.Logger;

import nl.co.gram.cabalee.Hello;
import nl.co.gram.cabalee.MsgType;
import nl.co.gram.cabalee.Transport;

public class Comm extends PayloadCallback {
    private static final Logger logger = Logger.getLogger("cabalee.comm");
    private final String remote;
    private State state = State.STARTING;
    private final CommCenter commCenter;
    private long remoteClient = -1;

    public void close() {
    }

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
            case MsgType.TRANSPORT_VALUE: {
                Transport t = Transport.parseFrom(in);
                logger.info("Transport from " + remoteID());
                commCenter.handleTransport(remoteID(), t);
                break;
            }
            default:
                throw new IOException("unsupported type " + bytes[0]);
        }
    }

    private void handlePayload(@NonNull Payload payload) throws IOException {
        switch (payload.getType()) {
            case Payload.Type.BYTES:
                handlePayloadBytes(payload.asBytes());
                break;
            default:
                logger.info("unexpected payload type " + payload.getType() + " ignored");
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
        logger.info("onPayloadTransferUpdate " + s + " = " +
                payloadTransferUpdate.getPayloadId() + ": " + payloadTransferUpdate.getStatus() +
                " (bytes=" + payloadTransferUpdate.getBytesTransferred() + ", total=" + payloadTransferUpdate.getTotalBytes());
    }
}