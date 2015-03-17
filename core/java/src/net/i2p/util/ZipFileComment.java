package net.i2p.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.util.zip.ZipException;

import net.i2p.data.DataHelper;

/**
 * Not available in ZipFile until Java 7. Refs:
 * https://secure.wikimedia.org/wikipedia/en/wiki/ZIP_%28file_format%29
 * http://download.oracle.com/javase/1.5.0/docs/api/java/util/zip/ZipFile.html
 * http://bugs.sun.com/view_bug.do?bug_id=6646605
 *
 * Code modified from:
 * http://www.flattermann.net/2009/01/read-a-zip-file-comment-with-java/
 * Beerware.
 *
 * since 0.8.8
 */
public abstract class ZipFileComment {

    private static final int BLOCK_LEN = 22;
    private static final byte[] magicStart = {0x50, 0x4b, 0x03, 0x04};
    private static final int HEADER_LEN = magicStart.length;
    private static final byte[] magicDirEnd = {0x50, 0x4b, 0x05, 0x06};
    private static final int MAGIC_LEN = magicDirEnd.length;

    /**
     *  @param max The max length of the comment in bytes.
     *             If the actual comment is longer, it will not be found and
     *             this method will throw an IOE
     *
     *  @return empty string if no comment, or the comment.
     *          The string is decoded with UTF-8
     *
     *  @throws IOE if no valid end-of-central-directory record found
     */
    public static String getComment(File file, int max) throws IOException {
        return getComment(file, max, 0);
    }

    /**
     *  @param max The max length of the comment in bytes.
     *             If the actual comment is longer, it will not be found and
     *             this method will throw an IOE
     *  @param skip Number of bytes to skip in the file before looking for the
     *              zip header. Use 56 for sud/su2 files.
     *
     *  @return empty string if no comment, or the comment.
     *          The string is decoded with UTF-8
     *
     *  @throws IOE if no valid end-of-central-directory record found
     */
    public static String getComment(File file, int max, int skip) throws IOException {
        if (!file.exists())
            throw new FileNotFoundException("File not found: " + file);
        long len = file.length();
        if (len < BLOCK_LEN + HEADER_LEN + skip)
            throw new ZipException("File too short: " + file);
        if (len > Integer.MAX_VALUE)
            throw new ZipException("File too long: " + file);
        int fileLen = (int) len;
        byte[] buffer = new byte[Math.min(fileLen - skip, max + BLOCK_LEN)];
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            if (skip > 0)
                in.skip(skip);
            byte[] hdr = new byte[HEADER_LEN];
            DataHelper.read(in, hdr);
            if (!DataHelper.eq(hdr, magicStart))
                throw new ZipException("Not a zip file: " + file);
            in.skip(fileLen - (skip + HEADER_LEN + buffer.length));
            DataHelper.read(in, buffer);
            return getComment(buffer);
        } finally {
           if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  go backwards from the end
     */
    private static String getComment(byte[] buffer) throws IOException {
        for (int i = buffer.length - (1 + BLOCK_LEN - MAGIC_LEN); i >= 0; i--) {
            if (DataHelper.eq(buffer, i, magicDirEnd, 0, MAGIC_LEN)) {
                int commentLen = (buffer[i + BLOCK_LEN - 2] & 0xff) +
                                 ((buffer[i + BLOCK_LEN - 1] & 0xff) * 256);
                return new String(buffer, i + BLOCK_LEN, commentLen, "UTF-8");
            }
        }
        throw new ZipException("No comment block found");
    }

    public static void main(String args[]) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: ZipFileComment file");
            return;
        }
        int skip = 0;
        String file = args[0];
        if (file.endsWith(".sud") || file.endsWith(".su2"))
            skip = 56;
        String c = getComment(new File(file), 256, skip);
        System.out.println("comment is: \"" + c + '\"');
    }
}
