package nl.cl.gram.outernet;

import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.Payload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import nl.co.gram.outernet.Hello;
import nl.co.gram.outernet.Hop;
import nl.co.gram.outernet.MsgType;
import nl.co.gram.outernet.RouteToServiceRequest;
import nl.co.gram.outernet.RouteToServiceResponse;
import nl.co.gram.outernet.Service;

public class CommCenter extends ConnectionLifecycleCallback {
    private static final Logger logger = Logger.getLogger("outernet.center");
    private final long localID;
    private Map<Long, Comm> commsByID = new HashMap<>();
    private Map<String, Comm> commsByRemote = new HashMap<>();
    private final ConnectionsClient connectionsClient;
    private Handler handler = new Handler();
    private AtomicInteger requestIDs = new AtomicInteger(0);
    private Map<Integer, Stream<RouteToServiceResponse>> routeResponses = new ConcurrentHashMap<>();

    CommCenter(ConnectionsClient connectionsClient) {
        localID = Util.newRandomID();
        this.connectionsClient = connectionsClient;
        handler.postDelayed(getRoutes(), 5_000);
    }
    public long id() { return localID; }

    public synchronized Collection<Comm> activeComms() {
        return new ArrayList<>(commsByID.values());
    }

    Hop myHop() {
        return Hop.newBuilder()
                .setId(id())
                .setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos())
                .build();
    }

    public void fastestRouteTo(Service service, Stream<RouteToServiceResponse> s) {
        final int req = requestIDs.addAndGet(1);
        RouteToServiceRequest rr = RouteToServiceRequest.newBuilder()
                .setReq(req)
                .setService(service)
                .addOutbound(myHop())
                .build();
        sendToAll(-1, rr);
        routeResponses.put(req, s);
        // TODO: remove this post more efficiently when/if things succeed.
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                routeResponses.remove(req);
                s.onComplete();
            }
        }, 13_000);
    }

    private Runnable getRoutes() {
        return new Runnable() {
            @Override
            public void run() {
                synchronized (CommCenter.this) {
                    for (String s : commsByRemote.keySet()) {
                        Comm c = commsByRemote.get(s);
                        logger.info("have remote conn for: " + s + " id=" + c.remoteID());
                    }
                    for (Long l : commsByID.keySet()) {
                        logger.info("have remote conn for id: " + l);
                    }
                }
                handler.postDelayed(this, 15_000);
            }
        };
    }

    boolean anyServices = true;

    public void turnOffServices() {
        anyServices = false;
    }

    public boolean hasService(Service service) {
        return anyServices;
    }

    public synchronized void sendToAll(long except, @NonNull Object proto) {
        if (commsByID.isEmpty()) return;
        logger.info("sending to all: " + proto.getClass());
        for (Comm c : commsByID.values()) {
            if (c.remoteID() != except) {
                c.sendProto(proto);
            }
        }
    }
    public synchronized boolean sendTo(long id, @NonNull Object proto) {
        logger.info("sending to " + id + ": " + proto.getClass());
        Comm c = commsByID.get(id);
        if (c == null) return false;
        c.sendProto(proto);
        return true;
    }

    private synchronized Comm commFor(String remote) {
        Comm c = commsByRemote.get(remote);
        if (c == null) {
            c = new Comm(this, remote);
            commsByRemote.put(remote, c);
        }
        return c;
    }

    void handleRouteResponse(RouteToServiceResponse rresp) {
        Util.checkArgument(rresp.getOutboundCount() == rresp.getInboundCount(), "#outbound != #inbound");
        ArrayList<Long> roundTrips = new ArrayList<>(rresp.getInboundCount()+1);
        long totalLatency = -1;
        for (int out = 0; out < rresp.getOutboundCount(); out++) {
            int in = rresp.getInboundCount() - out - 1;
            Hop outH = rresp.getOutbound(out);
            Hop inH = rresp.getInbound(in);
            Util.checkArgument(outH.getId() == inH.getId(), "id mismatch @ %d/%d", out, in);
            roundTrips.add(inH.getElapsedRealtimeNanos() - outH.getElapsedRealtimeNanos());
            if (out == 0) {
                totalLatency = inH.getElapsedRealtimeNanos() - outH.getElapsedRealtimeNanos();
            }
        }
        logger.severe("RResp total latency: " + nanosAsSeconds(totalLatency));
        for (int i = 0; i < rresp.getOutboundCount() - 1; i++) {
            long latency = roundTrips.get(i) - roundTrips.get(i+1);
            double seconds = nanosAsSeconds(latency) / 2;
            logger.info(String.format("%d -> %d took %f", rresp.getOutbound(i).getId(), rresp.getOutbound(i+1).getId(), seconds));
        }
        Stream<RouteToServiceResponse> resp = routeResponses.get(rresp.getReq());
        if (resp != null) {
            resp.onNext(rresp);
        }
    }

    double nanosAsSeconds(long nanos) {
        return ((double) nanos) / 1_000_000_000D;
    }

    synchronized void recheckState(Comm c) {
        switch (c.state()) {
            case CONNECTED:
                c.sendProto(Hello.newBuilder().setId(id()).build());
                break;
            case IDENTIFIED:
                commsByID.put(c.remoteID(), c);
                break;
            case DISCONNECTING:
                disconnect(c.remote());
                break;
            case DISCONNECTED:
                commsByID.remove(c.remoteID());
                commsByRemote.remove(c.remote());
                break;
        }
    }

    private synchronized void disconnect(String remote) {
        connectionsClient.disconnectFromEndpoint(remote);
    }

    void sendProto(@NonNull Object o, String remote) {
        logger.info("Sending " + o.getClass() + " to " + this);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            if (o instanceof Hello) {
                buf.write(MsgType.HELLO_VALUE);
                ((Hello) o).writeTo(buf);
            } else if (o instanceof RouteToServiceRequest) {
                buf.write(MsgType.ROUTE_TO_SERVICE_REQUEST_VALUE);
                ((RouteToServiceRequest) o).writeTo(buf);
            } else if (o instanceof RouteToServiceResponse) {
                buf.write(MsgType.ROUTE_TO_SERVICE_RESPONSE_VALUE);
                ((RouteToServiceResponse) o).writeTo(buf);
            } else {
                throw new RuntimeException("unsupported object to send: " + o.getClass());
            }
        } catch (IOException e) {
            throw new RuntimeException("writing to byte array failed", e);
        }
        connectionsClient.sendPayload(remote, Payload.fromBytes(buf.toByteArray()));
    }

    @Override
    public void onConnectionInitiated(@NonNull String s, @NonNull ConnectionInfo connectionInfo) {
        logger.info("onConnectionInitiated: " + s);
        Comm c = commFor(s);
        connectionsClient.acceptConnection(s, c);
        c.setState(Comm.State.ACCEPTING);
    }

    @Override
    public void onConnectionResult(@NonNull String s, @NonNull ConnectionResolution connectionResolution) {
        logger.info("onConnectionResult: " + s);
        Comm c = commFor(s);
        switch (connectionResolution.getStatus().getStatusCode()) {
            case ConnectionsStatusCodes.STATUS_OK:
                logger.severe("connection " + s + " OK");
                c.setState(Comm.State.CONNECTED);
                break;
            case ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED:
                logger.info("connection " + s + " REJECTED");
                c.setState(Comm.State.DISCONNECTED);
                break;
            case ConnectionsStatusCodes.STATUS_ERROR:
                logger.info("connection " + s + "ERROR");
                c.setState(Comm.State.DISCONNECTED);
                break;
        }
    }

    @Override
    public void onDisconnected(@NonNull String s) {
        logger.info("onDisconnected: " + s);
        commFor(s).setState(Comm.State.DISCONNECTED);
    }
}
