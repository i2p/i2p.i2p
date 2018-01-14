package net.i2p.client.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.i2cp.CreateLeaseSetMessage;
import net.i2p.data.i2cp.CreateSessionMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.SessionId;
import net.i2p.util.I2PAppThread;

/**
 *  An additional session using another session's connection.
 *
 *  A subsession uses the same connection to the router as the primary session,
 *  but has a different Destination. It uses the same tunnels as the primary
 *  but has its own leaseset. It must use the same encryption keys as the primary
 *  so that garlic encryption/decryption works.
 *
 *  The message handler map and message producer are reused from primary.
 *
 *  Does NOT reuse the session listener ????
 *
 *  While the I2CP protocol, in theory, allows for fully independent sessions
 *  over the same I2CP connection, this is not currently supported by the router.
 *
 *  @since 0.9.21
 */
class SubSession extends I2PSessionMuxedImpl {
    private final I2PSessionMuxedImpl _primary;

    /**
     *  @param primary must be a I2PSessionMuxedImpl
     */
    public SubSession(I2PSession primary, InputStream destKeyStream, Properties options) throws I2PSessionException {
        super((I2PSessionMuxedImpl)primary, destKeyStream, options);
        _primary = (I2PSessionMuxedImpl) primary;
        if (!getDecryptionKey().equals(_primary.getDecryptionKey()))
            throw new I2PSessionException("encryption key mismatch");
        if (getPrivateKey().equals(_primary.getPrivateKey()))
            throw new I2PSessionException("signing key must differ");
        // state management
    }

    /**
     *  Unsupported in a subsession.
     *  @throws UnsupportedOperationException always
     */
    @Override
    public I2PSession addSubsession(InputStream destKeyStream, Properties opts) throws I2PSessionException {
        throw new UnsupportedOperationException();
    }
    
    /**
     *  Unsupported in a subsession.
     *  Does nothing.
     */
    @Override
    public void removeSubsession(I2PSession session) {}
    
    /**
     *  Unsupported in a subsession.
     *  @return empty list always
     */
    @Override
    public List<I2PSession> getSubsessions() {
        return Collections.emptyList();
    }

    /**
     *  Does nothing for now
     */
    @Override
    public void updateOptions(Properties options) {}

    /**
     *  @since 0.9.33
     */
    public Properties getPrimaryOptions() {
        return _primary.getOptions();
    }

    /**
     * Connect to the router and establish a session.  This call blocks until 
     * a session is granted.
     *
     * Should be threadsafe, other threads will block until complete.
     * Disconnect / destroy from another thread may be called simultaneously and
     * will (should?) interrupt the connect.
     *
     * Connecting a subsession will automatically connect the primary session
     * if not previously connected.
     *
     * @throws I2PSessionException if there is a configuration error or the router is
     *                             not reachable
     */
    @Override
    public void connect() throws I2PSessionException {
        synchronized(_stateLock) {
            if (_state != State.OPEN) {
                changeState(State.OPENING);
            }
        }
        boolean success = false;
        try {
            _primary.connect();
            // wait until we have created a lease set
            int waitcount = 0;
            while (_leaseSet == null) {
                if (waitcount++ > 5*60) {
                    throw new IOException("No tunnels built after waiting 5 minutes. Your network connection may be down, or there is severe network congestion.");
                }
                synchronized (_leaseSetWait) {
                    // InterruptedException caught below
                    _leaseSetWait.wait(1000);
                }
            }
            synchronized(_stateLock) {
                if (_state != State.OPEN) {
                    Thread notifier = new I2PAppThread(_availabilityNotifier, "ClientNotifier " + getPrefix(), true);
                    notifier.start();
                    changeState(State.OPEN);
                }
            }
            success = true;
        } catch (InterruptedException ie) {
            throw new I2PSessionException("Interrupted", ie);
        } catch (IOException ioe) {
            throw new I2PSessionException(getPrefix() + "Cannot connect to the router on " + _hostname + ':' + _portNum, ioe);
        } finally {
            if (!success) {
                _availabilityNotifier.stopNotifying();
                changeState(State.CLOSED);
            }
        }
    }

    /**
     *  Has the session been closed (or not yet connected)?
     *  False when open and during transitions.
     */
    @Override
    public boolean isClosed() {
        return super.isClosed() || _primary.isClosed();
    }

    /**
     * Deliver an I2CP message to the router
     * May block for several seconds if the write queue to the router is full
     *
     * @throws I2PSessionException if the message is malformed or there is an error writing it out
     */
    @Override
    void sendMessage(I2CPMessage message) throws I2PSessionException {
        // workaround for now, as primary will send out our CreateSession
        // from his connect, while we are still closed.
        // If we did it in connect() we wouldn't need this
        if (isClosed() &&
            message.getType() != CreateSessionMessage.MESSAGE_TYPE &&
            message.getType() != CreateLeaseSetMessage.MESSAGE_TYPE)
            throw new I2PSessionException("Already closed");
        _primary.sendMessage_unchecked(message);
    }

