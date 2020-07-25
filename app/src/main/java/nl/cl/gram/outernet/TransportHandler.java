package nl.cl.gram.outernet;

import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.PublicKeyVerify;

import java.security.GeneralSecurityException;
import java.util.logging.Logger;

import nl.co.gram.outernet.Transport;

public abstract class TransportHandler {
    private static final Logger logger = Logger.getLogger("outernet.transport");
    private final PublicKeyVerify verifier;

    public TransportHandler(KeysetHandle keysetHandle) throws GeneralSecurityException {
        verifier = keysetHandle.getPrimitive(PublicKeyVerify.class);
    }

    public void handleTransport(long from, Transport transport) {
        try {
            verifier.verify(transport.getPayloadSignature().toByteArray(), transport.getPayload().toByteArray());
            handleVerifiedTransport(from, transport);
        } catch (GeneralSecurityException e) {
            logger.severe("ignoring message with security exception: " + e.getMessage());
        }
    }

    protected abstract void handleVerifiedTransport(long from, Transport transport) throws GeneralSecurityException;
}
