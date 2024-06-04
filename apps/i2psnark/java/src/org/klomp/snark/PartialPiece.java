package org.klomp.snark;

import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA1;
import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;

/**
 * Store the received data either on the heap or in a temp file.
 * The third option, to write chunks directly to the destination file,
 * is unimplemented.
 *
 * This is the class passed from PeerCoordinator to PeerState so
 * PeerState may start requests.
 *
 * It is also passed from PeerState to PeerCoordinator when
 * a piece is not completely downloaded, for example
 * when the Peer disconnects or chokes.
 *
 * New objects for the same piece are created during the end game -
 * this object should not be shared among multiple peers.
 *
 * @since 0.8.2
 */
class PartialPiece implements Comparable<PartialPiece> {

    // we store the piece so we can use it in compareTo()
    private final Piece piece;
    // null if using temp file
    private final byte[] bs;
    private int off;
    //private final long createdTime;
    private File tempfile;
    private RandomAccessFile raf;
    private final int pclen;
    private final File tempDir;
    private final BitField bitfield;

    private static final int BUFSIZE = PeerState.PARTSIZE;
    private static final ByteCache _cache = ByteCache.getInstance(16, BUFSIZE);

    // Any bigger than this, use temp file instead of heap
    private static final int MAX_IN_MEM = 128 * 1024;
    // May be reduced on OOM
    private static int _max_in_mem = MAX_IN_MEM;

    /**
     * Used by PeerCoordinator.
     * Creates a new PartialPiece, with no chunks yet downloaded.
     * Allocates the data storage area, either on the heap or in the
     * temp directory, depending on size.
     *
     * @param piece Piece number requested.
     * @param len must be equal to the piece length
     */
    public PartialPiece (Piece piece, int len, File tempDir) {
        this.piece = piece;
        this.pclen = len;
        //this.createdTime = 0;
        this.tempDir = tempDir;
        bitfield = new BitField((len + PeerState.PARTSIZE - 1) / PeerState.PARTSIZE);

        // temps for finals
        byte[] tbs = null;
        try {
            if (len <= MAX_IN_MEM) {
                try {
                    tbs = new byte[len];
                    return;
                } catch (OutOfMemoryError oom) {
                    if (_max_in_mem > PeerState.PARTSIZE)
                        _max_in_mem /= 2;
                    Log log = I2PAppContext.getGlobalContext().logManager().getLog(PartialPiece.class);
                    log.logAlways(Log.WARN, "OOM creating new partial piece");
                    // fall through to use temp file
                }
            }
            // delay creating temp file until required in read()
        } finally {
            // finals
            this.bs = tbs;
        }
    }

    /**
     *  Caller must synchronize
     *
     *  @since 0.9.1
     */
    private void createTemp() throws IOException {
        //tfile = SecureFile.createTempFile("piece", null, tempDir);
        // debug
        tempfile = SecureFile.createTempFile("piece_" + piece.getId() + '_', null, tempDir);
        raf = new RandomAccessFile(tempfile, "rw");
    }

    /**
     *  Convert this PartialPiece to a request for the next chunk.
     *  Used by PeerState only.
     *
     *  @return null if complete
     */
    public synchronized Request getRequest() {
        int chunk = off / PeerState.PARTSIZE;
        int sz = bitfield.size();
        for (int i = chunk; i < sz; i++) {
            if (!bitfield.get(i))
                return new Request(this, off, Math.min(pclen - off, PeerState.PARTSIZE));
            if (i == sz - 1)
                off = pclen;
            else
                off += PeerState.PARTSIZE;
        }
        return null;
    }

    /** piece number */
    public int getPiece() {
         return this.piece.getId();
    }

    /**
     *  @since 0.9.1
     */
    public int getLength() {
         return this.pclen;
    }

    /**
     *  @since 0.9.62
     */
    public synchronized boolean isComplete() {
        return bitfield.complete();
    }

    /**
     *  Have any chunks been downloaded?
     *
     *  @since 0.9.63
     */
    public synchronized boolean hasData() {
        return bitfield.count() > 0;
    }

