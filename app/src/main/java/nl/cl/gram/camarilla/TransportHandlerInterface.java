package nl.cl.gram.camarilla;

import nl.co.gram.camarilla.Transport;

public interface TransportHandlerInterface {
    void handleTransport(long from, Transport transport);
    String type();
}
