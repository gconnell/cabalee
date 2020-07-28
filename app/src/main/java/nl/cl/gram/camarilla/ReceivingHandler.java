package nl.cl.gram.camarilla;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.iwebpp.crypto.TweetNaclFast;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import nl.co.gram.camarilla.Payload;
import nl.co.gram.camarilla.Transport;

public class ReceivingHandler implements TransportHandlerInterface {
    private static final Logger logger = Logger.getLogger("camarilla.receiver");
    private final List<Payload> payloads = new ArrayList<>();
    private final byte[] key;
    private final TweetNaclFast.SecretBox box;
    private final ByteString id;
    private final CommCenter commCenter;

    @Override
    public String type() { return "receive"; }

    public ReceivingHandler(byte[] key, CommCenter commCenter) {
        Util.checkArgument(key.length == TweetNaclFast.Box.secretKeyLength, "key wrong length");
        this.commCenter = commCenter;
        this.key = key;
        this.box = new TweetNaclFast.SecretBox(key);
        MessageDigest digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("no sha256", e);
        }
        this.id = ByteString.copyFrom(digest.digest(key));
    }

    private static byte[] newKey() {
        byte[] key = new byte[TweetNaclFast.SecretBox.keyLength];
        Util.randomBytes(key);
        return key;
    }

    public static ByteString boxIt(Payload payload, TweetNaclFast.SecretBox box) {
        byte[] nonce = new byte[TweetNaclFast.SecretBox.nonceLength];
        Util.randomBytes(nonce);
        byte[] boxed = box.box(payload.toByteArray(), nonce);
        return ByteString.copyFrom(nonce).concat(ByteString.copyFrom(boxed));
    }
    public static Payload unboxIt(ByteString bytes, TweetNaclFast.SecretBox box) {
        Payload payload;
        if (bytes.size() < TweetNaclFast.SecretBox.nonceLength + TweetNaclFast.SecretBox.overheadLength) {
            logger.severe("payload too short");
            return null;
        }
        ByteString nonce = bytes.substring(0, TweetNaclFast.SecretBox.nonceLength);
        ByteString encrypted = bytes.substring(TweetNaclFast.SecretBox.nonceLength);
        byte[] cleartext = box.open(encrypted.toByteArray(), nonce.toByteArray());
        if (cleartext == null) {
            logger.severe("unable to open box");
            return null;
        }
        try {
            payload = Payload.parseFrom(cleartext);
        } catch (InvalidProtocolBufferException e) {
            logger.severe("discarding transport: " + e.getMessage());
            return null;
        }
        return payload;
    }

    public byte[] sooperSecret() {
        return key;
    }

    public ReceivingHandler(CommCenter commCenter) {
        this(newKey(), commCenter);
    }

    public ByteString id() { return id; }

    public void sendPayload(Payload payload) {
        ByteString boxed = boxIt(payload, box);
        synchronized (this) {
            payloads.add(payload);
        }
        commCenter.broadcastTransport(Transport.newBuilder()
                .setPayload(boxed)
                .setNetworkId(id())
                .build());
    }

    public synchronized List<Payload> payloads() {
        return new ArrayList<>(payloads);
    }

    public synchronized void clearPayloads() {
        payloads.clear();
    }

    @Override
    public void handleTransport(long from, Transport transport) {
        commCenter.broadcastTransport(transport);
        Util.checkArgument(id.equals(transport.getNetworkId()), "network id mismatch");
        Payload payload = unboxIt(transport.getPayload(), box);
        if (payload == null) {
            logger.severe("transport from " + from + " discarded");
            return;
        }
        logger.info("received valid payload from " + from);
        synchronized (this) {
            payloads.add(payload);
        }
        commCenter.messageReceived(this);
    }
}
