package net.i2p.servlet;

import java.io.File;

import org.eclipse.jetty.ee8.servlet.ErrorPageErrorHandler;

import net.i2p.I2PAppContext;

/**
 *  Customize the error page.
 *
 *  @since Jetty 12
 */
public class I2PErrorHandler extends ErrorPageErrorHandler
{
    private final File _docroot;

    public I2PErrorHandler() {
        this(new File(I2PAppContext.getGlobalContext().getConfigDir(), "eepsite/docroot"));
    }

    public I2PErrorHandler(String docroot) {
        this(new File(docroot));
    }

    public I2PErrorHandler(File docroot) {
        super();
        _docroot = docroot;
        setShowServlet(false);
        setShowStacks(false);
    }

    // TODO Overrides

}
