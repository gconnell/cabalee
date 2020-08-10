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

package nl.cl.gram.cabalee;

import android.graphics.Bitmap;
import android.util.Base64;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public class Util {
    private static final char CHAR_0 = 0x30;
    private static final char CHAR_A = 0x61;
    private static void appendNibble(int n, StringBuilder sb) {
        if (n < 0xa) {
            sb.append((char) (CHAR_0 + n));
        } else {
            sb.append((char) (CHAR_A + (n - 0xa)));
        }
    }
    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            appendNibble((b>>4)&0x0F, sb);
            appendNibble(b&0x0F, sb);

        }
        return sb.toString();
    }
    private static byte hexDigit(char c) {
        if (c >= '0' && c <= '9')
            return (byte) (c - '0');
        if (c >= 'a' && c <= 'f')
            return (byte) (0xA + c - 'a');
        if (c >= 'A' && c <= 'F')
            return (byte) (0xA + c - 'A');
        throw new RuntimeException("invalid hex digit: " + c);
    }
    public static byte[] fromHex(String hex) {
        if (hex.length() % 2 != 0) {
            throw new RuntimeException("document ID not even chars");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            byte n1 = hexDigit(hex.charAt(i*2));
            byte n2 = hexDigit(hex.charAt(i*2+1));
            out[i] = (byte) ((n1 << 4) + n2);
        }
        return out;
    }

    private static final SecureRandom secureRandom = new SecureRandom();
    public static synchronized void randomBytes(byte[] bytes) {
        secureRandom.nextBytes(bytes);
    }

    public static String toTitle(byte[] b) {
        String s = Base64.encodeToString(b, 0, 16, Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);
        return s;
    }

    public static class Uint32 {
        public static void writeLittleEndianTo(long out, OutputStream os) throws IOException {
            for (int i = 0; i < 4; i++) {
                os.write((byte) (out & 0xFFL));
                out >>= 8;
            }
        }
        public static byte[] writeLittleEndian(long out) {
            byte[] b = new byte[4];
            for (int i = 0; i < 4; i++) {
                b[i] = (byte) (out & 0xFFL);
                out >>= 8;
            }
            return b;
        }
        public static long readLittleEndianFrom(byte[] b) {
            Util.checkArgument(b.length >= 4, "readlittleendian bytes < 4");
            long out = 0;
            for (int i = 0; i < 4; i++) {
                out |= (0xFFL & (long) b[i]) << (i*8);
            }
            return out;
        }
        public static long readLittleEndianFrom(InputStream is) throws IOException {
            long out = 0;
            for (int i = 0; i < 4; i++) {
                out = (out >> 8) | (0xFFL & (long) is.read()) << 24;
            }
            return out;
        }
    }

    public static void checkArgument(boolean check, String msg, Object... args) {
        if (!check) {
            throw new IllegalArgumentException("argument invalid: " + String.format(msg, args));
        }
    }

    public static void fillBuffer(InputStream is, byte[] buf) throws IOException {
        int bytesRead = 0;
        while (bytesRead < buf.length) {
            bytesRead += is.read(buf, bytesRead, buf.length-bytesRead);
        }
    }

    public static Timestamp now() {
        long millis = System.currentTimeMillis();
        return Timestamp.newBuilder()
                .setSeconds(millis / 1000)
                .setNanos((int) ((millis % 1000L) * 1000000L))
                .build();
    }

    public static long newRandomID() {
        byte[] b = new byte[8];
        Util.randomBytes(b);
        long out = 0;
        for (int i = 0; i < 8; i++) {
            out |= (0xFFL & (long) b[i]) << (i*8);
        }
        return (out < 0 ? -out : out);
    }

    public static double nanosAsSeconds(long nanos) {
        return ((double) nanos) / 1_000_000_000D;
    }

    public static void checkArgument(boolean arg, String msg) {
        if (!arg) {
            throw new IllegalArgumentException(msg);
        }
    }

    public static MessageDigest sha256() {
        MessageDigest d;
        try {
            d = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("no sha256", e);
        }
        return d;
    }

    public static Bitmap identicon(ByteString id) {
        Bitmap bitmap=null;
        byte[] color = sha256().digest(id.toByteArray());
        int frd = color[0] & 0xff;
        int fgr = color[1] & 0xff;
        int fbl = color[2] & 0xff;
        int brd = color[3] & 0xff;
        int bgr = color[4] & 0xff;
        int bbl = color[5] & 0xff;
        try {
            int w = 4;
            int h = 7;
            boolean[] pixelFlags = new boolean[w * h];
            int count = 0;
            for (byte b : id.toByteArray()) {
                for (int i = 0; i < 8; i++) {
                    boolean bit = (b>>i&0x1) != 0;
                    int offset = count % pixelFlags.length;
                    pixelFlags[offset] ^= bit;
                    count++;
                }
            }
            int foreground = 0xff808080 | (frd << 16) | (fgr << 8) | fbl;
            int background = (0xff000000 | (brd << 16) | (bgr << 8) | bbl) & 0xff7f7f7f;
            int[] pixels = new int[h*h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int pixelColor = pixelFlags[w*y+x] ? foreground : background;
                    int yoffset = h*y;
                    pixels[yoffset+x] = pixelColor;
                    pixels[yoffset+h-x-1] = pixelColor;
                }
            }
            bitmap = Bitmap.createBitmap(h, h, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, h, 0, 0, h, h);
        } catch (Exception iae) {
            iae.printStackTrace();
            return null;
        }
        return Bitmap.createScaledBitmap(bitmap, 49, 49, false);
    }


    public static ByteString transportID(ByteString bs) {
        MessageDigest d;
        try {
            d = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("no sha256");
        }
        d.update(bs.asReadOnlyByteBuffer());
        return ByteString.copyFrom(d.digest());
    }
}
