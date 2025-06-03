package net.i2p.servlet;

import org.eclipse.jetty.ee8.nested.ErrorHandler;

/**
 *  Customize the error page.
 *
 *  @since Jetty 12
 */
public class I2PErrorHandler extends ErrorHandler
{
    public I2PErrorHandler() {
        super();
        setShowServlet(false);
        setShowStacks(false);
    }

    // TODO Overrides

}
