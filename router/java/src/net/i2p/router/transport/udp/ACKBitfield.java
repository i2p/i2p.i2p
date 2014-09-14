package net.i2p.router.transport.udp;

/**
 * Generic means of SACK/NACK transmission for partially or fully 
 * received messages
 */
interface ACKBitfield {

    /** what message is this partially ACKing? */
    public long getMessageId(); 

    /** how many fragments are covered in this bitfield? */
    public int fragmentCount();

    /** has the given fragment been received? */
    public boolean received(int fragmentNum);

    /** has the entire message been received completely? */
    public boolean receivedComplete();

    /**
     *  Number of fragments acked in this bitfield.
     *  Faster than looping through received()
     *  @since 0.9.16
     */
    public int ackCount();

    /**
     *  Highest fragment number acked in this bitfield.
     *  @return highest fragment number acked, or -1 if none
     *  @since 0.9.16
     */
    public int highestReceived();
}
