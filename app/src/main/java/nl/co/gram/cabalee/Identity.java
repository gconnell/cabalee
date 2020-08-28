package nl.co.gram.cabalee;

import com.google.protobuf.ByteString;
import com.iwebpp.crypto.TweetNaclFast;

public class Identity {
    private final byte[] privateKey;
    private final TweetNaclFast.Signature signature;
    private final PublicKey publicKey;
    public static final int SIGNED = 1;
    public static final int UNSIGNED = 0;
    public static final int LENGTH = TweetNaclFast.Signature.publicKeyLength;

    public Identity() {
        byte[] seed = new byte[TweetNaclFast.Signature.seedLength];
        Util.randomBytes(seed);
        TweetNaclFast.Signature.KeyPair kp = TweetNaclFast.Signature.keyPair_fromSeed(seed);
        privateKey = kp.getSecretKey();
        signature = new TweetNaclFast.Signature(null, privateKey);
        publicKey = new PublicKey(SIGNED, kp.getPublicKey());
    }

    public Identity(byte[] anonymous) {
        privateKey = null;
        signature = null;
        publicKey = new PublicKey(UNSIGNED, anonymous);
    }

    public int signingType() {
        return signature == null ? UNSIGNED : SIGNED;
    }

    public byte[] sign(byte[] data) {
        if (signature != null) {
            return signature.sign(data);
        }
        byte[] out = new byte[data.length + TweetNaclFast.Signature.signatureLength];
        for (int i = 0; i < data.length; i++) {
            out[i] = data[i];
        }
        return out;
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    static class PublicKey {
        private final ByteString publicKey;
        private final TweetNaclFast.Signature signature;

        public static final int SIZE = TweetNaclFast.Signature.publicKeyLength;
        public static final int OVERHEAD = 1 + PublicKey.SIZE + TweetNaclFast.Signature.signatureLength;

        PublicKey(int type, byte[] publicKey) {
            if (publicKey.length != TweetNaclFast.Signature.publicKeyLength) {
                throw new RuntimeException("invalid public key length: " + publicKey.length);
            }
            this.publicKey = ByteString.copyFrom(publicKey);
            if (type == SIGNED) {
                this.signature = new TweetNaclFast.Signature(publicKey, null);
            } else {
                this.signature = null;
            }
        }

        public int signingType() {
            return signature == null ? UNSIGNED : SIGNED;
        }

        public ByteString identity() { return publicKey; }

        public byte[] open(byte[] data) {
            if (signature != null) {
                return signature.open(data);
            }
            if (data.length < TweetNaclFast.Signature.signatureLength) return null;
            byte[] out = new byte[data.length - TweetNaclFast.Signature.signatureLength];
            for (int i = 0; i < data.length - TweetNaclFast.Signature.signatureLength; i++) {
                out[i] = data[i];
            }
            return out;
        }
    }
}
