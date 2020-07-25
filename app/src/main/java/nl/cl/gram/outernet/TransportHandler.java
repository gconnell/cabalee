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

    private boolean verifyTransport(Transport msg) {
        try {
            verifier.verify(msg.getPayloadSignature().toByteArray(), msg.getPayload().toByteArray());
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public void handleTransport(long from, Transport transport) {
        if (!verifyTransport(transport)) {
            logger.severe("discarding unverified transport");
            return;
        }
    }

    protected abstract void handleVerifiedTransport(long from, Transport transport) throws GeneralSecurityException;
}
