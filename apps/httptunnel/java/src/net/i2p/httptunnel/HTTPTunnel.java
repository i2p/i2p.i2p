/*
 * HTTPTunnel
 * (c) 2003 - 2004 mihi
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2, or (at
 * your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; see the file COPYING.  If not, write to
 * the Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 *
 * In addition, as a special exception, mihi gives permission to link
 * the code of this program with the proprietary Java implementation
 * provided by Sun (or other vendors as well), and distribute linked
 * combinations including the two. You must obey the GNU General
 * Public License in all respects for all of the code used other than
 * the proprietary Java implementation. If you modify this file, you
 * may extend this exception to your version of the file, but you are
 * not obligated to do so. If you do not wish to do so, delete this
 * exception statement from your version.
 */
package net.i2p.httptunnel;

import net.i2p.client.streaming.I2PSocketManager;

/**
 * HTTPTunnel main class.
 */
public class HTTPTunnel {

    /**
     * Create a HTTPTunnel instance.
     * 
     * @param initialManagers a list of socket managers to use
     * @param maxManagers how many managers to have in the cache
     * @param mcDonaldsMode whether to throw away a manager after use
     * @param listenPort which port to listen on
     */
    public HTTPTunnel(I2PSocketManager[] initialManagers, int maxManagers,
		      boolean mcDonaldsMode, int listenPort) {
	this(initialManagers, maxManagers, mcDonaldsMode, listenPort,
	     "127.0.0.1", 7654);
    }

    /**
     * Create a HTTPTunnel instance.
     * 
     * @param initialManagers a list of socket managers to use
     * @param maxManagers how many managers to have in the cache
     * @param mcDonaldsMode whether to throw away a manager after use
     * @param listenPort which port to listen on
     * @param i2cpAddress the I2CP address
     * @param i2cpPort the I2CP port
     */
    public HTTPTunnel(I2PSocketManager[] initialManagers, int maxManagers,
		      boolean mcDonaldsMode, int listenPort,
		      String i2cpAddress, int i2cpPort) {
	SocketManagerProducer smp = 
	    new SocketManagerProducer(initialManagers, maxManagers,
				      mcDonaldsMode, i2cpAddress, i2cpPort);
	new HTTPListener(smp, listenPort, "127.0.0.1");
    }
    
    /**
     * The all important main function, allowing HTTPTunnel to be 
     * stand-alone, a program in it's own right, and all that jazz.
     * @param args A list of String passed to the program
     */
    public static void main(String[] args) {
	String host = "127.0.0.1";
	int port = 7654, max = 1;
	boolean mc = false;
	if (args.length >1) {
	    if (args.length == 4) {
		host = args[2];
		port = Integer.parseInt(args[3]);
	    } else if (args.length != 2) {
		showInfo(); return;
	    }
	    max = Integer.parseInt(args[1]);
	} else if (args.length != 1) {
	    showInfo(); return;
	}
	if (max == 0) {
	    max = 1;
	} else if (max <0) {
	    max = -max;
	    mc = true;
	}
	new HTTPTunnel(null, max, mc, Integer.parseInt(args[0]), host, port);
    }
	    
    private static void showInfo() {
	System.out.println
	    ("Usage: java HTTPTunnel <listenPort> [<max> "+
	     "[<i2cphost> <i2cpport>]]\n"+
	     "  <listenPort>  port to listen for browsers\n"+
	     "  <max>         max number of SocketMangers in pool, "+
	     "use neg. number\n"+
	     "                to use each SocketManager only once "+
	     "(default: 1)\n"+
	     "  <i2cphost>    host to connect to the router "+
	     "(default: 127.0.0.1)\n"+
	     "  <i2cpport>    port to connect to the router "+
	     "(default: 7654)");
    }
}
