package nl.co.gram.cabalee;

import com.google.protobuf.ByteString;

public interface Comm {
    String name();
    void sendPayload(ByteString payload);
}
