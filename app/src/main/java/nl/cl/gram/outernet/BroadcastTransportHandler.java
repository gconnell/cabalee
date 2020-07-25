package nl.cl.gram.outernet;

import com.google.crypto.tink.KeysetHandle;

import java.security.GeneralSecurityException;

import nl.co.gram.outernet.Transport;

public class BroadcastTransportHandler extends TransportHandler {
    private final CommCenter commCenter;
    public BroadcastTransportHandler(KeysetHandle keysetHandle, CommCenter commCenter) throws GeneralSecurityException {
        super(keysetHandle);
        this.commCenter = commCenter;
    }

    @Override
    protected void handleVerifiedTransport(long from, Transport transport) throws GeneralSecurityException {
        transport = transport.toBuilder()
                .addPath(commCenter.id())
                .build();
        commCenter.sendToAll(transport, transport.getPathList());
    }
}
