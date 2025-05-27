package net.i2p.servlet;

import org.eclipse.jetty.ee8.servlet.DefaultServlet;

/**
 *  Extends DefaultServlet to set locale for the displayed time of directory listings,
 *  to prevent leaking of the locale.
 *
 *  @since 0.9.31
 *
 */
public class I2PDefaultServlet extends DefaultServlet
{
    /**
     *  @since Jetty 12
     */
    public I2PDefaultServlet() {
        super(new I2PResourceService());
    }
}
