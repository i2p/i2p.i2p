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

import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.NamingServiceUpdater;
import net.i2p.util.I2PAppThread;

/**
 * A thread that waits five minutes, then runs the addressbook daemon.  
 * 
 * @author Ragnarok
 *
 */
public class DaemonThread extends I2PAppThread implements NamingServiceUpdater {

    private final String[] args;
    private final Daemon daemon;

    /**
     * Construct a DaemonThread with the command line arguments args.
     * @param args
     * A String array to pass to Daemon.main().
     */
    public DaemonThread(String[] args) {
        this.args = args;
        daemon = new Daemon();
    }

    /* (non-Javadoc)
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
        //try {
        //    Thread.sleep(5 * 60 * 1000);
        //} catch (InterruptedException exp) {
        //}
        I2PAppContext.getGlobalContext().namingService().registerUpdater(this);
        try {
            if (args != null && args.length > 0 && args[0].equals("test"))
                daemon.test(args);
            else
                daemon.run(args);
        } finally {
            I2PAppContext.getGlobalContext().namingService().unregisterUpdater(this);
        }
    }

    public void halt() {
        daemon.stop();
        interrupt();
    }

    /**
     *  The NamingServiceUpdater interface.
     *  While this may be called directly, the recommended way
     *  is to call I2PAppContext.namingService().requestUpdate(Properties)
     *  which will call this.
     *
     *  @param options ignored, may be null
     *  @since 0.8.7
     */
    public void update(Properties options) {
        interrupt();
    }
}
