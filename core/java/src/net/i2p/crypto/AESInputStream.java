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
import java.util.LinkedList;
import java.util.List;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;
import net.i2p.util.Clock;
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
    private final static Log _log = new Log(AESInputStream.class);
    private final static CryptixAESEngine _engine = new CryptixAESEngine();
    private SessionKey _key;
    private byte[] _lastBlock;
    private boolean _eofFound;
    private long _cumulativeRead; // how many read from the source stream
    private long _cumulativePrepared; // how many bytes decrypted and added to _readyBuf
    private long _cumulativePaddingStripped; // how many bytes have been stripped

    private ByteArrayOutputStream _encryptedBuf; // read from the stream but not yet decrypted
    private List _readyBuf;  // list of Bytes ready to be consumed, where index 0 is the first
    
    private final static int BLOCK_SIZE = CryptixRijndael_Algorithm._BLOCK_SIZE;
    private final static int READ_SIZE = BLOCK_SIZE;
    private final static int DECRYPT_SIZE = BLOCK_SIZE-1;
    
    public AESInputStream(InputStream source, SessionKey key, byte iv[]) {
	super(source);
	_key = key;
	_lastBlock = new byte[BLOCK_SIZE];
	System.arraycopy(iv, 0, _lastBlock, 0, BLOCK_SIZE);
	_encryptedBuf = new ByteArrayOutputStream(BLOCK_SIZE);
	_readyBuf = new LinkedList();
	_cumulativePaddingStripped = 0;
	_eofFound = false;
    }
    
    public int read() throws IOException { 
	while ( (!_eofFound) && (_readyBuf.size() <= 0) ) {
	    refill(READ_SIZE);
	}
	Integer nval = getNext();
	if (nval != null) {
	    return nval.intValue();
	} else {
	    //_log.debug("No byte available.  eof? " + _eofFound);
	    if (_eofFound)
		return -1;
	    else {
		throw new IOException("Not EOF, but none available?  " + _readyBuf.size() + "/" + _encryptedBuf.size() + "/" + _cumulativeRead + "... impossible");
	    }
	}
    }
    
    public int read(byte dest[]) throws IOException {
	for (int i = 0; i < dest.length; i++) {
	    int val = read();
	    if (val == -1) {
		// no more to read... can they expect more?
		if (_eofFound && (i == 0))
		    return -1;
		else
		    return i;
	    } else {
		dest[i] = (byte)val;
	    }
	}
	_log.debug("Read the full buffer of size " + dest.length);
	return dest.length;
    }
    
    public int read(byte dest[], int off, int len) throws IOException { 
	byte buf[] = new byte[len];
	int read = read(buf);
	if (read == -1)
	    return -1;
	System.arraycopy(buf, 0, dest, off, read);
	return read;
    }
    public long skip(long numBytes) throws IOException { 
	for (long l = 0; l < numBytes; l++) {
	    int val = read();
	    if (val == -1)
		return l;
	}
	return numBytes;
    }
    
    public int available() throws IOException { return _readyBuf.size(); }
    public void close() throws IOException { 
	//_log.debug("We have " + _encryptedBuf.size() + " available to decrypt... doing so");
	//decrypt();
	//byte buf[] = new byte[_readyBuf.size()];
	//for (int i = 0; i < buf.length; i++) 
	//    buf[i] = ((Integer)_readyBuf.get(i)).byteValue();
	//_log.debug("After decrypt: readyBuf.size: " + _readyBuf.size() + "\n val:\t" + Base64.encode(buf));
	int ready = _readyBuf.size();
	int encrypted = _readyBuf.size();
	_readyBuf.clear(); 
	_encryptedBuf.reset();
	in.close(); 
	_log.debug("Cumulative bytes read from source/decrypted/stripped: " + _cumulativeRead + "/"+_cumulativePrepared +"/" + _cumulativePaddingStripped + "] remaining [" + ready + " ready, " + encrypted + " still encrypted]");
    }

    public void mark(int readLimit) {}
    public void reset() throws IOException { throw new IOException("Reset not supported"); }
    public boolean markSupported() { return false; }
    
    /**
     * Retrieve the next ready byte, or null if no bytes are ready.  this does not refill or block
     *
     */
    private Integer getNext() {
	if (_readyBuf.size() > 0) {
	    return (Integer)_readyBuf.remove(0);
	} else {
	    return null;
	}
    }
    
    /**
     * Read at least one new byte from the underlying stream, and up to max new bytes,
     * but not necessarily enough for a new decrypted block.  This blocks until at least
     * one new byte is read from the stream
     *
     */
    private void refill(int max) throws IOException {
	if (!_eofFound) {
	    byte buf[] = new byte[max];
	    int read = in.read(buf);
	    if (read == -1) {
		_eofFound = true;
	    } else if (read > 0) {
		//_log.debug("Read from the source stream " + read + " bytes");
		_cumulativeRead += read;
		_encryptedBuf.write(buf, 0, read);
	    }
	}
	if (false) return; // true to keep the data for decrypt/display on close
	if (_encryptedBuf.size() > 0) {
	    if (_encryptedBuf.size() >= DECRYPT_SIZE) {
		//_log.debug("We have " + _encryptedBuf.size() + " available to decrypt... doing so");
		decrypt();
		//if (_encryptedBuf.size() > 0)
		//    _log.debug("Bytes left in the encrypted buffer after decrypt: "  + _encryptedBuf.size());
	    } else {
		if (_eofFound) {
		    //_log.debug("EOF and not enough bytes to decrypt [size = " + _encryptedBuf.size() + " totalCumulative: " + _cumulativeRead + "/"+_cumulativePrepared +"]!");
		} else {
		    //_log.debug("Not enough bytes to decrypt [size = " + _encryptedBuf.size() + " totalCumulative: " + _cumulativeRead + "/"+_cumulativePrepared +"]");
		}
	    }
	}
    }

    /**
     * Take (n*BLOCK_SIZE) bytes off the _encryptedBuf, decrypt them, and place
     * them on _readyBuf
     *
     */
    private void decrypt() throws IOException {
	byte encrypted[] = _encryptedBuf.toByteArray();
	_encryptedBuf.reset();
	
	if ( (encrypted == null) || (encrypted.length <= 0) )
	    throw new IOException("Error decrypting - no data to decrypt");
	
	int numBlocks = encrypted.length / BLOCK_SIZE;
	if ( (encrypted.length % BLOCK_SIZE) != 0) {
	    // it was flushed / handled off the BLOCK_SIZE segments, so put the excess 
	    // back into the _encryptedBuf for later handling
	    int trailing = encrypted.length % BLOCK_SIZE;
	    _encryptedBuf.write(encrypted, encrypted.length - trailing, trailing);
	    byte nencrypted[] = new byte[encrypted.length - trailing];
	    System.arraycopy(encrypted, 0, nencrypted, 0, nencrypted.length);
	    encrypted = nencrypted;
	    _log.warn("Decrypt got odd segment - " + trailing + " bytes pushed back for later decryption - corrupted or slow data stream perhaps?");
	} else {
	    //_log.info(encrypted.length + " bytes makes up " + numBlocks + " blocks to decrypt normally");
	}
	
	byte block[] = new byte[BLOCK_SIZE];
	for (int i = 0; i < numBlocks; i++) {
	    System.arraycopy(encrypted, i*BLOCK_SIZE, block, 0, BLOCK_SIZE);
	    byte decrypted[] = _engine.decrypt(block, _key, _lastBlock);
	    byte data[] = CryptixAESEngine.xor(decrypted, _lastBlock);
	    int cleaned[] = stripPadding(data);
	    for (int j = 0; j < cleaned.length; j++) {
		if ( ((int)cleaned[j]) <= 0) {
		    cleaned[j] += 256;
		    //_log.error("(modified: " + cleaned[j] + ")");
		}
		_readyBuf.add(new Integer(cleaned[j]));
	    }
	    _cumulativePrepared += cleaned.length;
	    //_log.debug("Updating last block for inputStream");
	    System.arraycopy(decrypted, 0, _lastBlock, 0, BLOCK_SIZE);
	}
	
	int remaining = encrypted.length % BLOCK_SIZE;
	if (remaining != 0) {
	    _encryptedBuf.write(encrypted, encrypted.length-remaining, remaining);
	    _log.debug("After pushing " + remaining + " bytes back onto the buffer, lets delay 1s our action so we don't fast busy until the net transfers data");
	    try { Thread.sleep(1000); } catch (InterruptedException ie) {}
	} else {
	    //_log.debug("No remaining encrypted bytes beyond the block size");
	}
    }

    /**
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
     */
    private int[] stripPadding(byte data[]) throws IOException {
	int numPadBytes = (int)data[data.length-1];
	if ( (numPadBytes >= data.length) || (numPadBytes <= 0) )
	    throw new IOException("Invalid number of pad bytes");
	int rv[] = new int[data.length-numPadBytes];
	// optional, but a really good idea: verify the padding
	if (true) {
	    for (int i = data.length - numPadBytes; i < data.length; i++) {
		if (data[i] != (byte)numPadBytes) {
		    throw new IOException("Incorrect padding on decryption: data["+i+"] = " + data[i] + " not " + numPadBytes);
		}
	    }
	}
	for (int i = 0; i < rv.length; i++)
	    rv[i] = data[i];
	_cumulativePaddingStripped += numPadBytes;
	return rv;
    }
    
    int remainingBytes() { return _encryptedBuf.size(); }
    int readyBytes() { return _readyBuf.size(); }
    
    /**
     * Test AESOutputStream/AESInputStream
     */
    public static void main(String args[]) {
	byte orig[] = new byte[1024*32];
	RandomSource.getInstance().nextBytes(orig);
	//byte orig[] = "you are my sunshine, my only sunshine".getBytes();
	SessionKey key = KeyGenerator.getInstance().generateSessionKey();
	byte iv[] = "there once was a".getBytes();
	
	for (int i = 0; i < 20; i++) {
	    runTest(orig, key, iv);
	}
	
	_log.info("Done testing 32KB data");
	
	orig = new byte[20];
	RandomSource.getInstance().nextBytes(orig);
	for (int i = 0; i < 20; i++) {
	    runTest(orig, key, iv);
	}
	
	_log.info("Done testing 20 byte data");
	
	orig = new byte[3];
	RandomSource.getInstance().nextBytes(orig);
	for (int i = 0; i < 20; i++) {
	    runTest(orig, key, iv);
	}
	
	_log.info("Done testing 3 byte data");
	
	orig = new byte[0];
	RandomSource.getInstance().nextBytes(orig);
	for (int i = 0; i < 20; i++) {
	    runTest(orig, key, iv);
	}
	
	_log.info("Done testing 0 byte data");
	
	orig = new byte[32];
	RandomSource.getInstance().nextBytes(orig);
	runOffsetTest(orig, key, iv);
	
	_log.info("Done testing offset test (it should have come back with a statement NOT EQUAL!)");
	
	try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
    }
    
    private static void runTest(byte orig[], SessionKey key, byte[] iv) {
	try {
	    long start = Clock.getInstance().now();
	    ByteArrayOutputStream origStream = new ByteArrayOutputStream(512);
	    AESOutputStream out = new AESOutputStream(origStream, key, iv);
	    out.write(orig);
	    out.close();

	    byte encrypted[] = origStream.toByteArray();
	    long endE = Clock.getInstance().now();
	    
	    ByteArrayInputStream encryptedStream = new ByteArrayInputStream(encrypted);
	    AESInputStream in = new AESInputStream(encryptedStream, key, iv);
	    ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
	    byte buf[] = new byte[1024*32];
	    int read = DataHelper.read(in, buf);
	    if (read > 0)
		baos.write(buf, 0, read);
	    in.close();
	    byte fin[] = baos.toByteArray();
	    long end = Clock.getInstance().now();
	    Hash origHash = SHA256Generator.getInstance().calculateHash(orig);
	    
	    Hash newHash = SHA256Generator.getInstance().calculateHash(fin);
	    boolean eq = origHash.equals(newHash);
	    if (eq)
		_log.info("Equal hashes.  hash: " + origHash);
	    else
		_log.error("NOT EQUAL!  \norig: \t" + Base64.encode(orig) + "\nnew : \t" + Base64.encode(fin));
	    boolean ok = DataHelper.eq(orig, fin);
	    _log.debug("EQ data? " + ok + " origLen: " + orig.length + " fin.length: " + fin.length);
	    _log.debug("Time to D(E(" + orig.length + ")): " + (end - start) + "ms");
	    _log.debug("Time to E(" + orig.length + "): " + (endE - start) + "ms");
	    _log.debug("Time to D(" + orig.length + "): " + (end - endE) + "ms");
	    
	} catch (Throwable t) {
	    _log.error("ERROR transferring", t);
	}
	//try { Thread.sleep(5000); } catch (Throwable t) {}
    }
    
    private static void runOffsetTest(byte orig[], SessionKey key, byte[] iv) {
	try {
	    long start = Clock.getInstance().now();
	    ByteArrayOutputStream origStream = new ByteArrayOutputStream(512);
	    AESOutputStream out = new AESOutputStream(origStream, key, iv);
	    out.write(orig);
	    out.close();

	    byte encrypted[] = origStream.toByteArray();
	    long endE = Clock.getInstance().now();
	    
	    _log.info("Encrypted segment length: " + encrypted.length);
	    byte encryptedSegment[] = new byte[40];
	    System.arraycopy(encrypted, 0, encryptedSegment, 0, 40);
	    
	    ByteArrayInputStream encryptedStream = new ByteArrayInputStream(encryptedSegment);
	    AESInputStream in = new AESInputStream(encryptedStream, key, iv);
	    ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
	    byte buf[] = new byte[1024*32];
	    int read = DataHelper.read(in, buf);
	    int remaining = in.remainingBytes();
	    int readyBytes = in.readyBytes();
	    _log.info("Read: " + read);
	    if (read > 0)
		baos.write(buf, 0, read);
	    in.close();
	    byte fin[] = baos.toByteArray();
	    _log.info("fin.length: " + fin.length + " remaining: " + remaining + " ready: " + readyBytes);
	    long end = Clock.getInstance().now();
	    Hash origHash = SHA256Generator.getInstance().calculateHash(orig);
	    
	    Hash newHash = SHA256Generator.getInstance().calculateHash(fin);
	    boolean eq = origHash.equals(newHash);
	    if (eq)
		_log.info("Equal hashes.  hash: " + origHash);
	    else
		_log.error("NOT EQUAL!  \norig: \t" + Base64.encode(orig) + "\nnew : \t" + Base64.encode(fin));
	    boolean ok = DataHelper.eq(orig, fin);
	    _log.debug("EQ data? " + ok + " origLen: " + orig.length + " fin.length: " + fin.length);
	    _log.debug("Time to D(E(" + orig.length + ")): " + (end - start) + "ms");
	    _log.debug("Time to E(" + orig.length + "): " + (endE - start) + "ms");
	    _log.debug("Time to D(" + orig.length + "): " + (end - endE) + "ms");
	    
	} catch (Throwable t) {
	    _log.error("ERROR transferring", t);
	}
	//try { Thread.sleep(5000); } catch (Throwable t) {}
    }
}