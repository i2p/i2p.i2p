package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * This reads an underlying stream as written by AESOutputStream - AES256 encrypted
 * in CBC mode with PKCS#5 padding, with the padding on each and every block of 
 * 16 bytes.  This minimizes the overhead when communication is intermittent, 
 * rather than when streams of large sets of data are sent (in which case, the
 * padding would be on a larger size - say, 1k, though in the worst case that 
 * would have 1023 bytes of padding, while in the worst case here, we only have
 * 15 bytes of padding).  So we have an expansion factor of 6.25%.  c'est la vie
 *
 */
public class AESInputStream extends FilterInputStream {
    private Log _log;
    private I2PAppContext _context;
    private SessionKey _key;
    private byte[] _lastBlock;
    private boolean _eofFound;
    private long _cumulativeRead; // how many read from the source stream
    private long _cumulativePrepared; // how many bytes decrypted and added to _readyBuf
    private long _cumulativePaddingStripped; // how many bytes have been stripped

    /** read but not yet decrypted */
    private byte _encryptedBuf[];
    /** how many bytes have been added to the encryptedBuf since it was decrypted? */
    private int _writesSinceDecrypt;
    /** decrypted bytes ready for reading (first available == index of 0) */
    private int _decryptedBuf[];
    /** how many bytes are available for reading without decrypt? */
    private int _decryptedSize;

    private final static int BLOCK_SIZE = CryptixRijndael_Algorithm._BLOCK_SIZE;

    public AESInputStream(I2PAppContext context, InputStream source, SessionKey key, byte[] iv) {
        super(source);
        _context = context;
        _log = context.logManager().getLog(AESInputStream.class);
        _key = key;
        _lastBlock = new byte[BLOCK_SIZE];
        System.arraycopy(iv, 0, _lastBlock, 0, BLOCK_SIZE);
        _encryptedBuf = new byte[BLOCK_SIZE];
        _writesSinceDecrypt = 0;
        _decryptedBuf = new int[BLOCK_SIZE-1];
        _decryptedSize = 0;
        _cumulativePaddingStripped = 0;
        _eofFound = false;
    }
    
    @Override
    public int read() throws IOException {
        while ((!_eofFound) && (_decryptedSize <= 0)) { 
            refill();
        }
        if (_decryptedSize > 0) {
            int c = _decryptedBuf[0];
            System.arraycopy(_decryptedBuf, 1, _decryptedBuf, 0, _decryptedBuf.length-1);
            _decryptedSize--;
            return c;
        } else if (_eofFound) {
            return -1;
        } else {
            throw new IOException("Not EOF, but none available?  " + _decryptedSize 
                                  + "/" + _writesSinceDecrypt
                                  + "/" + _cumulativeRead + "... impossible");
        }
    }
    
    @Override
    public int read(byte dest[]) throws IOException {
        return read(dest, 0, dest.length);
    }
    
    @Override
    public int read(byte dest[], int off, int len) throws IOException {
        for (int i = 0; i < len; i++) {
            int val = read();
            if (val == -1) {
                // no more to read... can they expect more?
                if (_eofFound && (i == 0)) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.info("EOF? " + _eofFound 
                                  + "\nread=" + i + " decryptedSize=" + _decryptedSize 
                                  + " \nencryptedSize=" + _writesSinceDecrypt 
                                  + " \ntotal=" + _cumulativeRead
                                  + " \npadding=" + _cumulativePaddingStripped
                                  + " \nprepared=" + _cumulativePrepared);
                    return -1;
                } else {
                    if (i != len) 
                        if (_log.shouldLog(Log.DEBUG))
                            _log.info("non-terminal eof: " + _eofFound + " i=" + i + " len=" + len);
                }
                
                return i;
            }
            dest[off+i] = (byte)val;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Read the full buffer of size " + len);
        return len;
    }
    
    @Override
    public long skip(long numBytes) throws IOException {
        for (long l = 0; l < numBytes; l++) {
            int val = read();
            if (val == -1) return l;
        }
        return numBytes;
    }
    
    @Override
    public int available() throws IOException {
        return _decryptedSize;
    }
    
