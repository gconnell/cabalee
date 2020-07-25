package nl.cl.gram.outernet;

import com.google.protobuf.ByteString;

import nl.co.gram.outernet.MessageContents;

public interface MessageCenter {
    void handleBroadcast(ByteString sender, MessageContents contents);
    void handleDirectMessage(ByteString sender, MessageContents contents);
}
