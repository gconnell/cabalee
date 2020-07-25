package nl.cl.gram.outernet;

import java.util.List;

public interface KeyBackingStorage {
    public class BytesPair {
        final byte[] a;
        final byte[] b;

        public BytesPair(byte[] a, byte[] b) {
            this.a = a;
            this.b = b;
        }
    }
    long storeBytesPair(BytesPair b);
    void removeBytesPair(long id);
    BytesPair getBytesPair(long id);
    List<Long> listBytesPairs(boolean withB);
}
