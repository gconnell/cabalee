package nl.cl.gram.outernet;

import java.util.logging.Logger;

import nl.co.gram.outernet.Transport;

public class DropHandler implements TransportHandlerInterface {
    private static final Logger logger = Logger.getLogger("outernet.drop");
    @Override
    public void handleTransport(long from, Transport transport) {
        logger.info("dropping transport from " + from);
    }
}
