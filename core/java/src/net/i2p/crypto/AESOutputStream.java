package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import net.i2p.data.SessionKey;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.I2PAppContext;

/**
 * This writes everything as CBC with PKCS#5 padding, but each block is padded
 * so as soon as a block is received it can be decrypted (rather than wait for
 * an arbitrary number of blocks to arrive).  That means that each block sent 
 * will contain exactly one padding byte (unless it was flushed with 
 * numBytes % (BLOCK_SIZE-1) != 0, in which case that last block will be padded 
 * with up to 15 bytes).  So we have an expansion factor of 6.25%.  c'est la vie
 *
 */
public class AESOutputStream extends FilterOutputStream {
    private Log _log;
    private I2PAppContext _context;
    private SessionKey _key;
    private byte[] _lastBlock;
    private ByteArrayOutputStream _inBuf;
    private long _cumulativeProvided; // how many bytes provided to this stream
    private long _cumulativeWritten; // how many bytes written to the underlying stream
    private long _cumulativePadding; // how many bytes of padding written

    public final static float EXPANSION_FACTOR = 1.0625f; // 6% overhead w/ the padding

    private final static int BLOCK_SIZE = CryptixRijndael_Algorithm._BLOCK_SIZE;
    private final static int MAX_BUF = 256;

    public AESOutputStream(I2PAppContext context, OutputStream source, SessionKey key, byte[] iv) {
        super(source);
        _context = context;
        _log = context.logManager().getLog(AESOutputStream.class);
        _key = key;
        _lastBlock = new byte[BLOCK_SIZE];
        System.arraycopy(iv, 0, _lastBlock, 0, BLOCK_SIZE);
        _inBuf = new ByteArrayOutputStream(MAX_BUF);
    }

    public void write(int val) throws IOException {
        _cumulativeProvided++;
        _inBuf.write(val);
        if (_inBuf.size() > MAX_BUF) doFlush();
    }

    public void write(byte src[]) throws IOException {
        _cumulativeProvided += src.length;
        _inBuf.write(src);
        if (_inBuf.size() > MAX_BUF) doFlush();
    }

    public void write(byte src[], int off, int len) throws IOException {
        _cumulativeProvided += len;
        _inBuf.write(src, off, len);
        if (_inBuf.size() > MAX_BUF) doFlush();
    }

    public void close() throws IOException {
        flush();
        out.close();
        _inBuf.reset();
        _log.debug("Cumulative bytes provided to this stream / written out / padded: " + _cumulativeProvided + "/"
                   + _cumulativeWritten + "/" + _cumulativePadding);
    }

    public void flush() throws IOException {
        doFlush();
        out.flush();
    }

    private void doFlush() throws IOException {
        writeEncrypted(_inBuf.toByteArray());
        _inBuf.reset();
    }

    /**
     * Encrypt an arbitrary size array with AES using CBC and PKCS#5 padding,
     * write it to the stream, and set _lastBlock to the last encrypted
     * block.  This operation works by taking every (BLOCK_SIZE-1) bytes
     * from the src, padding it with PKCS#5 (aka adding 0x01), and encrypting
     * it.  If the last block doesn't contain exactly (BLOCK_SIZE-1) bytes, it
     * is padded with PKCS#5 as well (adding # padding bytes repeated that many
     * times).
     *
     */
    private void writeEncrypted(byte src[]) throws IOException {
        if ((src == null) || (src.length == 0)) return;
        int numBlocks = src.length / (BLOCK_SIZE - 1);

        byte block[] = new byte[BLOCK_SIZE];
        block[BLOCK_SIZE - 1] = 0x01; // the padding byte for "full" blocks
        for (int i = 0; i < numBlocks; i++) {
            System.arraycopy(src, i * 15, block, 0, 15);
            byte data[] = DataHelper.xor(block, _lastBlock);
            byte encrypted[] = _context.AESEngine().encrypt(data, _key, _lastBlock);
            _cumulativeWritten += encrypted.length;
            out.write(encrypted);
            System.arraycopy(encrypted, encrypted.length - BLOCK_SIZE, _lastBlock, 0, BLOCK_SIZE);
            _cumulativePadding++;
        }

        if (src.length % 15 != 0) {
            // we need to do non trivial padding
            int remainingBytes = src.length - numBlocks * 15;
            int paddingBytes = BLOCK_SIZE - remainingBytes;
            System.arraycopy(src, numBlocks * 15, block, 0, remainingBytes);
            Arrays.fill(block, remainingBytes, BLOCK_SIZE, (byte) paddingBytes);
            byte data[] = DataHelper.xor(block, _lastBlock);
            byte encrypted[] = _context.AESEngine().encrypt(data, _key, _lastBlock);
            out.write(encrypted);
            System.arraycopy(encrypted, encrypted.length - BLOCK_SIZE, _lastBlock, 0, BLOCK_SIZE);
            _cumulativePadding += paddingBytes;
            _cumulativeWritten += encrypted.length;
        }
    }

}