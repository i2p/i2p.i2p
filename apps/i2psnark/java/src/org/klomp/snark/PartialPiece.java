package org.klomp.snark;

/**
 * This is the class passed from PeerCoordinator to PeerState so
 * PeerState may start requests.
 *
 * It is also passed from PeerState to PeerCoordinator when
 * a piece is not completely downloaded, for example
 * when the Peer disconnects or chokes.
 */
class PartialPiece implements Comparable {

    private final int piece;
    private final byte[] bs;
    private final int off;
    private final long createdTime;

    /**
     * Used by PeerCoordinator.
     * Creates a new PartialPiece, with no chunks yet downloaded.
     * Allocates the data.
     *
     * @param piece Piece number requested.
     * @param len must be equal to the piece length
     */
    public PartialPiece (int piece, int len) throws OutOfMemoryError {
        this.piece = piece;
        this.bs = new byte[len];
        this.off = 0;
        this.createdTime = 0;
    }

    /**
     * Used by PeerState.
     * Creates a new PartialPiece, with chunks up to but not including
     * firstOutstandingRequest already downloaded and stored in the Request byte array.
     *
     * Note that this cannot handle gaps; chunks after a missing chunk cannot be saved.
     * That would be harder.
     *
     * @param firstOutstandingRequest the first request not fulfilled for the piece
     */
    public PartialPiece (Request firstOutstandingRequest) {
        this.piece = firstOutstandingRequest.piece;
        this.bs = firstOutstandingRequest.bs;
        this.off = firstOutstandingRequest.off;
        this.createdTime = System.currentTimeMillis();
    }

    /**
     *  Convert this PartialPiece to a request for the next chunk.
     *  Used by PeerState only.
     */

    public Request getRequest() {
        return new Request(this.piece, this.bs, this.off, Math.min(this.bs.length - this.off, PeerState.PARTSIZE));
    }

    /** piece number */
    public int getPiece() {
         return this.piece;
    }

    /** how many bytes are good */
    public int getDownloaded() {
         return this.off;
    }

    public long getCreated() {
         return this.createdTime;
    }

    /**
     *  Highest downloaded first
     */
    public int compareTo(Object o) throws ClassCastException {
        return ((PartialPiece)o).off - this.off;  // reverse
    }
    
    @Override
    public int hashCode() {
        return piece * 7777;
    }

    /**
     *  Make this simple so PeerCoordinator can keep a List.
     *  Warning - compares piece number only!
     */
    @Override
    public boolean equals(Object o) {
        if (o instanceof PartialPiece) {
            PartialPiece pp = (PartialPiece)o;
            return pp.piece == this.piece;
        }
        return false;
    }

    @Override
    public String toString() {
        return "Partial(" + piece + ',' + off + ',' + bs.length + ')';
    }
}
