package nl.cl.gram.outernet;

import java.security.GeneralSecurityException;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import nl.co.gram.outernet.Transport;

public class RebroadcastHandler implements TransportHandlerInterface {
    private final Logger logger = Logger.getLogger("outernet.hydhandler");
    private final CommCenter commCenter;

    public RebroadcastHandler(CommCenter commCenter) {
        this.commCenter = commCenter;
    }

    @Override
    public String type() { return "rebroadcast"; }

    @Override
    public void handleTransport(long from, Transport transport) {
        if (!transport.getPathList().contains(from)) {
            // pedantic extra check
            transport = transport.toBuilder().addPath(from).build();
        }
        commCenter.broadcastTransport(transport);
    }
}
