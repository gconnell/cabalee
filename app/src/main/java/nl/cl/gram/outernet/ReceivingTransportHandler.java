package nl.cl.gram.outernet;

import com.google.crypto.tink.BinaryKeysetReader;
import com.google.crypto.tink.BinaryKeysetWriter;
import com.google.crypto.tink.CleartextKeysetHandle;
import com.google.crypto.tink.HybridDecrypt;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.PublicKeyVerify;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.logging.Logger;

import nl.co.gram.outernet.DirectMessage;
import nl.co.gram.outernet.Message;
import nl.co.gram.outernet.MessageContents;
import nl.co.gram.outernet.Payload;
import nl.co.gram.outernet.Transport;

public class ReceivingTransportHandler extends BroadcastTransportHandler {
    private static final Logger logger = Logger.getLogger("outernet.receive");
    private final HybridDecrypt networkDecrypt;
    private final HybridDecrypt localDecrypt;
    private final ByteString me;
    private final MessageCenter messageCenter;
    public ReceivingTransportHandler(
            KeysetHandle networkKeyset,
            CommCenter commCenter,
            KeysetHandle localKeyset,
            MessageCenter messageCenter) throws GeneralSecurityException {
        super(networkKeyset, commCenter);
        networkDecrypt = networkKeyset.getPrimitive(HybridDecrypt.class);
        localDecrypt = localKeyset.getPrimitive(HybridDecrypt.class);
        this.messageCenter = messageCenter;
        ByteArrayOutputStream bs = new ByteArrayOutputStream();
        try {
            CleartextKeysetHandle.write(localKeyset.getPublicKeysetHandle(), BinaryKeysetWriter.withOutputStream(bs));
        } catch (IOException e) {
            throw new RuntimeException("writing public key to bytearrayoutputstream", e);
        }
        me = ByteString.copyFrom(bs.toByteArray());
    }

    @Override
    protected void handleVerifiedTransport(long from, Transport transport) throws GeneralSecurityException {
        byte[] decrypted = networkDecrypt.decrypt(transport.getPayload().toByteArray(), null);
        Payload payload;
        try {
            payload = Payload.parseFrom(decrypted);
        } catch (InvalidProtocolBufferException e) {
            throw new GeneralSecurityException("unable to unmarshal payload", e);
        }
        KeysetHandle senderKeyset;
        try {
            senderKeyset = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(payload.getMessage().toByteArray()));
        } catch (IOException e) {
            throw new GeneralSecurityException("unable to read sender keyset", e);
        }
        PublicKeyVerify senderVerify = senderKeyset.getPrimitive(PublicKeyVerify.class);
        senderVerify.verify(payload.getMessageSignature().toByteArray(), payload.getMessage().toByteArray());
        Message message;
        try {
            message = Message.parseFrom(payload.getMessage());
        } catch (InvalidProtocolBufferException e) {
            throw new GeneralSecurityException("unable to read message", e);
        }
        switch (message.getKindCase()) {
            case BROADCAST: {
                super.handleVerifiedTransport(from, transport);
                handleBroadcast(payload.getSenderId(), message.getBroadcast());
                break;
            }
            case DIRECT_MESSAGE: {
                DirectMessage dm = message.getDirectMessage();
                if (dm.getRecipientId().equals(me)) {
                    handleDirectMessage(payload.getSenderId(), dm.getContents());
                } else {
                    super.handleVerifiedTransport(from, transport);
                }
                break;
            }
            default: {
                logger.severe("unknown message kind, broadcasting without processing");
                super.handleVerifiedTransport(from, transport);
                break;
            }
        }
    }

    private void handleDirectMessage(ByteString senderId, ByteString contents) throws GeneralSecurityException {
        byte[] decrypted = localDecrypt.decrypt(contents.toByteArray(), null);
        MessageContents messageContents;
        try {
            messageContents = MessageContents.parseFrom(decrypted);
        } catch (InvalidProtocolBufferException e) {
            throw new GeneralSecurityException("parsing decripted, verified direct message", e);
        }
        messageCenter.handleDirectMessage(senderId, messageContents);
    }

    private void handleBroadcast(ByteString senderId, MessageContents broadcast) {
        messageCenter.handleBroadcast(senderId, broadcast);
    }
}
