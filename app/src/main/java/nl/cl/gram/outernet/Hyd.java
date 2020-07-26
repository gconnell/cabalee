package nl.cl.gram.outernet;

import androidx.core.util.Preconditions;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.sun.jna.Native;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import co.libly.hydride.Hydrogen;
import nl.co.gram.outernet.Payload;
import nl.co.gram.outernet.Transport;

// I have yet to find a single doc that says
//   "libhydrogen is {reentrant,concurrency-safe,thread-safe}"
// so I've synchronized all calls on the singleton.  *sigh*
public class Hyd {
    private static final Hyd singleton = new Hyd();
    private final Hydrogen hydrogen;
    private Hyd() {
        Native.register(Hydrogen.class, "hydrogen");
        hydrogen = new Hydrogen();
        hydrogen.hydro_init();
    }
    private static void check(boolean x, String msg) throws GeneralSecurityException {
        if (!x) throw new GeneralSecurityException("hydrogen failure: " + msg);
    }
    public static class Network {
        private final byte[] base;
        private final byte[] psk;
        private static final byte[] derivePskContext = {0,0,0,0,0,0,0,1};
        private static final long pskDerivationId = 1;
        private static final byte[] deriveSignContext = {0,0,0,0,0,0,0,2};
        private static final long signDerivationId = 2;
        private static final byte[] encryptContext = {0,0,0,0,0,0,0,3};
        private static final byte[] signContext = {0,0,0,0,0,0,0,4};
        private final Hydrogen.HydroSignKeyPair sv;
        public Network(byte[] base) throws GeneralSecurityException {
            check(base.length == Hydrogen.HYDRO_KDF_KEYBYTES, "kdf length");
            this.base = base;
            this.psk = new byte[Hydrogen.HYDRO_SECRETBOX_KEYBYTES];
            byte[] svgen = new byte[Hydrogen.HYDRO_SIGN_SEEDBYTES];
            this.sv = new Hydrogen.HydroSignKeyPair();
            synchronized (singleton) {
                check(0 == singleton.hydrogen.hydro_kdf_derive_from_key(psk, psk.length, pskDerivationId, derivePskContext, base), "derive_psk");
                check(0 == singleton.hydrogen.hydro_kdf_derive_from_key(svgen, svgen.length, signDerivationId, deriveSignContext, base), "derive_svgen");
                singleton.hydrogen.hydro_sign_keygen_deterministic(sv, svgen);
            }
        }
        public static byte[] newBase() throws GeneralSecurityException {
            byte[] base = new byte[Hydrogen.HYDRO_KDF_KEYBYTES];
            synchronized (singleton) {
                singleton.hydrogen.hydro_kdf_keygen(base);
            }
            return base;
        }
        byte[] sooperSecret() {
            return base;
        }
        private byte[] encrypt(byte[] cleartext) throws GeneralSecurityException {
            byte[] ciphertext = new byte[cleartext.length + Hydrogen.HYDRO_SECRETBOX_HEADERBYTES];
            synchronized (singleton) {
                check(0 == singleton.hydrogen.hydro_secretbox_encrypt(ciphertext, cleartext, cleartext.length, 0, encryptContext, psk), "secretbox_encrypt");
            }
            return ciphertext;
        }
        private byte[] decrypt(byte[] ciphertext) throws GeneralSecurityException {
            check(ciphertext.length > Hydrogen.HYDRO_SECRETBOX_HEADERBYTES, "encrypted too small");
            byte[] cleartext = new byte[ciphertext.length-Hydrogen.HYDRO_SECRETBOX_HEADERBYTES];
            synchronized (singleton) {
                check(0 == singleton.hydrogen.hydro_secretbox_decrypt(cleartext, ciphertext, ciphertext.length, 0, encryptContext, psk), "secretbox_decrypt");
            }
            return cleartext;
        }
        private byte[] signature(byte[] data) throws GeneralSecurityException {
            byte[] signature = new byte[Hydrogen.HYDRO_SIGN_BYTES];
            synchronized (singleton) {
                check(0 == singleton.hydrogen.hydro_sign_create(signature, data, data.length, signContext, sv.sk), "sign_create");
            }
            return signature;
        }
        private void verify(byte[] data, byte[] signature) throws GeneralSecurityException {
            synchronized (singleton) {
                check(0 == singleton.hydrogen.hydro_sign_verify(signature, data, data.length, signContext, sv.pk), "sign_verify");
            }
        }
        public byte[] id() {
            return sv.pk;
        }
        Transport transportFromPayload(Payload payload) throws GeneralSecurityException {
            byte[] ciphertext = encrypt(payload.toByteArray());
            byte[] signature = signature(ciphertext);
            return Transport.newBuilder()
                    .setNetworkId(ByteString.copyFrom(id()))
                    .setPayload(ByteString.copyFrom(ciphertext))
                    .setPayloadSignature(ByteString.copyFrom(signature))
                    .build();
        }
        public static void verifyTransport(Transport t) throws GeneralSecurityException {
            synchronized (singleton) {
                check(0 == singleton.hydrogen.hydro_sign_verify(
                        t.getPayloadSignature().toByteArray(),
                        t.getPayload().toByteArray(),
                        t.getPayload().size(),
                        signContext,
                        t.getNetworkId().toByteArray()), "sign_verify");
            }
        }
        public Payload payloadFromTransport(Transport t, boolean alreadyVerified) throws GeneralSecurityException {
            Util.checkArgument(Arrays.equals(id(), t.getNetworkId().toByteArray()), "network id mismatch");
            if (!alreadyVerified)
                verifyTransport(t);
            byte[] cleartext = decrypt(t.getPayload().toByteArray());
            try {
                return Payload.parseFrom(cleartext);
            } catch (InvalidProtocolBufferException e) {
                throw new GeneralSecurityException("protobuf parse failure", e);
            }
        }
    }
}
