package nl.cl.gram.outernet;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import nl.co.gram.outernet.Payload;
import nl.co.gram.outernet.Transport;

public class ReceivingHandler implements TransportHandlerInterface {
    private static final Logger logger = Logger.getLogger("outernet.receiver");
    private final Hyd.Network network;
    private final List<Payload> payloads = new ArrayList<>();

    public ReceivingHandler(Hyd.Network network) {
        this.network = network;
    }

    public void handleReceivedTransport(Transport t) {}

    @Override
    public void handleTransport(long from, Transport transport) {
        Payload payload;
        try {
            payload = network.payloadFromTransport(transport, false);
        } catch (GeneralSecurityException e) {
            logger.severe("discarding transport from " + from + ": " + e.getMessage());
            return;
        }
        synchronized (this) {
            payloads.add(payload);
        }
    }
}
