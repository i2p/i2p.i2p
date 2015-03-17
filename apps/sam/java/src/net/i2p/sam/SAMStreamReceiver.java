package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.nio.ByteBuffer;

import net.i2p.data.Destination;

/**
 * Interface for sending streaming data to a SAM client
 */

public interface SAMStreamReceiver {
    /**
     * Sends the result of a stream send operation
     * @param id Stream ID
     * @param result information
     * @param bufferState state of the buffer
     * @throws IOException
     */
    public void streamSendAnswer( int id, String result, String bufferState ) throws IOException;
	
    /**
     * Notifies that the outwards buffer is free for writing
     * @param id stream ID
     * @throws IOException
     */
    public void notifyStreamSendBufferFree( int id ) throws IOException;

    /**
     * Notify about a new incoming connection
     *
     * @param id New connection id
     * @param dest Destination
     * @throws IOException 
     */
    public void notifyStreamIncomingConnection ( int id, Destination dest ) throws IOException;

    /**
     * Notify about a new outgoing connection
     *
     * @param id New connection id
     * @param result message result
     * @param msg Message
     * @throws IOException 
     */
    public void notifyStreamOutgoingConnection(int id, String result, String msg) throws IOException;

    /**
     * Transmit a byte array from I2P to a SAM client.
     *
     * @param id Connection id
     * @param data Byte array to be received
     * @throws IOException 
     */
    public void receiveStreamBytes(int id, ByteBuffer data) throws IOException;

    /**
     * Notify that a connection has been closed
     * FIXME: this interface should be cleaner
     *
     * @param id Connection id
     * @param result Disconnection reason ("OK" or something else)
     * @param msg Error message, if any
     * @throws IOException 
     */
    public void notifyStreamDisconnection(int id, String result, String msg) throws IOException;

    /**
     * Stop receiving data.
     *
     */
    public void stopStreamReceiving();
}
