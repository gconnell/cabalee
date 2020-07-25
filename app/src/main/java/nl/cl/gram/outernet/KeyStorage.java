package nl.cl.gram.outernet;

import com.google.crypto.tink.KeysetHandle;

import java.util.List;

public interface KeyStorage {
    long addTransportOnlyNetwork(KeysetHandle kh);
    void removeTransportOnlyNetwork(long id);
    KeysetHandle getTransportOnlyNetwork(long id);
    List<Long> transportOnlyNetworks();

    public class Receiver {
        public final KeysetHandle network;
        public final KeysetHandle local;

        public Receiver(KeysetHandle network, KeysetHandle local) {
            this.network = network;
            this.local = local;
        }
    }

    long addReceivingNetwork(Receiver r);
    void removeReceivingNetwork(long id);
    Receiver getReceivingNetwork(long id);
    List<Long> receivingNetworks();
}
