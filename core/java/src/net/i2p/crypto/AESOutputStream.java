package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;

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
    /** 
     * buffer containing the unwritten bytes.  The first unwritten
     * byte is _lastCommitted+1, and the last unwritten byte is _nextWrite-1
     * (aka the next byte to be written on the array is _nextWrite)
     */
    private byte[] _unencryptedBuf;
    private byte _writeBlock[];
    /** how many bytes have we been given since we flushed it to the stream? */
    private int _writesSinceCommit;
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
        _unencryptedBuf = new byte[MAX_BUF];
        _writeBlock = new byte[BLOCK_SIZE];
        _writesSinceCommit = 0;
    }
    
    @Override
    public void write(int val) throws IOException {
        _cumulativeProvided++;
        _unencryptedBuf[_writesSinceCommit++] = (byte)(val & 0xFF);
        if (_writesSinceCommit == _unencryptedBuf.length)
            doFlush();
    }
    
    @Override
    public void write(byte src[]) throws IOException {
        write(src, 0, src.length);
    }
    
    @Override
    public void write(byte src[], int off, int len) throws IOException {
        // i'm too lazy to unroll this into the partial writes (dealing with
        // wrapping around the buffer size)
        for (int i = 0; i < len; i++)
            write(src[i+off]);
    }
    
    @Override
    public void close() throws IOException {
        flush();
        out.close();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Cumulative bytes provided to this stream / written out / padded: " 
                       + _cumulativeProvided + "/" + _cumulativeWritten + "/" + _cumulativePadding);
    }
    
    @Override
    public void flush() throws IOException {
        doFlush();
        out.flush();
    }

    private void doFlush() throws IOException {
        if (_log.shouldLog(Log.INFO))
            _log.info("doFlush(): writesSinceCommit=" + _writesSinceCommit);
        writeEncrypted();
        _writesSinceCommit = 0;
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
    private void writeEncrypted() throws IOException {
        int numBlocks = _writesSinceCommit / (BLOCK_SIZE - 1);

        if (_log.shouldLog(Log.INFO))
            _log.info("writeE(): #=" + _writesSinceCommit + " blocks=" + numBlocks);
        
        for (int i = 0; i < numBlocks; i++) {
            DataHelper.xor(_unencryptedBuf, i * 15, _lastBlock, 0, _writeBlock, 0, 15);
            // the padding byte for "full" blocks
            _writeBlock[BLOCK_SIZE - 1] = (byte)(_lastBlock[BLOCK_SIZE - 1] ^ 0x01); 
            _context.aes().encrypt(_writeBlock, 0, _writeBlock, 0, _key, _lastBlock, BLOCK_SIZE);
            out.write(_writeBlock);
            System.arraycopy(_writeBlock, 0, _lastBlock, 0, BLOCK_SIZE);
            _cumulativeWritten += BLOCK_SIZE;
            _cumulativePadding++;
        }

        if (_writesSinceCommit % 15 != 0) {
            // we need to do non trivial padding
            int remainingBytes = _writesSinceCommit - numBlocks * 15;
            int paddingBytes = BLOCK_SIZE - remainingBytes;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Padding " + _writesSinceCommit + " with " + paddingBytes + " bytes in " + (numBlocks+1) + " blocks");
            System.arraycopy(_unencryptedBuf, numBlocks * 15, _writeBlock, 0, remainingBytes);
            Arrays.fill(_writeBlock, remainingBytes, BLOCK_SIZE, (byte) paddingBytes);
            DataHelper.xor(_writeBlock, 0, _lastBlock, 0, _writeBlock, 0, BLOCK_SIZE);
            _context.aes().encrypt(_writeBlock, 0, _writeBlock, 0, _key, _lastBlock, BLOCK_SIZE);
            out.write(_writeBlock);
            System.arraycopy(_writeBlock, 0, _lastBlock, 0, BLOCK_SIZE);
            _cumulativePadding += paddingBytes;
            _cumulativeWritten += BLOCK_SIZE;
        }
    }
}