    @Override
    public void close() throws IOException {
        in.close();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Cumulative bytes read from source/decrypted/stripped: " + _cumulativeRead + "/"
                       + _cumulativePrepared + "/" + _cumulativePaddingStripped + "] remaining [" + _decryptedSize + " ready, "
                       + _writesSinceDecrypt + " still encrypted]");
    }
    
    @Override
    public void mark(int readLimit) { // nop
    }
    
    @Override
    public void reset() throws IOException {
        throw new IOException("Reset not supported");
    }
    
    @Override
    public boolean markSupported() {
        return false;
    }

    /**
     * Read at least one new byte from the underlying stream, and up to max new bytes,
     * but not necessarily enough for a new decrypted block.  This blocks until at least
     * one new byte is read from the stream
     *
     */
    private void refill() throws IOException {
        if ( (!_eofFound) && (_writesSinceDecrypt < BLOCK_SIZE) ) {
            int read = in.read(_encryptedBuf, _writesSinceDecrypt, _encryptedBuf.length - _writesSinceDecrypt);
            if (read == -1) {
                _eofFound = true;
            } else if (read > 0) {
                _cumulativeRead += read;
                _writesSinceDecrypt += read;
            }
        }
        if (_writesSinceDecrypt == BLOCK_SIZE) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("We have " + _writesSinceDecrypt + " available to decrypt... doing so");
            decryptBlock();
            if ( (_writesSinceDecrypt > 0) && (_log.shouldLog(Log.DEBUG)) )
                _log.debug("Bytes left in the encrypted buffer after decrypt: "  
                           + _writesSinceDecrypt);
        }
    }

    /**
     * Decrypt the 
     */
    private void decryptBlock() throws IOException {
        if (_writesSinceDecrypt != BLOCK_SIZE)
            throw new IOException("Error decrypting - no data to decrypt");
        
        if (_decryptedSize != 0)
            throw new IOException("wtf, decrypted size is not 0? " + _decryptedSize);
        
        _context.aes().decrypt(_encryptedBuf, 0, _encryptedBuf, 0, _key, _lastBlock, BLOCK_SIZE);
        DataHelper.xor(_encryptedBuf, 0, _lastBlock, 0, _encryptedBuf, 0, BLOCK_SIZE);
        int payloadBytes = countBlockPayload(_encryptedBuf, 0);

        for (int i = 0; i < payloadBytes; i++) {
            int c = _encryptedBuf[i];
            if (c <= 0)
                c += 256;
            _decryptedBuf[i] = c;
        }
        _decryptedSize = payloadBytes;

        _cumulativePaddingStripped += BLOCK_SIZE - payloadBytes;
        _cumulativePrepared += payloadBytes;

        System.arraycopy(_encryptedBuf, 0, _lastBlock, 0, BLOCK_SIZE);
        
        _writesSinceDecrypt = 0;
    }

    /**
     * How many non-padded bytes are there in the block starting at the given
     * location.
     *
     * PKCS#5 specifies the padding for the block has the # of padding bytes
     * located in the last byte of the block, and each of the padding bytes are
     * equal to that value.  
     * e.g. in a 4 byte block:
     *  0x0a padded would become 
     *  0x0a 0x03 0x03 0x03
     * e.g. in a 4 byte block:
     *  0x01 0x02 padded would become 
     *  0x01 0x02 0x02 0x02 
     *
     * We use 16 byte blocks in this AES implementation
     *
     * @throws IOException if the padding is invalid
     */
    private int countBlockPayload(byte data[], int startIndex) throws IOException {
        int numPadBytes = data[startIndex + BLOCK_SIZE - 1];
        if ((numPadBytes >= BLOCK_SIZE) || (numPadBytes <= 0)) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("countBlockPayload on block index " + startIndex 
                           + numPadBytes + " is an invalid # of pad bytes");
            throw new IOException("Invalid number of pad bytes (" + numPadBytes 
                                  + ") for " + startIndex + " index");
        }
        
        // optional, but a really good idea: verify the padding
        if (true) {
            for (int i = BLOCK_SIZE - numPadBytes; i < BLOCK_SIZE; i++) {
                if (data[startIndex + i] != (byte) numPadBytes) { 
                    throw new IOException("Incorrect padding on decryption: data[" + i
                                          + "] = " + data[startIndex + i] + " not " + numPadBytes); 
                }
            }
        }
        
        return BLOCK_SIZE - numPadBytes;
    }

    int remainingBytes() {
        return _writesSinceDecrypt;
    }

    int readyBytes() {
        return _decryptedSize;
    }

    /**
     * Test AESOutputStream/AESInputStream
     */
    public static void main(String args[]) {        
        I2PAppContext ctx = new I2PAppContext();

        try {
            System.out.println("pwd=" + new java.io.File(".").getAbsolutePath());
            System.out.println("Beginning");
            runTest(ctx);
        } catch (Throwable e) {
            ctx.logManager().getLog(AESInputStream.class).error("Fail", e);
        }
        try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
        System.out.println("Done");
    }
    private static void runTest(I2PAppContext ctx) {
        Log log = ctx.logManager().getLog(AESInputStream.class);
        log.setMinimumPriority(Log.DEBUG);
        byte orig[] = new byte[1024 * 32];
        RandomSource.getInstance().nextBytes(orig);
        //byte orig[] = "you are my sunshine, my only sunshine".getBytes();
        SessionKey key = KeyGenerator.getInstance().generateSessionKey();
        byte iv[] = "there once was a".getBytes();

        for (int i = 0; i < 20; i++) {
            runTest(ctx, orig, key, iv);
        }

        log.info("Done testing 32KB data");

        orig = new byte[20];
        RandomSource.getInstance().nextBytes(orig);
        for (int i = 0; i < 20; i++) {
            runTest(ctx, orig, key, iv);
        }

        log.info("Done testing 20 byte data");

        orig = new byte[3];
        RandomSource.getInstance().nextBytes(orig);
        for (int i = 0; i < 20; i++) {
            runTest(ctx, orig, key, iv);
        }

        log.info("Done testing 3 byte data");

        orig = new byte[0];
        RandomSource.getInstance().nextBytes(orig);
        for (int i = 0; i < 20; i++) {
            runTest(ctx, orig, key, iv);
        }

        log.info("Done testing 0 byte data");
  
        for (int i = 0; i <= 32768; i++) {
            orig = new byte[i];
            ctx.random().nextBytes(orig);
            try {
                log.info("Testing " + orig.length);
                runTest(ctx, orig, key, iv);
            } catch (RuntimeException re) {
                log.error("Error testing " + orig.length);
                throw re;
            }
        }
  
/*
        orig = new byte[615280];

        RandomSource.getInstance().nextBytes(orig);
        for (int i = 0; i < 20; i++) {
            runTest(ctx, orig, key, iv);
        }

        log.info("Done testing 615280 byte data");
*/
        /*
        for (int i = 0; i < 100; i++) {
            orig = new byte[ctx.random().nextInt(1024*1024)];
            ctx.random().nextBytes(orig);
            try {
                runTest(ctx, orig, key, iv);
            } catch (RuntimeException re) {
                log.error("Error testing " + orig.length);
                throw re;
            }
        }
         
        log.info("Done testing 100 random lengths");
        */
        
        orig = new byte[32];
        RandomSource.getInstance().nextBytes(orig);
        try {
            runOffsetTest(ctx, orig, key, iv);
        } catch (Exception e) { 
            log.info("Error running offset test", e);
        }

        log.info("Done testing offset test (it should have come back with a statement NOT EQUAL!)");

        try {
            Thread.sleep(30 * 1000);
        } catch (InterruptedException ie) { // nop
        }
    }

    private static void runTest(I2PAppContext ctx, byte orig[], SessionKey key, byte[] iv) {
        Log log = ctx.logManager().getLog(AESInputStream.class);
        try {
            long start = Clock.getInstance().now();
            ByteArrayOutputStream origStream = new ByteArrayOutputStream(512);
            AESOutputStream out = new AESOutputStream(ctx, origStream, key, iv);
            out.write(orig);
            out.close();

            byte encrypted[] = origStream.toByteArray();
            long endE = Clock.getInstance().now();

            ByteArrayInputStream encryptedStream = new ByteArrayInputStream(encrypted);
            AESInputStream sin = new AESInputStream(ctx, encryptedStream, key, iv);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            byte buf[] = new byte[1024 * 32];
            int read = DataHelper.read(sin, buf);
            if (read > 0) baos.write(buf, 0, read);
            sin.close();
            byte fin[] = baos.toByteArray();
            long end = Clock.getInstance().now();
            Hash origHash = SHA256Generator.getInstance().calculateHash(orig);

            Hash newHash = SHA256Generator.getInstance().calculateHash(fin);
            boolean eq = origHash.equals(newHash);
            if (eq) {
                //log.info("Equal hashes.  hash: " + origHash);
            } else {
                throw new RuntimeException("NOT EQUAL!  len=" + orig.length + " read=" + read 
                                           + "\norig: \t" + Base64.encode(orig) + "\nnew : \t" 
                                           + Base64.encode(fin));
            }
            boolean ok = DataHelper.eq(orig, fin);
            log.debug("EQ data? " + ok + " origLen: " + orig.length + " fin.length: " + fin.length);
            log.debug("Time to D(E(" + orig.length + ")): " + (end - start) + "ms");
            log.debug("Time to E(" + orig.length + "): " + (endE - start) + "ms");
            log.debug("Time to D(" + orig.length + "): " + (end - endE) + "ms");

        } catch (IOException ioe) {
            log.error("ERROR transferring", ioe);
        }
        //try { Thread.sleep(5000); } catch (Throwable t) {}
    }

    private static void runOffsetTest(I2PAppContext ctx, byte orig[], SessionKey key, byte[] iv) {
        Log log = ctx.logManager().getLog(AESInputStream.class);
        try {
            long start = Clock.getInstance().now();
            ByteArrayOutputStream origStream = new ByteArrayOutputStream(512);
            AESOutputStream out = new AESOutputStream(ctx, origStream, key, iv);
            out.write(orig);
            out.close();

            byte encrypted[] = origStream.toByteArray();
            long endE = Clock.getInstance().now();

            log.info("Encrypted segment length: " + encrypted.length);
            byte encryptedSegment[] = new byte[40];
            System.arraycopy(encrypted, 0, encryptedSegment, 0, 40);

            ByteArrayInputStream encryptedStream = new ByteArrayInputStream(encryptedSegment);
            AESInputStream sin = new AESInputStream(ctx, encryptedStream, key, iv);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            byte buf[] = new byte[1024 * 32];
            int read = DataHelper.read(sin, buf);
            int remaining = sin.remainingBytes();
            int readyBytes = sin.readyBytes();
            log.info("Read: " + read);
            if (read > 0) baos.write(buf, 0, read);
            sin.close();
            byte fin[] = baos.toByteArray();
            log.info("fin.length: " + fin.length + " remaining: " + remaining + " ready: " + readyBytes);
            long end = Clock.getInstance().now();
            Hash origHash = SHA256Generator.getInstance().calculateHash(orig);

            Hash newHash = SHA256Generator.getInstance().calculateHash(fin);
            boolean eq = origHash.equals(newHash);
            if (eq)
                log.info("Equal hashes.  hash: " + origHash);
            else
                throw new RuntimeException("NOT EQUAL!  len=" + orig.length + "\norig: \t" + Base64.encode(orig) + "\nnew : \t" + Base64.encode(fin));
            boolean ok = DataHelper.eq(orig, fin);
            log.debug("EQ data? " + ok + " origLen: " + orig.length + " fin.length: " + fin.length);
            log.debug("Time to D(E(" + orig.length + ")): " + (end - start) + "ms");
            log.debug("Time to E(" + orig.length + "): " + (endE - start) + "ms");
            log.debug("Time to D(" + orig.length + "): " + (end - endE) + "ms");
        } catch (RuntimeException re) {
            throw re;
        } catch (IOException ioe) {
            log.error("ERROR transferring", ioe);
        }
        //try { Thread.sleep(5000); } catch (Throwable t) {}
    }
}