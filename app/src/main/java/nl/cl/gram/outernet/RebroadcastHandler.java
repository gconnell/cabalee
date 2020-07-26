package nl.cl.gram.outernet;

import java.security.GeneralSecurityException;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import nl.co.gram.outernet.Transport;

public class RebroadcastHandler implements TransportHandlerInterface {
    private final Logger logger = Logger.getLogger("outernet.hydhandler");
    private final CommCenter commCenter;
    private final Hyd.Network network;

    public RebroadcastHandler(CommCenter commCenter, @Nullable Hyd.Network network) {
        this.commCenter = commCenter;
        this.network = network;
    }

    @Override
    public void handleTransport(long from, Transport transport) {
        try {
            Hyd.Network.verifyTransport(transport);
        } catch (GeneralSecurityException e) {
            logger.severe("unverified transport from " + from + ": dropping");
            return;
        }
        Transport out = transport.toBuilder()
                .addPath(commCenter.id())
                .build();
        commCenter.sendToAll(out, out.getPathList());
    }
}
