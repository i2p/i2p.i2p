/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.util.Set;

import net.i2p.client.I2PSession;
import net.i2p.util.EventDispatcher;
import net.i2p.util.EventDispatcherImpl;

/**
 * Either a Server or a Client.
 */

public abstract class I2PTunnelTask extends EventDispatcherImpl {

    private int id;
    private String name;
    protected boolean open;
    private I2PTunnel tunnel;

    //protected I2PTunnelTask(String name) {
    //	I2PTunnelTask(name, (EventDispatcher)null);
    //}

    protected I2PTunnelTask(String name, EventDispatcher notifyThis, I2PTunnel tunnel) {
        attachEventDispatcher(notifyThis);
        this.name = name;
        this.id = -1;
        this.tunnel = tunnel;
    }

    /** for apps that use multiple I2PTunnel instances */
    public void setTunnel(I2PTunnel pTunnel) {
        tunnel = pTunnel;
    }
    
    public I2PTunnel getTunnel() { return tunnel; }

    public int getId() {
        return this.id;
    }

    public boolean isOpen() {
        return open;
    }

    public void setId(int id) {
        this.id = id;
    }

    protected void setName(String name) {
        this.name = name;
    }

    protected void routerDisconnected() {
        tunnel.routerDisconnected();
    }

    public abstract boolean close(boolean forced);

    public void disconnected(I2PSession session) {
        routerDisconnected();
        getTunnel().removeSession(session);
    }

    public void errorOccurred(I2PSession session, String message, Throwable error) {
    }

    public void reportAbuse(I2PSession session, int severity) {
    }

    @Override
    public String toString() {
        return name;
    }
}
