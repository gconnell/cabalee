package nl.cl.gram.cabalee;

import nl.co.gram.cabalee.Transport;

public interface TransportHandlerInterface {
    void handleTransport(long from, Transport transport);
    String type();
}
