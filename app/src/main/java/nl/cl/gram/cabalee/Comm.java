package nl.cl.gram.cabalee;

import com.google.protobuf.ByteString;

public interface Comm {
    String name();
    void sendPayload(ByteString payload);
}
