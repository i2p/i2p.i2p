/*
 * I2PHttpRequestHandler.java
 *
 * Created on April 8, 2005, 11:57 PM
 */

package net.i2p.aum.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import net.i2p.client.streaming.I2PSocket;

/**
 *
 * @author  david
 */
public abstract class I2PHttpRequestHandler extends MiniHttpRequestHandler
{
    /** Creates a new instance of I2PHttpRequestHandler */
    public I2PHttpRequestHandler(MiniHttpServer server, Object sock, Object arg) 
        throws Exception
    {
        super(server, sock, arg);
    }

    /** Extracts a readable InputStream from own socket */
    public InputStream getInputStream() throws IOException {
        try {
            return ((I2PSocket)socket).getInputStream();
        } catch (Exception e) {
            return ((Socket)socket).getInputStream();
        }
    }
    
    /** Extracts a writeable OutputStream from own socket */
    public OutputStream getOutputStream() throws IOException {
        try {
            return ((I2PSocket)socket).getOutputStream();
        } catch (Exception e) {
            return ((Socket)socket).getOutputStream();
        }
    }

}