    /**
     *  Has this chunk been downloaded?
     *
     *  @since 0.9.63
     */
    public synchronized boolean hasChunk(int chunk) {
        return bitfield.get(chunk);
    }

    /**
     *  How many bytes are good - as set by read().
     *  As of 0.9.63, accurately counts good bytes after "holes".
     */
    public synchronized int getDownloaded() {
        if (bitfield.complete())
            return pclen;
        int sz = bitfield.count();
        int rv = sz * PeerState.PARTSIZE;
        int rem = pclen % PeerState.PARTSIZE;
        if (rem != 0 && bitfield.get(sz - 1))
            rv -= PeerState.PARTSIZE - rem;
        return rv;
    }

    /**
     *  Piece must be complete.
     *  The SHA1 hash of the completely read data.
     *  @since 0.9.1
     */
    public byte[] getHash() throws IOException {
        MessageDigest sha1 = SHA1.getInstance();
        if (bs != null) {
            sha1.update(bs);
        } else {
            int read = 0;
            int buflen = Math.min(pclen, BUFSIZE);
            ByteArray ba;
            byte[] buf;
            if (buflen == BUFSIZE) {
                ba = _cache.acquire();
                buf = ba.getData();
            } else {
                ba = null;
                buf = new byte[buflen];
            }
            synchronized (this) {
                if (raf == null)
                    throw new IOException();
                raf.seek(0);
                while (read < pclen) {
                    int rd = raf.read(buf, 0, Math.min(buf.length, pclen - read));
                    if (rd < 0)
                        break;
                    read += rd;
                    sha1.update(buf, 0, rd);
                }
            }
            if (ba != null)
                _cache.release(ba, false);
            if (read < pclen)
                throw new IOException();
        }
        return sha1.digest();
    }
    
    /**
     *  Blocking.
     *  If offset matches the previous downloaded amount
     *  (as set by a previous call to read() or setDownlaoded()),
     *  the downloaded amount will be incremented by len.
     *
     *  @since 0.9.1
     */
    public void read(DataInputStream din, int offset, int len, BandwidthListener bwl) throws IOException {
        if (offset % PeerState.PARTSIZE != 0)
            throw new IOException("Bad offset " + offset);
        int chunk = offset / PeerState.PARTSIZE;
        // We read the data before checking if we have the chunk,
        // because otherwise we'd have to break the peer connection
        if (bs != null) {
            // Don't use readFully() so we may update the BandwidthListener as we go
            //in.readFully(bs, offset, len);
            int offs = offset;
            int toRead = len;
            while (toRead > 0) {
                int numRead = din.read(bs, offs, toRead);
                if (numRead < 0)
                    throw new EOFException();
                offs += numRead;
                toRead -= numRead;
                bwl.downloaded(numRead);
            }
            synchronized (this) {
                if (bitfield.get(chunk)) {
                    warn("Already have chunk " + chunk + " on " + this);
                } else {
                    bitfield.set(chunk);
                    if (this.off == offset) {
                        this.off += len;
                        // if this filled in a hole, advance off
                        int sz = bitfield.size();
                        for (int i = chunk + 1; i < sz; i++) {
                            if (!bitfield.get(i))
                                break;
                            warn("Hole filled in before chunk " + i + " on " + this + ' ' + bitfield);
                            if (i == sz - 1)
                                off = pclen;
                            else
                                off += PeerState.PARTSIZE;
                        }
                    } else {
                        warn("Out of order chunk " + chunk + " on " + this + ' ' + bitfield);
                    }
                }
            }
        } else {
            // read in fully before synching on raf
            ByteArray ba;
            byte[] tmp;
            if (len == BUFSIZE) {
                ba = _cache.acquire();
                tmp = ba.getData();
            } else {
                ba = null;
                tmp = new byte[len];
            }

            // Don't use readFully() so we may update the BandwidthListener as we go
            //din.readFully(tmp);
            int offs = 0;
            int toRead = len;
            while (toRead > 0) {
                int numRead = din.read(tmp, offs, toRead);
                if (numRead < 0)
                    throw new EOFException();
                offs += numRead;
                toRead -= numRead;
                bwl.downloaded(numRead);
            }

            synchronized (this) {
                if (bitfield.get(chunk)) {
                    warn("Already have chunk " + chunk + " on " + this);
                } else {
                    if (raf == null)
                        createTemp();
                    raf.seek(offset);
                    raf.write(tmp);
                    bitfield.set(chunk);
                    if (this.off == offset) {
                        this.off += len;
                        // if this filled in a hole, advance off
                        int sz = bitfield.size();
                        for (int i = chunk + 1; i < sz; i++) {
                            if (!bitfield.get(i))
                                break;
                            warn("Hole filled in before chunk " + i + " on " + this + ' ' + bitfield);
                            if (i == sz - 1)
                                off = pclen;
                            else
                                off += PeerState.PARTSIZE;
                        }
                    } else {
                        warn("Out of order chunk " + chunk + " on " + this + ' ' + bitfield);
                    }
                }
            }
            if (ba != null)
                _cache.release(ba, false);
        }
    }

