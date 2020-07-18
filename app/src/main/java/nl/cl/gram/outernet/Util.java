package nl.cl.gram.outernet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    public static class Uint32 {
        public static void writeLittleEndianTo(long out, OutputStream os) throws IOException {
            for (int i = 0; i < 4; i++) {
                os.write((byte) (out & 0xFFL));
                out >>= 8;
            }
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
}
