package net.i2p.httptunnel;

import java.util.*;
import net.i2p.client.streaming.*;

/**
 * Produces SocketManagers in a thread and gives them to those who
 * need them.
 */
public class SocketManagerProducer extends Thread {

    private ArrayList myManagers = new ArrayList();
    private HashMap usedManagers = new HashMap();
    private int port;
    private String host;
    private int maxManagers;
    private boolean mcDonalds;

    public SocketManagerProducer(I2PSocketManager[] initialManagers,
				 int maxManagers,
				 boolean mcDonaldsMode,
				 String host, int port) {
	if (maxManagers < 1) {
	    throw new IllegalArgumentException("maxManagers < 1");
	}
	this.host=host;
	this.port=port;
	mcDonalds=mcDonaldsMode;
	if (initialManagers != null) {
	    myManagers.addAll(Arrays.asList(initialManagers));
	}
	this.maxManagers=maxManagers;
	mcDonalds=mcDonaldsMode;
	setDaemon(true);
	start();
    }

    
    /**
     * Thread producing new SocketManagers.
     */
    public void run() {
	while (true) {
	    synchronized(this) {
		// without mcDonalds mode, we most probably need no
		// new managers.
		while (!mcDonalds && myManagers.size() == maxManagers) {
		    myWait();
		}
	    }
	    // produce a new manager, regardless whether it is needed
	    // or not. Do not synchronized this part, since it can be
	    // quite time-consuming.
	    I2PSocketManager newManager =
		I2PSocketManagerFactory.createManager(host, port,
						      new Properties());
	    // when done, check if it is needed.
	    synchronized(this) {
		while(myManagers.size() == maxManagers) {
		    myWait();
		}
		myManagers.add(newManager);
		notifyAll();
	    }
	}
    }
    
    /**
     * Get a manager for connecting to a given destination. Each
     * destination will always get the same manager.
     *
     * @param dest the destination to connect to
     * @return the SocketManager to use
     */
    public synchronized I2PSocketManager getManager(String dest) {
	I2PSocketManager result = (I2PSocketManager) usedManagers.get(dest);
	if (result == null) {
	    result = getManager();
	    usedManagers.put(dest,result);
	}
	return result;
    }

    /**
     * Get a "new" SocketManager. Depending on the anonymity settings,
     * this can be a completely new one or one randomly selected from
     * a pool.
     *
     * @return the SocketManager to use
     */
    public synchronized I2PSocketManager getManager() {
	while (myManagers.size() == 0) {
	    myWait(); // no manager here, so wait until one is produced
	}
	int which = (int)(Math.random()*myManagers.size());
	I2PSocketManager result = (I2PSocketManager) myManagers.get(which);
	if (mcDonalds) {
	    myManagers.remove(which);
	    notifyAll();
	}
	return result;
    }

    public void myWait() {
	try {
	    wait();
	} catch (InterruptedException ex) {
	    ex.printStackTrace();
	}
    }
}
