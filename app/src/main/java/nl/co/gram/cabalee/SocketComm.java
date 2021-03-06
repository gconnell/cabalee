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
// limitations under the License.package nl.cl.gram.cabalee;

package nl.co.gram.cabalee;

import com.google.protobuf.ByteString;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class SocketComm implements Comm {
    private static final Logger logger = Logger.getLogger("cabalee.socketcomm");
    private static final ByteString PREAMBLE = ByteString.copyFrom("Cabalee1", StandardCharsets.UTF_8);
    final AtomicBoolean initiatedSuccessfully = new AtomicBoolean(true);
    final AtomicBoolean closed = new AtomicBoolean(false);
    final CountDownLatch initiated = new CountDownLatch(2);
    private static final int MAX_MESSAGE_SIZE = 32 * 1024;  // 32K
    private final InputStream is;
    private final OutputStream os;
    private final byte[] inputBuf = new byte[1024];
    private final String name;
    private final BlockingDeque<ByteString> toSend = new LinkedBlockingDeque<>();
    private final CommCenter commCenter;
    private final List<Runnable> onClose = new ArrayList<>();

    @Override
    public String name() {
        return name;
    }

    @Override
    public void sendPayload(ByteString payload) {
        toSend.add(payload);
    }

    public static class CommException extends Exception {
        CommException(String msg) { super(msg); }
        CommException(String msg, Throwable t) { super(msg, t); }
    }

    // returns null if there are no more messages
    private ByteString readPayload() throws IOException, CommException {
        logger.fine("reading message");
        long length = Util.Uint32.readLittleEndianFrom(is);
        if (length > MAX_MESSAGE_SIZE || length < 0) {
            throw new CommException("received invalid length " + length);
        }
        logger.fine("Receiving " + length);
        // TODO: figure out how to read directly into ByteString(.Output)
        ByteString.Output bso = ByteString.newOutput((int) length);
        fillBuffer(is, bso, (int) length);
        logger.fine("read message successfully");
        ByteString bs = bso.toByteString();
        return bs;
    }

    private void writePayload(ByteString bs) throws IOException, CommException {
        logger.fine("writing message size " + bs.size());
        synchronized (os) {
            if (bs.size() > MAX_MESSAGE_SIZE) {
                throw new CommException("message too big (" + bs.size() + "), cowardly refusal to write");
            }
            Util.Uint32.writeLittleEndianTo(bs.size(), os);
            bs.writeTo(os);
        }
        logger.fine("wrote message successfully");
    }

    public SocketComm(CommCenter commCenter, InputStream is, OutputStream os, String name) {
        this.is = is;
        this.os = os;
        this.name = name;
        this.commCenter = commCenter;
        new Thread(sender()).start();
        new Thread(receiver()).start();
    }

    public SocketComm addCloseRunnable(Runnable r) {
        onClose.add(r);
        return this;
    }

    private void reallyAwait(CountDownLatch cdl) {
        while (true) {
            try {
                cdl.await();
                return;
            } catch (InterruptedException e) {
                logger.warning("countdownlatch reallyAwait interrupted: " + e.getMessage());
            }
        }
    }

    public synchronized void close() {
        if (closed.getAndSet(true)) {
            try {
                this.is.close();
            } catch (IOException e) {
                logger.severe("closing input stream: " + e.getMessage());
            }
            try {
                this.os.close();
            } catch (IOException e) {
                logger.severe("closing output stream: " + e.getMessage());
            }
            toSend.add(ByteString.EMPTY);
            for (Runnable r: onClose) {
                r.run();
            }
        }
    }

    public boolean closed() {
        return this.closed.get();
    }

    private Runnable sender() {
        return new Runnable() {
            @Override
            public void run() {
                logger.info("send init writing preamble");
                try {
                    synchronized (os) {
                        PREAMBLE.writeTo(os);
                    }
                } catch (Throwable e) {
                    logger.severe("initiating: " + e.getMessage());
                    initiatedSuccessfully.set(false);
                }
                logger.info("send initiated");
                initiated.countDown();
                reallyAwait(initiated);
                if (initiatedSuccessfully.get()) {
                    commCenter.addComm(SocketComm.this);
                    try {
                        while (!closed()) {
                            ByteString bs = toSend.takeFirst();
                            if (bs.size() > 0) {
                                writePayload(bs);
                            }
                        }
                    } catch (Throwable t) {
                        logger.info("thrown while writing: " + t.getMessage());
                    } finally {
                        commCenter.removeComm(SocketComm.this);
                    }
                }
                close();
            }
        };
    }

    private Runnable receiver() {
        return new Runnable() {
            @Override
            public void run() {
                logger.info("recv init");
                try {
                    ByteString.Output gotPreamble = ByteString.newOutput(8);
                    synchronized (is) {
                        fillBuffer(is, gotPreamble, 8);
                    }
                    if (!gotPreamble.toByteString().equals(PREAMBLE)) {
                        throw new CommException("received unexpected preamble");
                    }
                } catch (IOException e) {
                    logger.info("recv ioerror: " + e.getMessage());
                    initiatedSuccessfully.set(false);
                } catch (Throwable e) {
                    logger.severe("receiving: " + e.getMessage());
                    e.printStackTrace();
                    initiatedSuccessfully.set(false);
                }
                logger.info("recv initiated");
                initiated.countDown();
                reallyAwait(initiated);
                if (!initiatedSuccessfully.get()) {
                    logger.warning("recv init unsuccessful, bailing");
                } else {
                    try {
                        while (!closed()) {
                            ByteString received = readPayload();
                            commCenter.handlePayloadBytes(name(), received);
                        }
                    } catch (IOException e) {
                        logger.info("got IOException from msg read, closing successfully: " + e.getMessage());
                    } catch (Throwable e) {
                        logger.severe("reading payload: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                close();
            }
        };
    }

    private void fillBuffer(InputStream is, ByteString.Output bs, int length) throws IOException {
        synchronized (inputBuf) {
            int bytesRead = 0;
            while (bytesRead < length) {
                int n = is.read(inputBuf, 0, length-bytesRead);
                if (n < 0) {
                    throw new IOException("end of input stream");
                }
                bs.write(inputBuf, 0, n);
                bytesRead += n;
            }
        }
    }
}
