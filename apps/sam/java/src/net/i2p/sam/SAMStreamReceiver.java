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

import net.i2p.data.Destination;

/**
 * Interface for sending streaming data to a SAM client
 */
public interface SAMStreamReceiver {

    /**
     * Notify about a new incoming connection
     *
     * @param id New connection id
     */
    public void notifyStreamConnection(int id, Destination dest) throws IOException;

    /**
     * Send a byte array to a SAM client.
     *
     * @param id Connection id
     * @param data Byte array to be received
     * @param len Number of bytes in data
     */
    public void receiveStreamBytes(int id, byte data[], int len) throws IOException;

    /**
     * Notify that a connection has been closed
     * FIXME: this interface should be cleaner
     *
     * @param id Connection id
     * @param result Disconnection reason ("OK" or something else)
     * @param msg Error message, if any
     */
    public void notifyStreamDisconnection(int id, String result, String msg) throws IOException;

    /**
     * Stop receiving data.
     *
     */
    public void stopStreamReceiving();
}
