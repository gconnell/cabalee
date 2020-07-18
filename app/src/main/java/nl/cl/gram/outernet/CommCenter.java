package nl.cl.gram.outernet;

import androidx.annotation.NonNull;
import androidx.core.util.Preconditions;

import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionsClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import nl.co.gram.outernet.Hop;
import nl.co.gram.outernet.RouteToServiceResponse;
import nl.co.gram.outernet.Service;

public class CommCenter {
    private static final Logger logger = Logger.getLogger("outernet.center");
    private final long localID;
    private Map<Long, Comm> comms = new HashMap<>();
    private Set<Comm> starting = new HashSet<>();
    private final ConnectionsClient connectionsClient;

    CommCenter(ConnectionsClient connectionsClient) {
        byte[] bytes = new byte[6];
        Util.randomBytes(bytes);
        long id = 0;
        for (int i = 0; i < 4; i++) {
            id = (id << 8) | ((long) bytes[i]) & 0xFFL;
        }
        localID = id;
        this.connectionsClient = connectionsClient;
    }
    public long id() { return localID; }

    public boolean hasService(Service service) {
        return true;
    }

    public synchronized void sendToAll(long except, @NonNull Object proto) {
        for (Comm c : comms.values()) {
            if (c.remote() != except) {
                c.sendProto(proto);
            }
        }
    }
    public synchronized boolean sendTo(long id, @NonNull Object proto) {
        Comm c = comms.get(id);
        if (c == null) return false;
        c.sendProto(proto);
        return true;
    }

    void handleRouteResponse(RouteToServiceResponse rresp) {
        Util.checkArgument(rresp.getOutboundCount() == rresp.getInboundCount(), "#outbound != #inbound");
        ArrayList<Long> roundTrips = new ArrayList<>(rresp.getInboundCount()+1);
        for (int out = 0; out < rresp.getOutboundCount(); out++) {
            int in = rresp.getInboundCount() - out - 1;
            Hop outH = rresp.getOutbound(out);
            Hop inH = rresp.getInbound(in);
            Util.checkArgument(outH.getId() == inH.getId(), "id mismatch @ %d/%d", out, in);
            roundTrips.add(inH.getElapsedRealtimeNanos() - outH.getElapsedRealtimeNanos());
        }
        for (int i = 0; i < rresp.getOutboundCount() - 1; i++) {
            long latency = roundTrips.get(i) - roundTrips.get(i+1);
            double seconds = ((double) latency) / 1_000_000_000D / 2;
            logger.info(String.format("%d -> %d took %f", rresp.getOutbound(i).getId(), rresp.getOutbound(i+1), seconds));
        }
    }

    public synchronized ConnectionLifecycleCallback connect(String s) {
        Comm c = new Comm(this, connectionsClient, s);
        starting.add(c);
        return c;
    }
    synchronized void recheckState(Comm c) {
        switch (c.state()) {
            case IDENTIFIED:
                starting.remove(c);
                comms.put(c.remote(), c);
            case DISCONNECTED:
                if (comms.get(c.remote()) == c) {
                    comms.remove(c.remote());
                }
                starting.remove(c);
        }
    }
}
