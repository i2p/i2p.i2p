package net.i2p.servlet;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!response.containsHeader("X-Content-Type-Options"))
            response.setHeader("X-Content-Type-Options", "nosniff");
        if (!response.containsHeader("Referrer-Policy"))
            response.setHeader("Referrer-Policy", "no-referrer");
        super.service(request, response);
    }
}
