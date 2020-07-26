package nl.cl.gram.outernet;

import android.content.Context;
import android.util.Base64;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.crypto.tink.HybridDecrypt;
import com.google.crypto.tink.HybridEncrypt;
import com.google.crypto.tink.KeyTemplate;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.PublicKeySign;
import com.google.crypto.tink.PublicKeyVerify;
import com.google.crypto.tink.aead.AeadKeyTemplates;
import com.google.crypto.tink.config.TinkConfig;
import com.google.crypto.tink.hybrid.EciesAeadHkdfPrivateKeyManager;
import com.google.crypto.tink.hybrid.HybridKeyTemplates;
import com.google.crypto.tink.proto.Ed25519;
import com.google.crypto.tink.proto.Ed25519KeyFormat;
import com.google.crypto.tink.proto.Tink;
import com.google.crypto.tink.signature.EcdsaSignKeyManager;
import com.google.crypto.tink.signature.Ed25519PrivateKeyManager;
import com.goterl.lazycode.lazysodium.LazySodiumAndroid;
import com.goterl.lazycode.lazysodium.SodiumAndroid;
import com.iwebpp.crypto.TweetNaclFast;
import com.sun.jna.Native;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.logging.Logger;

import co.libly.hydride.Hydrogen;

import static org.junit.Assert.*;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {
    private static final Logger logger = Logger.getLogger("test");

    @Test
    public void useAppContext() {
        // Context of the app under test.
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("nl.cl.gram.outernet", appContext.getPackageName());
    }

    @Test
    public void testTink() throws Exception {
        TinkConfig.register();
        KeysetHandle networkPrivate = KeysetHandle.generateNew(
                Ed25519PrivateKeyManager.rawEd25519Template());
        KeysetHandle networkPublic = networkPrivate.getPublicKeysetHandle();
    }

    @Test
    public void testNacl() throws Exception {
        byte[] sk = new byte[64];
        sk[1] = 5;
        byte[] pk = new byte[32];
        TweetNaclFast.crypto_sign_keypair(pk, sk, true);
        assertArrayEquals(pk, Arrays.copyOfRange(sk, 32, 64));
        byte[] want = new byte[32];
        want[1] = 5;
        assertArrayEquals(want, Arrays.copyOfRange(sk, 0, 32));
        byte[] tosign = {1, 2, 3, 4};
        byte[] signed = new byte[tosign.length + 64];
        byte[] verified = new byte[signed.length];
        assertEquals(0, TweetNaclFast.crypto_sign(signed, signed.length, tosign, 0, tosign.length, sk));
        assertEquals(0, TweetNaclFast.crypto_sign_open(verified, verified.length, signed, 0, signed.length, pk));
        assertArrayEquals(tosign, Arrays.copyOfRange(verified, 64, verified.length));
        assertArrayEquals(tosign, Arrays.copyOfRange(signed, 64, signed.length));
        assertFalse(Arrays.equals(signed, verified));

        TweetNaclFast.Signature.KeyPair skp = TweetNaclFast.Signature.keyPair_fromSecretKey(sk);
        TweetNaclFast.Signature verifier = new TweetNaclFast.Signature(skp.getPublicKey(), null);
        TweetNaclFast.Signature signer = new TweetNaclFast.Signature(null, skp.getSecretKey());
        byte[] signed2 = signer.sign(tosign);
        signed2[66] = 5;
        byte[] verified2 = verifier.open(signed2);
        assertNotNull(verified2);
        assertArrayEquals(tosign, verified2);
    }

    @Test
    public void testHydrogen() throws Exception {
        Native.register(Hydrogen.class, "hydrogen");
        Hydrogen hydrogen = new Hydrogen();
        hydrogen.hydro_init();
        {
            Hydrogen.HydroKxKeyPair kpA = new Hydrogen.HydroKxKeyPair();
            Hydrogen.HydroKxKeyPair kpB = new Hydrogen.HydroKxKeyPair();
            hydrogen.hydro_kx_keygen(kpA);
            hydrogen.hydro_kx_keygen(kpB);
            byte[] packetAtoB = new byte[Hydrogen.HYDRO_KX_KK_PACKET1BYTES];
            byte[] packetBtoA = new byte[Hydrogen.HYDRO_KX_KK_PACKET2BYTES];
            Hydrogen.HydroKxState state1 = new Hydrogen.HydroKxState();
            Hydrogen.HydroKxSessionKeyPair sessionA = new Hydrogen.HydroKxSessionKeyPair();
            Hydrogen.HydroKxSessionKeyPair sessionB = new Hydrogen.HydroKxSessionKeyPair();
            if (0 != hydrogen.hydro_kx_kk_1(state1, packetAtoB, kpB.pk, kpA))
                throw new Exception("yuk");
            if (0 != hydrogen.hydro_kx_kk_2(sessionB, packetBtoA, packetAtoB, kpA.pk, kpB))
                throw new Exception("yuk");
            if (0 != hydrogen.hydro_kx_kk_3(state1, sessionA, packetBtoA, kpA))
                throw new Exception("yuk");
            assertArrayEquals(sessionA.tx, sessionB.rx);
            assertArrayEquals(sessionA.rx, sessionB.tx);
        }
        {
            assertEquals(Hydrogen.HYDRO_SECRETBOX_KEYBYTES, Hydrogen.HYDRO_SIGN_SEEDBYTES);
            byte[] skey = new byte[Hydrogen.HYDRO_SECRETBOX_KEYBYTES];
            hydrogen.hydro_secretbox_keygen(skey);
            Hydrogen.HydroSignKeyPair kp = new Hydrogen.HydroSignKeyPair();
            hydrogen.hydro_sign_keygen_deterministic(kp, skey);
            byte[] sig = new byte[Hydrogen.HYDRO_SIGN_BYTES];
            byte[] tosign = {1,2,3,4};
            byte[] context = new byte[Hydrogen.HYDRO_SIGN_CONTEXTBYTES];
            assertEquals(0, hydrogen.hydro_sign_create(sig, tosign, tosign.length, context, kp.sk));
            assertEquals(0, hydrogen.hydro_sign_verify(sig, tosign, tosign.length, context, kp.pk));
            assertNotEquals(0, hydrogen.hydro_sign_verify(sig, tosign, tosign.length-1, context, kp.pk));
            tosign[0] = 2;
            assertNotEquals(0, hydrogen.hydro_sign_verify(sig, tosign, tosign.length, context, kp.pk));
        }

    }

    @Test
    public void testNetwork() throws Exception {
        byte[] base = Hyd.Network.newBase();
        Hyd.Network net = new Hyd.Network(base);
        byte[] empty  = new byte[Hydrogen.HYDRO_KDF_KEYBYTES];
        assertFalse(Arrays.equals(empty, base));
    }
}