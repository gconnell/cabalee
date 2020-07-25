package nl.cl.gram.outernet;

import com.google.crypto.tink.BinaryKeysetReader;
import com.google.crypto.tink.BinaryKeysetWriter;
import com.google.crypto.tink.HybridDecrypt;
import com.google.crypto.tink.HybridEncrypt;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.PublicKeySign;
import com.google.crypto.tink.PublicKeyVerify;
import com.google.crypto.tink.integration.android.AndroidKeystoreAesGcm;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class KmsKeyStorage implements KeyStorage {
    private final KeyBackingStorage kbs;
    private static final String KMS_URI = "android-keystore://nl.co.gram.outernet";
    private final AndroidKeystoreAesGcm androidKeystoreAesGcm;

    KmsKeyStorage(KeyBackingStorage kbs) {
        this.kbs = kbs;
        try {
            androidKeystoreAesGcm = new AndroidKeystoreAesGcm(KMS_URI);
        } catch (Exception e) {
            throw new RuntimeException("creating Android keystore", e);
        }
    }

    @Override
    public long addTransportOnlyNetwork(KeysetHandle kh) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            // Can we actually do what we need to:
            kh.getPrimitive(PublicKeyVerify.class);

            kh.write(BinaryKeysetWriter.withOutputStream(os), androidKeystoreAesGcm);
            return kbs.storeBytesPair(new KeyBackingStorage.BytesPair(os.toByteArray(), null));
        } catch (Exception e) {
            throw new RuntimeException("writing transport only key", e);
        }
    }

    @Override
    public void removeTransportOnlyNetwork(long id) {
        kbs.removeBytesPair(id);
    }

    @Override
    public KeysetHandle getTransportOnlyNetwork(long id) {
        KeyBackingStorage.BytesPair p = kbs.getBytesPair(id);
        if (p.b != null) {
            throw new RuntimeException("got receive network for " + id);
        }
        try {
            KeysetHandle kh = KeysetHandle.read(BinaryKeysetReader.withBytes(p.a), androidKeystoreAesGcm);
            return kh;
        } catch (Exception e) {
            throw new RuntimeException("reading transport only key " + id, e);
        }
    }

    @Override
    public List<Long> transportOnlyNetworks() {
        return kbs.listBytesPairs(false);
    }

    @Override
    public long addReceivingNetwork(Receiver r) {
        ByteArrayOutputStream osA = new ByteArrayOutputStream();
        ByteArrayOutputStream osB = new ByteArrayOutputStream();
        try {
            // Can we actually do what we need to?:
            r.network.getPrimitive(PublicKeyVerify.class);
            r.network.getPrimitive(PublicKeySign.class);
            r.network.getPrimitive(HybridDecrypt.class);
            r.network.getPrimitive(HybridEncrypt.class);
            r.local.getPrimitive(HybridEncrypt.class);
            r.local.getPrimitive(HybridDecrypt.class);
            r.local.getPrimitive(PublicKeySign.class);

            r.network.write(BinaryKeysetWriter.withOutputStream(osA), androidKeystoreAesGcm);
            r.local.write(BinaryKeysetWriter.withOutputStream(osB), androidKeystoreAesGcm);
            return kbs.storeBytesPair(new KeyBackingStorage.BytesPair(osA.toByteArray(), osB.toByteArray()));
        } catch (Exception e) {
            throw new RuntimeException("writing transport only key", e);
        }
    }

    @Override
    public void removeReceivingNetwork(long id) {
        kbs.removeBytesPair(id);
    }

    @Override
    public Receiver getReceivingNetwork(long id) {
        KeyBackingStorage.BytesPair p = kbs.getBytesPair(id);
        if (p.b == null) {
            throw new RuntimeException("got transport network for " + id);
        }
        try {
            KeysetHandle network = KeysetHandle.read(BinaryKeysetReader.withBytes(p.a), androidKeystoreAesGcm);
            KeysetHandle local = KeysetHandle.read(BinaryKeysetReader.withBytes(p.b), androidKeystoreAesGcm);
            return new Receiver(network, local);
        } catch (Exception e) {
            throw new RuntimeException("reading transport only key " + id, e);
        }
    }

    @Override
    public List<Long> receivingNetworks() {
        return kbs.listBytesPairs(true);
    }
}