    /**
     *  Piece must be complete.
     *  Caller must synchronize on out and seek to starting point.
     *  Caller must call release() when done with the whole piece.
     *
     *  @param out stream to write to
     *  @param offset offset in the piece
     *  @param len length to write
     *  @since 0.9.1
     */
    public void write(DataOutput out, int offset, int len) throws IOException {
        if (bs != null) {
            out.write(bs, offset, len);
        } else {
            int read = 0;
            int buflen = Math.min(len, BUFSIZE);
            ByteArray ba;
            byte[] buf;
            if (buflen == BUFSIZE) {
                ba = _cache.acquire();
                buf = ba.getData();
            } else {
                ba = null;
                buf = new byte[buflen];
            }
            synchronized (this) {
                if (raf == null)
                    throw new IOException();
                raf.seek(offset);
                while (read < len) {
                    int rd = Math.min(buf.length, len - read);
                    raf.readFully(buf, 0, rd);
                    read += rd;
                    out.write(buf, 0, rd);
                }
            }
            if (ba != null)
                _cache.release(ba, false);
        }
    }
    
    /**
     *  Release all resources.
     *
     *  @since 0.9.1
     */
    public void release() {
        if (bs == null) {
            synchronized (this) {
                if (raf != null) {
                    locked_release();
                    raf = null;
                }
            }
            //if (raf != null)
            //    I2PAppContext.getGlobalContext().logManager().getLog(PartialPiece.class).warn("Released " + tempfile);
        }
    }
    
    /**
     *  Caller must synchronize
     *
     *  @since 0.9.1
     */
    private void locked_release() {
        try {
            raf.close();
        } catch (IOException ioe) {
        }
        tempfile.delete();
    }

    /*
     *  Highest priority first,
     *  then rarest first,
     *  then highest downloaded first
     */
    public int compareTo(PartialPiece opp) {
        int d = this.piece.compareTo(opp.piece);
        if (d != 0)
            return d;
        return opp.getDownloaded() - getDownloaded();  // reverse
    }
    
    @Override
    public int hashCode() {
        return piece.getId() * 7777;
    }

    /**
     *  Make this simple so PeerCoordinator can keep a List.
     *  Warning - compares piece number only!
     */
    @Override
    public boolean equals(Object o) {
        if (o instanceof PartialPiece) {
            PartialPiece pp = (PartialPiece)o;
            return pp.piece.getId() == this.piece.getId();
        }
        return false;
    }

    @Override
    public String toString() {
        return "Partial(" + piece.getId() + ',' + off + ',' + getDownloaded() + ',' + pclen + ')';
    }

    /**
     *  @since 0.9.62
     */
    public static void warn(String s) {
        I2PAppContext.getGlobalContext().logManager().getLog(PartialPiece.class).warn(s);
    }
}
