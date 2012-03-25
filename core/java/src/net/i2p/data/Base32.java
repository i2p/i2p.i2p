package net.i2p.data;

/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Encodes and decodes to and from Base32 notation.
 * Ref: RFC 3548
 *
 * Don't bother with '=' padding characters on encode or
 * accept them on decode (i.e. don't require 5-character groups).
 * No whitespace allowed.
 *
 * Decode accepts upper or lower case.
 * @author zzz
 * @since 0.7
 */
public class Base32 {

    //private final static Log _log = new Log(Base32.class);

    /** The 32 valid Base32 values. */
    private final static char[] ALPHABET = {'a', 'b', 'c', 'd',
                                            'e', 'f', 'g', 'h', 'i', 'j',
                                            'k', 'l', 'm', 'n', 'o', 'p',
                                            'q', 'r', 's', 't', 'u', 'v',
                                            'w', 'x', 'y', 'z',
                                            '2', '3', '4', '5', '6', '7'};

    /** 
     * Translates a Base32 value to either its 5-bit reconstruction value
     * or a negative number indicating some other meaning.
     * Allow upper or lower case.
     **/
    private final static byte[] DECODABET = {
                                             26, 27, 28, 29, 30, 31, -9, -9, // Numbers two through nine
                                             -9, -9, -9, // Decimal 58 - 60
                                             -1, // Equals sign at decimal 61
                                             -9, -9, -9, // Decimal 62 - 64
                                             0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, // Letters 'A' through 'M'
                                             13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, // Letters 'N' through 'Z'
                                             -9, -9, -9, -9, -9, -9, // Decimal 91 - 96
                                             0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, // Letters 'a' through 'm'
                                             13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, // Letters 'n' through 'z'
                                             -9, -9, -9, -9, -9 // Decimal 123 - 127
    };

    private final static byte BAD_ENCODING = -9; // Indicates error in encoding
    private final static byte EQUALS_SIGN_ENC = -1; // Indicates equals sign in encoding

    /** Defeats instantiation. */
    private Base32() { // nop
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            help();
            return;
        }
        runApp(args);
    }

    private static void runApp(String args[]) {
        String cmd = args[0].toLowerCase(Locale.US);
        if ("encodestring".equals(cmd)) {
            System.out.println(encode(args[1].getBytes()));
            return;
        }
        InputStream in = System.in;
        OutputStream out = System.out;
        try {
            if (args.length >= 3) {
                out = new FileOutputStream(args[2]);
            }
            if (args.length >= 2) {
                in = new FileInputStream(args[1]);
            }
            if ("encode".equals(cmd)) {
                encode(in, out);
                return;
            }
            if ("decode".equals(cmd)) {
                decode(in, out);
                return;
            }
        } catch (IOException ioe) {
            ioe.printStackTrace(System.err);
        } finally {
            try { in.close(); } catch (IOException e) {}
            try { out.close(); } catch (IOException e) {}
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        byte buf[] = new byte[4096];
        while (true) {
            int read = in.read(buf);
            if (read < 0) break;
            baos.write(buf, 0, read);
        }
        return baos.toByteArray();
    }

    private static void encode(InputStream in, OutputStream out) throws IOException {
        String encoded = encode(read(in));
        for (int i = 0; i < encoded.length(); i++)
            out.write((byte)(encoded.charAt(i) & 0xFF));
    }

    private static void decode(InputStream in, OutputStream out) throws IOException {
        byte decoded[] = decode(new String(read(in)));
        if (decoded == null) {
            System.out.println("FAIL");
            return;
        }
        out.write(decoded);
    }

    private static void help() {
        System.out.println("Syntax: Base32 encode <inFile> <outFile>");
        System.out.println("or    : Base32 encode <inFile>");
        System.out.println("or    : Base32 encodestring <string>");
        System.out.println("or    : Base32 encode");
        System.out.println("or    : Base32 decode <inFile> <outFile>");
        System.out.println("or    : Base32 decode <inFile>");
        System.out.println("or    : Base32 decode");
    }

    /**
     *  @param source if null will return ""
     */
    public static String encode(String source) {
        return (source != null ? encode(source.getBytes()) : "");
    }

    /**
     * @param source The data to convert non-null
     */
    public static String encode(byte[] source) {
        StringBuilder buf = new StringBuilder((source.length + 7) * 8 / 5);
        encodeBytes(source, buf);
        return buf.toString();
    }

    private final static byte[] emask = { (byte) 0x1f,
                                          (byte) 0x01, (byte) 0x03, (byte) 0x07, (byte) 0x0f };
    /**
     * Encodes a byte array into Base32 notation.
     *
     * @param source The data to convert non-null
     */
    private static void encodeBytes(byte[] source, StringBuilder out) {
        int usedbits = 0;
        for (int i = 0; i < source.length; ) {
             int fivebits;
             if (usedbits < 3) {
                 fivebits = (source[i] >> (3 - usedbits)) & 0x1f;
                 usedbits += 5;
             } else if (usedbits == 3) {
                 fivebits = source[i++] & 0x1f;
                 usedbits = 0;
             } else {
                 fivebits = (source[i++] << (usedbits - 3)) & 0x1f;
                 if (i < source.length) {
                     usedbits -= 3;
                     fivebits |= (source[i] >> (8 - usedbits)) & emask[usedbits];
                 }
             }
             out.append(ALPHABET[fivebits]);
        }
    }

    /**
     * Decodes data from Base32 notation and
     * returns it as a string.
     *
     * @param s the string to decode, if null returns null
     * @return The data as a string or null on failure
     */
    public static String decodeToString(String s) {
        byte[] b = decode(s);
        if (b == null)
            return null;
        return new String(b);
    }

    /**
     * @param s non-null
     * @return decoded data, null on error
     */
    public static byte[] decode(String s) {
        return decode(s.getBytes());
    }

    private final static byte[] dmask = { (byte) 0xf8, (byte) 0x7c, (byte) 0x3e, (byte) 0x1f,
                                          (byte) 0x0f, (byte) 0x07, (byte) 0x03, (byte) 0x01 };
    /**
     * Decodes Base32 content in byte array format and returns
     * the decoded byte array.
     *
     * @param source The Base32 encoded data non-null
     * @return decoded data, null on error
     */
    private static byte[] decode(byte[] source) {
        int len58;
        if (source.length <= 1)
            len58 = source.length;
        else
            len58 = source.length * 5 / 8;
        byte[] outBuff = new byte[len58];
        int outBuffPosn = 0;

        int usedbits = 0;
        for (int i = 0; i < source.length; i++) {
            int fivebits;
            if ((source[i] & 0x80) != 0 || source[i] < '2' || source[i] > 'z')
                fivebits = BAD_ENCODING;
            else
                fivebits = DECODABET[source[i] - '2'];

            if (fivebits >= 0) {
                 if (usedbits == 0) {
                     outBuff[outBuffPosn] = (byte) ((fivebits << 3) & 0xf8);
                     usedbits = 5;
                 } else if (usedbits < 3) {
                     outBuff[outBuffPosn] |= (fivebits << (3 - usedbits)) & dmask[usedbits];
                     usedbits += 5;
                 } else if (usedbits == 3) {
                     outBuff[outBuffPosn++] |= fivebits;
                     usedbits = 0;
                 } else {
                     outBuff[outBuffPosn++] |= (fivebits >> (usedbits - 3)) & dmask[usedbits];
                     byte next = (byte) (fivebits << (11 - usedbits));
                     if (outBuffPosn < len58) {
                         outBuff[outBuffPosn] = next;
                         usedbits -= 3;
                     } else if (next != 0) {
                       //_log.warn("Extra data at the end: " + next + "(decimal)");
                       return null;
                     }
                 }
            } else {
                //_log.warn("Bad Base32 input character at " + i + ": " + source[i] + "(decimal)");
                return null;
            }
        }
        return outBuff;
    }
}
