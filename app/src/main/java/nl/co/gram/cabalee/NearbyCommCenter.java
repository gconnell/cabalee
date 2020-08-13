// Copyright 2020 The Cabalī Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package nl.co.gram.cabalee;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.Strategy;
import com.google.protobuf.ByteString;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class NearbyCommCenter extends ConnectionLifecycleCallback {
    private static final Logger logger = Logger.getLogger("cabalee.nearby");
    private final CommCenter commCenter;
    private Map<String, NearbyComm> commsByRemote = new HashMap<>();
    private final ConnectionsClient connectionsClient;
    private final Strategy strategy;
    private static final String SERVICE_ID = "nl.co.gram.cabalee";

    NearbyCommCenter(Context context, CommCenter cc, Strategy strategy) {
        this.commCenter = cc;
        this.strategy = strategy;
        connectionsClient = Nearby.getConnectionsClient(context);
    }

    public CommCenter commCenter() { return commCenter; }

    private synchronized NearbyComm commFor(String remote) {
        NearbyComm c = commsByRemote.get(remote);
        if (c == null) {
            c = new NearbyComm(this, remote);
            commsByRemote.put(remote, c);
        }
        return c;
    }

    public void onCreate() {
        startAdvertising();
        startDiscovery();
    }

    public void onDestroy() {
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        connectionsClient.stopAllEndpoints();
    }

    synchronized void recheckState(NearbyComm c) {
        switch (c.state()) {
            case CONNECTED:
                commCenter.addComm(c);
                break;
            case DISCONNECTING:
                connectionsClient.disconnectFromEndpoint(c.remote());
                break;
            case DISCONNECTED:
                commCenter.removeComm(c);
                commsByRemote.remove(c.remote());
                break;
        }
    }

    void sendPayload(ByteString bs, String remote) {
        logger.info("Sending to " + this);
        connectionsClient.sendPayload(remote, Payload.fromBytes(bs.toByteArray()));
    }

    @Override
    public void onConnectionInitiated(@NonNull String s, @NonNull ConnectionInfo connectionInfo) {
        logger.info("onConnectionInitiated: " + s);
        NearbyComm c = commFor(s);
        connectionsClient.acceptConnection(s, c);
        c.setState(NearbyComm.State.ACCEPTING);
    }

    @Override
    public void onConnectionResult(@NonNull String s, @NonNull ConnectionResolution connectionResolution) {
        logger.info("onConnectionResult: " + s);
        NearbyComm c = commFor(s);
        switch (connectionResolution.getStatus().getStatusCode()) {
            case ConnectionsStatusCodes.STATUS_OK:
                logger.severe("connection " + s + " OK");
                c.setState(NearbyComm.State.CONNECTED);
                break;
            case ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED:
                logger.info("connection " + s + " REJECTED");
                c.setState(NearbyComm.State.DISCONNECTED);
                break;
            case ConnectionsStatusCodes.STATUS_ERROR:
                logger.info("connection " + s + "ERROR");
                c.setState(NearbyComm.State.DISCONNECTED);
                break;
        }
    }

    @Override
    public void onDisconnected(@NonNull String s) {
        logger.info("onDisconnected: " + s);
        commFor(s).setState(NearbyComm.State.DISCONNECTED);
    }

    public void startDiscovery() {
        DiscoveryOptions discoveryOptions =
                new DiscoveryOptions.Builder().setStrategy(strategy).build();
        EndpointDiscoveryCallback callback = new EndpointDiscoveryCallback() {
            @Override
            public void onEndpointFound(@NonNull String s, @NonNull DiscoveredEndpointInfo discoveredEndpointInfo) {
                logger.info("onEndpointFound: " + s);
                connectionsClient
                        .requestConnection("cabalee", s, NearbyCommCenter.this)
                        .addOnSuccessListener(
                                (Void unused) -> {
                                    logger.info("requesting connection to " + s + " succeeded");
                                })
                        .addOnFailureListener(
                                (Exception e) -> {
                                    logger.info("requesting connection to " + s + " failed: " + e.getMessage());
                                });
            }

            @Override
            public void onEndpointLost(@NonNull String s) {
                logger.info("onEndpointLost: " + s);
            }
        };
        connectionsClient
                .startDiscovery(SERVICE_ID, callback, discoveryOptions)
                .addOnSuccessListener(
                        (Void unused) -> {
                            logger.info("Discovering started");
                        })
                .addOnFailureListener(
                        (Exception e) -> {
                            logger.severe("Discovering failed: " + e.getMessage());
                            e.printStackTrace();
                        });
    }

    public void startAdvertising() {
        AdvertisingOptions advertisingOptions =
                new AdvertisingOptions.Builder().setStrategy(strategy).build();
        connectionsClient
                .startAdvertising("cabalee", SERVICE_ID, this, advertisingOptions)
                .addOnSuccessListener(
                        (Void unused) -> {
                            logger.info("Advertizing started");
                        })
                .addOnFailureListener(
                        (Exception e) -> {
                            logger.severe("Advertizing failed: " + e.getMessage());
                            e.printStackTrace();
                        });
    }
}
