/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.util.Properties;

import net.i2p.client.I2PSession;
import net.i2p.util.EventDispatcher;
import net.i2p.util.EventDispatcherImpl;

/**
 *  Either a Server or a Client.
 *
 *  Use caution if extending externally.
 *  This class should be maintained as a stable API,
 *  but ask to be sure.
 *
 *  Note that there is no startRunning() method,
 *  however all extending classes implement one.
 */
public abstract class I2PTunnelTask extends EventDispatcherImpl {

    private int id;
    private String name;
    protected volatile boolean open;
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

    /**
     *  Note that the tunnel can be reopened after this by calling startRunning().
     *  This may not release all resources. In particular, the I2PSocketManager remains
     *  and it may have timer threads that continue running.
     *
     *  To release all resources permanently, call destroy().
     *
     *  @return success
     */
    public abstract boolean close(boolean forced);

    /**
     *  Note that the tunnel cannot be reopened after this by calling startRunning(),
     *  as it may destroy the underlying socket manager, depending on implementation.
     *  This should release all resources.
     *
     *  The implementation here simply calls close(true).
     *  Extending classes should override to release all resources.
     *
     *  @return success
     *  @since 0.9.17
     */
    public boolean destroy() {
        return close(true);
    }

    /**
     *  Notify the task that I2PTunnel's options have been updated.
     *  Extending classes should override and call I2PTunnel.getClientOptions(),
     *  then update the I2PSocketManager.
     *  Does nothing here.
     *
     *  @since 0.9.1
     */
    public void optionsUpdated(I2PTunnel tunnel) {}

    /**
     *  For tasks that don't call I2PTunnel.addSession() directly
     *  @since 0.8.13
     */
    public void connected(I2PSession session) {
        getTunnel().addSession(session);
    }

    public void disconnected(I2PSession session) {
        routerDisconnected();
        getTunnel().removeSession(session);
    }

    /**
     *  @since 0.9.62
     */
    protected boolean getBooleanOption(String opt, boolean dflt) {
        Properties opts = getTunnel().getClientOptions();
        String o = opts.getProperty(opt);
        if (o != null)
            return Boolean.parseBoolean(o);
        return dflt;
    }

    /**
     *  Does nothing here. Extending classes may override.
     */
    public void errorOccurred(I2PSession session, String message, Throwable error) {
    }

    /**
     *  Does nothing here. Extending classes may override.
     */
    public void reportAbuse(I2PSession session, int severity) {
    }

    @Override
    public String toString() {
        return name;
    }
}
