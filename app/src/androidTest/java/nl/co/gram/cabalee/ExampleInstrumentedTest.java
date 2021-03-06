// Copyright 2020 The Cabalī Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package nl.co.gram.cabalee;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.protobuf.ByteString;
import com.iwebpp.crypto.TweetNaclFast;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.KeyStore;
import java.util.Arrays;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

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
    public void testBoxAndUnbox() {
        byte[] key = new byte[TweetNaclFast.SecretBox.keyLength];
        TweetNaclFast.SecretBox box = new TweetNaclFast.SecretBox(key);
        for (int i = 0; i < 1000; i++) {
            logger.info("Run " + i);
            Payload p = Payload.newBuilder()
                    .setCleartextBroadcast(MessageContents.newBuilder()
                            .setText("wheee"))
                    .build();
            Identity ident = new Identity();
            ByteString boxed = Cabal.boxIt(p, box, ident);
            Payload p2 = Cabal.unboxIt(boxed, box);
            assertTrue(p.equals(p2));
            assertNull(Cabal.unboxIt(boxed.substring(1), box));
            assertNull(Cabal.unboxIt(boxed.substring(0, boxed.size() - 1), box));
            int minSize = 127 + 1 + TweetNaclFast.SecretBox.overheadLength + TweetNaclFast.SecretBox.nonceLength;
            if (minSize > boxed.size()) {
                fail("Boxed size " + boxed.size() + " < min size " + minSize);
            }
        }
    }
    @Test
    public void testBoxAndUnboxLarge() {
        byte[] key = new byte[TweetNaclFast.SecretBox.keyLength];
        TweetNaclFast.SecretBox box = new TweetNaclFast.SecretBox(key);
        Payload p = Payload.newBuilder()
                .setCleartextBroadcast(MessageContents.newBuilder()
                        .setText("111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111" +
                                "111111111111111111111111111111111111"))
                .build();
        ByteString boxed = Cabal.boxIt(p, box);
        Payload p2 = Cabal.unboxIt(boxed, box);
        assertTrue(p.equals(p2));
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
    public void testAndroidKeystore() throws Exception {
        byte[] key = new byte[32];
        SecretKeySpec spec = new SecretKeySpec(key, "AES");
        SecretKey k = spec;
        KeyStore.SecretKeyEntry entry = new KeyStore.SecretKeyEntry(k);

        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        keyStore.setEntry("myhappykey", entry, null);

        final KeyStore.SecretKeyEntry secretKeyEntry = (KeyStore.SecretKeyEntry) keyStore
                .getEntry("myhappykey", null);
        SecretKey k2 = secretKeyEntry.getSecretKey();

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, k);
        byte[] cipher = c.doFinal(new byte[]{1, 2, 3, 4});
        Cipher c2 = Cipher.getInstance("AES/GCM/NoPadding");
        c2.init(Cipher.ENCRYPT_MODE, k2);
        byte[] cipher2 = c2.doFinal(new byte[]{1, 2, 3, 4});
        assertFalse(Arrays.equals(cipher, new byte[]{1, 2, 3, 4}));
        assertArrayEquals(cipher, cipher2);
    }
}
