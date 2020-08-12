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

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NearbyComm extends PayloadCallback implements Comm {
    private static final Logger logger = Logger.getLogger("cabalee.comm");
    private final String remote;
    private State state = State.STARTING;
    private final NearbyCommCenter commCenter;

    public enum State {
        STARTING,
        ACCEPTING,
        CONNECTED,
        DISCONNECTING,
        DISCONNECTED;
    }

    public NearbyComm(NearbyCommCenter commCenter, String remote) {
        this.commCenter = commCenter;
        this.remote = remote;
    }

    public State state() {
        return state;
    }

    @Override
    public String name() {
        return "nearby:" + remote;
    }
    void setState(State s) {
        state = s;
        commCenter.recheckState(this);
    }

    public String remote() {
        return remote;
    }

    @Override
    public void sendPayload(ByteString payload) {
        commCenter.sendPayload(payload, remote);
    }

    private void handlePayloadBytes(ByteString bs) throws IOException {
        if (bs.size() < 1) {
            throw new IOException("payload is too small");
        }
        switch (bs.byteAt(0)) {
            case MsgType.CABAL_MESSAGE_V1_VALUE: {
                logger.info("Transport");
                commCenter.commCenter().handleTransport(name(), bs.substring(1));
                break;
            }
            case MsgType.KEEPALIVE_MESSAGE_V1_VALUE: {
                logger.info("Received (ignoring) keepalive");
                break;
            }
            default:
                throw new IOException("unsupported type " + bs.byteAt(0));
        }
    }

    private void handlePayload(@NonNull Payload payload) throws IOException {
        switch (payload.getType()) {
            case Payload.Type.BYTES:
                handlePayloadBytes(ByteString.copyFrom(payload.asBytes()));
                break;
            default:
                logger.info("unexpected payload type " + payload.getType() + " ignored");
        }
    }

    @Override
    public void onPayloadReceived(@NonNull String s, @NonNull Payload payload) {
        logger.fine("onPayloadReceived from " + s);
        try {
            handlePayload(payload);
        } catch (IOException e) {
            logger.severe("handling payload: " + e.getMessage());
            setState(State.DISCONNECTING);
        }
    }

    @Override
    public void onPayloadTransferUpdate(@NonNull String s, @NonNull PayloadTransferUpdate payloadTransferUpdate) {
        Level level = Level.FINE;
        if (payloadTransferUpdate.getStatus() != PayloadTransferUpdate.Status.SUCCESS) {
            level = Level.WARNING;
        }
        logger.log(level, "onPayloadTransferUpdate " + s + " = " +
                payloadTransferUpdate.getPayloadId() + ": " + payloadTransferUpdate.getStatus() +
                " (bytes=" + payloadTransferUpdate.getBytesTransferred() + ", total=" + payloadTransferUpdate.getTotalBytes() + ")");
    }
}
