package org.klomp.snark;

import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA1;
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
class PartialPiece implements Comparable {

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
        //I2PAppContext.getGlobalContext().logManager().getLog(PartialPiece.class).warn("Created " + tempfile);
        // tfile.deleteOnExit() ???
        raf = new RandomAccessFile(tempfile, "rw");
        // Do not preallocate the file space.
        // Not necessary to call setLength(), file is extended when written
        //traf.setLength(len);
    }

    /**
     *  Convert this PartialPiece to a request for the next chunk.
     *  Used by PeerState only.
     */

    public Request getRequest() {
        return new Request(this, this.off, Math.min(this.pclen - this.off, PeerState.PARTSIZE));
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
     *  How many bytes are good - only valid by setDownloaded()
     */
    public int getDownloaded() {
         return this.off;
    }

    /**
     *  Call this before returning a PartialPiece to the PeerCoordinator
     *  @since 0.9.1
     */
    public void setDownloaded(int offset) {
         this.off = offset;
    }

/****
    public long getCreated() {
         return this.createdTime;
    }
****/

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
            byte[] buf = new byte[Math.min(pclen, 16384)];
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
            if (read < pclen)
                throw new IOException();
        }
        return sha1.digest();
    }
    
    /**
     *  Blocking.
     *  @since 0.9.1
     */
    public void read(DataInputStream din, int off, int len) throws IOException {
        if (bs != null) {
            din.readFully(bs, off, len);
        } else {
            // read in fully before synching on raf
            byte[] tmp = new byte[len];
            din.readFully(tmp);
            synchronized (this) {
                if (raf == null)
                    createTemp();
                raf.seek(off);
                raf.write(tmp);
            }
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
            byte[] buf = new byte[Math.min(len, 16384)];
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
                if (raf != null)
                    locked_release();
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
            I2PAppContext.getGlobalContext().logManager().getLog(PartialPiece.class).warn("Error closing " + raf, ioe);
        }
        tempfile.delete();
    }

    /*
     *  Highest priority first,
     *  then rarest first,
     *  then highest downloaded first
     */
    public int compareTo(Object o) throws ClassCastException {
        PartialPiece opp = (PartialPiece)o;
        int d = this.piece.compareTo(opp.piece);
        if (d != 0)
            return d;
        return opp.off - this.off;  // reverse
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
        return "Partial(" + piece.getId() + ',' + off + ',' + pclen + ')';
    }
}
