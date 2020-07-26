package nl.cl.gram.outernet;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.iwebpp.crypto.TweetNaclFast;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import nl.co.gram.outernet.Payload;
import nl.co.gram.outernet.Transport;

public class ReceivingHandler implements TransportHandlerInterface {
    private static final Logger logger = Logger.getLogger("outernet.receiver");
    private final List<Payload> payloads = new ArrayList<>();
    private final byte[] key;
    private final TweetNaclFast.SecretBox box;
    private final ByteString id;

    @Override
    public String type() { return "receive"; }

    public ReceivingHandler(byte[] key) {
        Util.checkArgument(key.length == TweetNaclFast.Box.secretKeyLength, "key wrong length");
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

    public byte[] sooperSecret() {
        return key;
    }

    public ReceivingHandler() {
        this(newKey());
    }

    public ByteString id() { return id; }

    @Override
    public void handleTransport(long from, Transport transport) {
        Util.checkArgument(id.equals(transport.getNetworkId()), "network id mismatch");
        Payload payload;
        try {
            payload = Payload.parseFrom(box.open(transport.getPayload().toByteArray()));
        } catch (InvalidProtocolBufferException e) {
            logger.severe("discarding transport from " + from + ": " + e.getMessage());
            return;
        }
        synchronized (this) {
            payloads.add(payload);
        }
    }
}
