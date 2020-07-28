package nl.cl.gram.camarilla;

import com.google.protobuf.ByteString;

import nl.co.gram.camarilla.MessageContents;

public interface MessageCenter {
    void handleBroadcast(ByteString sender, MessageContents contents);
    void handleDirectMessage(ByteString sender, MessageContents contents);
}
