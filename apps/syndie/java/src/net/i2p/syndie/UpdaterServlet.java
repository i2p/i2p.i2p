package net.i2p.syndie;

import javax.servlet.GenericServlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/**
 * A wrapper for syndie updater to allow it to be started as a web application.
 * 
 * @author Ragnarok
 *
 */
public class UpdaterServlet extends GenericServlet {

    /* (non-Javadoc)
     * @see javax.servlet.Servlet#service(javax.servlet.ServletRequest, javax.servlet.ServletResponse)
     */
    public void service(ServletRequest request, ServletResponse response) {
    }

    /* (non-Javadoc)
     * @see javax.servlet.Servlet#init(javax.servlet.ServletConfig)
     */
    public void init(ServletConfig config) {
        try {
            super.init(config);
        } catch (ServletException exp) {
        }
        /*
        UpdaterThread thread = new UpdaterThread();
        thread.setDaemon(true);
        thread.start();
        System.out.println("INFO: Starting Syndie Updater " + Updater.VERSION);
         */
        System.out.println("INFO: Syndie Updater DISABLED.  Use the new Syndie from http://syndie.i2p.net/");
    }

}