package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Hexdump class (well, it's actually a namespace with some functions,
 * but let's stick with java terminology :-).  These methods generate
 * an output that resembles `hexdump -C` (Windows users: do you
 * remember `debug` in the DOS age?).
 *
 * @author human
 */
public class HexDump {

    private static final int FORMAT_OFFSET_PADDING = 8;
    private static final int FORMAT_BYTES_PER_ROW = 16;
    private static final byte[] HEXCHARS = "0123456789abcdef".getBytes();

    /**
     * Dump a byte array in a String.
     *
     * @param data Data to be dumped
     */
    public static String dump(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            dump(data, 0, data.length, out);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return out.toString();
    }

    /**
     * Dump a byte array in a String.
     *
     * @param data Data to be dumped
     * @param off  Offset from the beginning of <code>data</code>
     * @param len  Number of bytes of <code>data</code> to be dumped
     */
    public static String dump(byte[] data, int off, int len) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            dump(data, off, len, out);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return out.toString();
    }

    /**
     * Dump a byte array through a stream.
     *
     * @param data Data to be dumped
     * @param out  Output stream
     */
    public static void dump(byte data[], OutputStream out) throws IOException {
        dump(data, 0, data.length, out);
    }

    /**
     * Dump a byte array through a stream.
     *
     * @param data Data to be dumped
     * @param off  Offset from the beginning of <code>data</code>
     * @param len  Number of bytes of <code>data</code> to be dumped
     * @param out  Output stream
     */
    public static void dump(byte[] data, int off, int len, OutputStream out) throws IOException {
        String hexoff;
        int dumpoff, hexofflen, i, nextbytes, end = len + off;
        int val;

        for (dumpoff = off; dumpoff < end; dumpoff += FORMAT_BYTES_PER_ROW) {
            // Pad the offset with 0's (i miss my beloved sprintf()...)
            hexoff = Integer.toString(dumpoff, 16);
            hexofflen = hexoff.length();
            for (i = 0; i < FORMAT_OFFSET_PADDING - hexofflen; ++i) {
                hexoff = "0" + hexoff;
            }
            out.write((hexoff + " ").getBytes());

            // Bytes to be printed in the current line
            nextbytes = (FORMAT_BYTES_PER_ROW < (end - dumpoff) ? FORMAT_BYTES_PER_ROW : (end - dumpoff));

            for (i = 0; i < FORMAT_BYTES_PER_ROW; ++i) {
                // Put two spaces to separate 8-bytes blocks
                if ((i % 8) == 0) {
                    out.write(" ".getBytes());
                }
                if (i >= nextbytes) {
                    out.write("   ".getBytes());
                } else {
                    val = data[dumpoff + i] & 0xff;
                    out.write(HEXCHARS[val >>> 4]);
                    out.write(HEXCHARS[val & 0xf]);
                    out.write(" ".getBytes());
                }
            }

            out.write(" |".getBytes());

            for (i = 0; i < FORMAT_BYTES_PER_ROW; ++i) {
                if (i >= nextbytes) {
                    out.write(" ".getBytes());
                } else {
                    val = data[i + dumpoff];
                    // Is it a printable character?
                    if ((val > 31) && (val < 127)) {
                        out.write(val);
                    } else {
                        out.write(".".getBytes());
                    }
                }
            }

            out.write("|\n".getBytes());
        }
    }
}