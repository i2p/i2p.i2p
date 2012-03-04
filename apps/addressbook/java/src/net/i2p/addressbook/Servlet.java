/*
 * Copyright (c) 2004 Ragnarok
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.i2p.addressbook;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Random;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A wrapper for addressbook to allow it to be started as a web application.
 * 
 * This was a GenericServlet, we make it an HttpServlet solely to provide a hook
 * for SusiDNS to wake us up when the subscription list changes.
 * 
 * @author Ragnarok
 *
 */
public class Servlet extends HttpServlet {
    private DaemonThread thread;
    private String nonce;
    private static final String PROP_NONCE = "addressbook.nonce";

    /**
     * Hack to allow susidns to kick the daemon when the subscription list changes.
     * URL must be /addressbook/ with wakeup param set, and nonce param set from system property.
     *
     * (non-Javadoc)
     * see javax.servlet.Servlet#service(javax.servlet.ServletRequest, javax.servlet.ServletResponse)
     */
    public void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        //System.err.println("Got request nonce = " + request.getParameter("nonce"));
        if (this.thread != null && request.getParameter("wakeup") != null &&
            this.nonce != null && this.nonce.equals(request.getParameter("nonce"))) {
            //System.err.println("Sending interrupt");
            this.thread.interrupt();
            // no output
        } else {
            PrintWriter out = response.getWriter();
            out.write("I2P addressbook OK");
        }
    }

    /* (non-Javadoc)
     * @see javax.servlet.Servlet#init(javax.servlet.ServletConfig)
     */
    @Override
    public void init(ServletConfig config) {
        try {
            super.init(config);
        } catch (ServletException exp) {
            System.err.println("Addressbook init exception: " + exp);
        }
        this.nonce = "" + Math.abs((new Random()).nextLong());
        // put the nonce where susidns can get it
        System.setProperty(PROP_NONCE, this.nonce);
        String[] args = new String[1];
        args[0] = config.getInitParameter("home");
        this.thread = new DaemonThread(args);
        this.thread.setDaemon(true);
        this.thread.setName("Addressbook");
        this.thread.start();
        //System.out.println("INFO: Starting Addressbook " + Daemon.VERSION);
        //System.out.println("INFO: config root under " + args[0]);
    }

    @Override
    public void destroy() {
        this.thread.halt();
        super.destroy();
    }
}
