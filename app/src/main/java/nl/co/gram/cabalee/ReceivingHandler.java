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
import android.content.Intent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.protobuf.ByteString;
import com.iwebpp.crypto.TweetNaclFast;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class ReceivingHandler {
    private static final Logger logger = Logger.getLogger("cabalee.receiver");
    private final byte[] key;
    private final ByteString myID;
    private final TweetNaclFast.SecretBox box;
    private final ByteString id;
    private final CommCenter commCenter;
    private String name;
    private final IDSet ids = new IDSet(0, 1);
    private final LocalBroadcastManager localBroadcastManager;
    private final List<Payload> payloads = new ArrayList<>();
    private final CabalNotification notificationHandler;

    public String type() { return "receive"; }

    public ReceivingHandler(byte[] key, CommCenter commCenter, Context context) {
        Util.checkArgument(key.length == TweetNaclFast.Box.secretKeyLength, "key wrong length");
        this.commCenter = commCenter;
        this.key = key;
        this.box = new TweetNaclFast.SecretBox(key);
        MessageDigest digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("no sha256", e);
        }
        this.id = ByteString.copyFrom(digest.digest(key));
        this.name = Util.toTitle(this.id.toByteArray());
        this.localBroadcastManager = LocalBroadcastManager.getInstance(context);
        this.notificationHandler = new CabalNotification(context, this);
        byte[] randomID = new byte[6];
        Util.randomBytes(randomID);
        myID = ByteString.copyFrom(randomID);
    }

    public ByteString myID() {
        return myID;
    }

    public synchronized void setName(String name) {
        this.name = name;
    }
    public synchronized String name() { return name; }

    private static byte[] newKey() {
        byte[] key = new byte[TweetNaclFast.SecretBox.keyLength];
        Util.randomBytes(key);
        return key;
    }

    static int paddingSize(int outputSize) {
        byte[] paddingSizeByte = new byte[1];
        Util.randomBytes(paddingSizeByte);
        int paddingSize = 0x7f & (int) paddingSizeByte[0];
        int minPadding = 127 - outputSize;
        return paddingSize < minPadding ? minPadding : paddingSize;
    }

    private static final ByteString paddingHelper = ByteString.copyFrom(new byte[128]);
    public static ByteString boxIt(Payload payload, TweetNaclFast.SecretBox box) {
        byte[] nonce = new byte[TweetNaclFast.SecretBox.nonceLength];
        Util.randomBytes(nonce);
        ByteString out = payload.toByteString();
        int paddingSize = paddingSize(out.size());
        out = ByteString.copyFrom(new byte[]{(byte) paddingSize}).concat(paddingHelper.substring(0, paddingSize)).concat(out);
        byte[] boxed = box.box(out.toByteArray(), nonce);
        return ByteString.copyFrom(nonce).concat(ByteString.copyFrom(boxed));
    }
    public static Payload unboxIt(ByteString bytes, TweetNaclFast.SecretBox box) {
        Payload payload;
        if (bytes.size() < TweetNaclFast.SecretBox.nonceLength + TweetNaclFast.SecretBox.overheadLength) {
            logger.severe("payload too short");
            return null;
        }
        ByteString nonce = bytes.substring(0, TweetNaclFast.SecretBox.nonceLength);
        ByteString encrypted = bytes.substring(TweetNaclFast.SecretBox.nonceLength);
        byte[] cleartext = box.open(encrypted.toByteArray(), nonce.toByteArray());
        if (cleartext == null || cleartext.length < 128 || cleartext[0] < 0 || cleartext.length < 1 + (int) cleartext[0]) {
            logger.severe("unable to open box");
            return null;
        }
        try {
            ByteString serializedPayload = ByteString.copyFrom(cleartext).substring(1+(int)cleartext[0]);
            payload = Payload.parseFrom(serializedPayload);
        } catch (Exception e) {
            logger.severe("discarding transport: " + e.getMessage());
            return null;
        }
        return payload.toBuilder().build();
    }

    public byte[] sooperSecret() {
        return key;
    }

    public ReceivingHandler(CommCenter commCenter, Context context) {
        this(newKey(), commCenter, context);
    }

    public ByteString id() { return id; }

    public void sendPayload(Payload payload) {
        ByteString boxed = boxIt(payload, box);
        ids.checkAndAdd(Util.transportID(boxed));
        commCenter.broadcastTransport(boxed, null);
        payloadToReceivers(payload);
    }

    public synchronized void payloadToReceivers(Payload p) {
        switch (p.getKindCase()) {
            case SELF_DESTRUCT: {
                Intent intent = new Intent(Intents.CABAL_DESTROY_REQUESTED);
                intent.putExtra(Intents.EXTRA_NETWORK_ID, id().toByteArray());
                localBroadcastManager.sendBroadcast(intent);
            }
            // FALLTHROUGH
            case CLEARTEXT_BROADCAST: {
                payloads.add(p);
                Intent intent = new Intent(Intents.PAYLOAD_RECEIVED);
                intent.putExtra(Intents.EXTRA_NETWORK_ID, id().toByteArray());
                localBroadcastManager.sendBroadcast(intent);
                break;
            }
        }
    }

    public boolean handleTransport(ByteString transport) {
        Payload payload = unboxIt(transport, box);
        if (payload == null) {
            logger.severe("transport discarded");
            return false;
        }
        if (ids.checkAndAdd(Util.transportID(transport))) {
            logger.severe("replay of old message");
            // we've already seen this, so let the caller know we've handled it.
            return true;
        }
        logger.info("received valid payload");
        payloadToReceivers(payload);
        return true;
    }

    public synchronized List<Payload> payloads() {
        return new ArrayList<>(payloads);
    }
}
