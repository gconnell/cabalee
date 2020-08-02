package nl.cl.gram.cabalee;

import com.google.protobuf.ByteString;

import nl.co.gram.cabalee.MessageContents;

public interface MessageCenter {
    void handleBroadcast(ByteString sender, MessageContents contents);
    void handleDirectMessage(ByteString sender, MessageContents contents);
}