    /**
     * Deliver an I2CP message to the router.
     * Does NOT check state. Call only from connect() or other methods that need to
     * send messages when not in OPEN state.
     *
     * @throws I2PSessionException if the message is malformed or there is an error writing it out
     * @since 0.9.23
     */
    @Override
    void sendMessage_unchecked(I2CPMessage message) throws I2PSessionException {
        _primary.sendMessage_unchecked(message);
    }

    /**
     * Pass off the error to the listener
     * Misspelled, oh well.
     * @param error non-null
     */
    @Override
    void propogateError(String msg, Throwable error) {
        _primary.propogateError(msg, error);
        if (_sessionListener != null) _sessionListener.errorOccurred(this, msg, error);
    }

    /**
     * Tear down the session, and do NOT reconnect.
     *
     * Blocks if session has not been fully started.
     */
    @Override
    public void destroySession() {
        _primary.destroySession();
        if (_availabilityNotifier != null)
            _availabilityNotifier.stopNotifying();
        if (_sessionListener != null) _sessionListener.disconnected(this);
        changeState(State.CLOSED);
    }

    /**
     * Will interrupt a connect in progress.
     */
    @Override
    protected void disconnect() {
        _primary.disconnect();
    }

    @Override
    protected boolean reconnect() {
        return _primary.reconnect();
    }

    /**
     *  Called by the message handler
     *  on reception of DestReplyMessage
     *
     *  This will never happen, as the dest reply message does not contain a session ID.
     */
    @Override
    void destReceived(Destination d) {
        _primary.destReceived(d);
    }

    /**
     *  Called by the message handler
     *  on reception of DestReplyMessage
     *
     *  This will never happen, as the dest reply message does not contain a session ID.
     *
     *  @param h non-null
     */
    @Override
    void destLookupFailed(Hash h) {
        _primary.destLookupFailed(h);
    }

    /**
     *  Called by the message handler
     *  on reception of HostReplyMessage
     *  @param d non-null
     */
    void destReceived(long nonce, Destination d) {
        _primary.destReceived(nonce, d);
    }

    /**
     *  Called by the message handler
     *  on reception of HostReplyMessage
     */
    @Override
    void destLookupFailed(long nonce) {
        _primary.destLookupFailed(nonce);
    }

    /**
     * Called by the message handler.
     * This will never happen, as the bw limits message does not contain a session ID.
     */
    @Override
    void bwReceived(int[] i) {
        _primary.bwReceived(i);
    }

    /**
     *  Blocking. Waits a max of 10 seconds by default.
     *  See lookupDest with maxWait parameter to change.
     *  Implemented in 0.8.3 in I2PSessionImpl;
     *  previously was available only in I2PSimpleSession.
     *  Multiple outstanding lookups are now allowed.
     *  @return null on failure
     */
    @Override
    public Destination lookupDest(Hash h) throws I2PSessionException {
        return _primary.lookupDest(h);
    }

    /**
     *  Blocking.
     *  @param maxWait ms
     *  @return null on failure
     */
    @Override
    public Destination lookupDest(Hash h, long maxWait) throws I2PSessionException {
        return _primary.lookupDest(h, maxWait);
    }

    /**
     *  Ask the router to lookup a Destination by host name.
     *  Blocking. Waits a max of 10 seconds by default.
     *
     *  This only makes sense for a b32 hostname, OR outside router context.
     *  Inside router context, just query the naming service.
     *  Outside router context, this does NOT query the context naming service.
     *  Do that first if you expect a local addressbook.
     *
     *  This will log a warning for non-b32 in router context.
     *
     *  See interface for suggested implementation.
     *
     *  Requires router side to be 0.9.11 or higher. If the router is older,
     *  this will return null immediately.
     */
    @Override
    public Destination lookupDest(String name) throws I2PSessionException {
        return _primary.lookupDest(name);
    }

    /**
     *  Ask the router to lookup a Destination by host name.
     *  Blocking. See above for details.
     *  @param maxWait ms
     *  @return null on failure
     */
    @Override
    public Destination lookupDest(String name, long maxWait) throws I2PSessionException {
        return _primary.lookupDest(name, maxWait);
    }

    /**
     *  This won't be called, as the reply does not contain a session ID, so
     *  it won't be routed back to us
     */
    @Override
    public int[] bandwidthLimits() throws I2PSessionException {
        return _primary.bandwidthLimits();
    }

    @Override
    protected void updateActivity() {
        _primary.updateActivity();
    }

    @Override
    public long lastActivity() {
        return _primary.lastActivity();
    }

    @Override
    public void setReduced() {
        _primary.setReduced();
    }
}
