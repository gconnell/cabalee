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

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class Cabal {
    private static final Logger logger = Logger.getLogger("cabalee.receiver");
    private final byte[] key;
    private final ByteString myID;
    private final TweetNaclFast.SecretBox box;
    private final ByteString id;
    private final CommCenter commCenter;
    private String name;
    private final IDSet ids = new IDSet(0, 1);
    private final LocalBroadcastManager localBroadcastManager;
    private final List<Message> messages = new ArrayList<>();
    private final CabalNotification notificationHandler;

    public String type() { return "receive"; }

    public Cabal(byte[] key, CommCenter commCenter, Context context) {
        Util.checkArgument(key.length == TweetNaclFast.Box.secretKeyLength, "key wrong length");
        this.commCenter = commCenter;
        this.key = key;
        this.box = new TweetNaclFast.SecretBox(key);
        this.id = idFor(key);
        this.name = Util.toTitle(this.id.toByteArray());
        this.localBroadcastManager = LocalBroadcastManager.getInstance(context);
        this.notificationHandler = new CabalNotification(context, this);
        byte[] randomID = new byte[6];
        Util.randomBytes(randomID);
        myID = ByteString.copyFrom(randomID);
    }

    public static ByteString idFor(byte[] key) {
        MessageDigest digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("no sha256", e);
        }
        return ByteString.copyFrom(digest.digest(key));
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
    public static ByteString boxIt(Payload payload, TweetNaclFast.SecretBox box, Identity identity) {
        byte[] nonce = new byte[TweetNaclFast.SecretBox.nonceLength];
        Util.randomBytes(nonce);

        // First, we serialize
        ByteString payloadBytes = payload.toByteString();

        // Then, we pad, up to a minimum of 128 bytes and with between 1-128 bytes (uniformly random) of padding
        int paddingSize = paddingSize(payloadBytes.size());
        ByteString paddingBytes = ByteString.copyFrom(new byte[]{(byte) paddingSize}).concat(paddingHelper.substring(0, paddingSize));
        ByteString toSign = paddingBytes.concat(payloadBytes);

        // Then, we sign with our identity.
        byte[] signed = identity.sign(toSign.toByteArray());
        ByteString.Output toEncrypt = ByteString.newOutput(toSign.size() + Identity.PublicKey.OVERHEAD);
        toEncrypt.write(identity.publicKey().signingType());
        try {
            identity.publicKey().identity().writeTo(toEncrypt);
            toEncrypt.write(signed);
        } catch (IOException e) {
            throw new RuntimeException("writing to bytestring output", e);
        }

        // Finally, we encrypt
        byte[] encrypted = box.box(toEncrypt.toByteString().toByteArray(), nonce);

        // Lastly, we prepend the nonce.
        ByteString.Output out = ByteString.newOutput(nonce.length + encrypted.length);
        try {
            out.write(nonce);
            out.write(encrypted);
        } catch (IOException e) {
            throw new RuntimeException("writing bytestring", e);
        }
        return out.toByteString();
    }
    public static Message unboxIt(ByteString bytes, TweetNaclFast.SecretBox box) {
        // Unwrap outer cabal encryption
        if (bytes.size() < TweetNaclFast.SecretBox.nonceLength + TweetNaclFast.SecretBox.overheadLength) {
            logger.severe("payload too short");
            return null;
        }
        ByteString nonce = bytes.substring(0, TweetNaclFast.SecretBox.nonceLength);
        ByteString encrypted = bytes.substring(TweetNaclFast.SecretBox.nonceLength);
        byte[] cleartext = box.open(encrypted.toByteArray(), nonce.toByteArray());
        if (cleartext == null) {
            logger.severe("failed to open box");
            return null;
        } else if (cleartext.length < 128 + Identity.PublicKey.OVERHEAD) {
            logger.severe("opened box too short: " + cleartext.length);
            return null;
        }

        // Unwrap inner identity and verify
        ByteString clear = ByteString.copyFrom(cleartext);
        Identity.PublicKey key = new Identity.PublicKey(clear.byteAt(0), clear.substring(1, 1+ Identity.PublicKey.SIZE).toByteArray());
        byte[] verified = key.open(clear.substring(1+ Identity.PublicKey.SIZE).toByteArray());
        if (verified == null || verified[0] < 0 || verified.length < verified[0]+1) {
            logger.severe("unable to verify box");
            return null;
        }

        // Extract payload.
        Payload payload;
        try {
            ByteString serializedPayload = ByteString.copyFrom(verified).substring(1+(int)verified[0]);
            payload = Payload.parseFrom(serializedPayload);
        } catch (Exception e) {
            logger.severe("deserializing: " + e.getMessage());
            return null;
        }
        return new Message(payload, key);
    }

    public byte[] sooperSecret() {
        return key;
    }

    public Cabal(CommCenter commCenter, Context context) {
        this(newKey(), commCenter, context);
    }

    public ByteString id() { return id; }

    public void sendPayload(Payload payload, Identity identity) {
        ByteString boxed = boxIt(payload, box, identity);
        ids.checkAndAdd(Util.transportID(boxed));
        commCenter.broadcastTransport(boxed, null);
        messageToReceivers(new Message(payload, identity.publicKey()));
    }

    public synchronized void messageToReceivers(Message m) {
        switch (m.payload.getKindCase()) {
            case SELF_DESTRUCT: {
                Intent intent = new Intent(Intents.CABAL_DESTROY_REQUESTED);
                intent.putExtra(Intents.EXTRA_NETWORK_ID, id().toByteArray());
                localBroadcastManager.sendBroadcast(intent);
            }
            // FALLTHROUGH
            case CLEARTEXT_BROADCAST: {
                messages.add(m);
                Intent intent = new Intent(Intents.PAYLOAD_RECEIVED);
                intent.putExtra(Intents.EXTRA_NETWORK_ID, id().toByteArray());
                localBroadcastManager.sendBroadcast(intent);
                break;
            }
        }
    }

    public boolean handleTransport(ByteString transport) {
        Message payload = unboxIt(transport, box);
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
        messageToReceivers(payload);
        return true;
    }

    public synchronized List<Message> messages() {
        return new ArrayList<>(messages);
    }
}
