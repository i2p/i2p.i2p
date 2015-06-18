package net.i2p.client.impl;

import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.SendMessageStatusListener;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.util.Log;

/**
 * Contains the state of a payload message being sent to a peer.
 *
 * Originally was a general-purpose waiter.
 * Then we got rid of guaranteed delivery.
 * Then we stopped waiting for accept in best-effort delivery.
 * Brought back to life for asynchronous status delivery to the client.
 */
class MessageState {
    private final I2PAppContext _context;
    private final Log _log;
    private final long _nonce;
    private final String _prefix;
    private MessageId _id;
    private final long _created;
    private final long _expires;
    private final SendMessageStatusListener _listener;
    private final I2PSession _session;

    private enum State { INIT, ACCEPTED, PROBABLE_FAIL, FAIL, SUCCESS };
    private State _state = State.INIT;

    /**
     *  For synchronous waiting for accept with waitForAccept().
     *  UNUSED.
     */
    public MessageState(I2PAppContext ctx, long nonce, String prefix) {
        _context = ctx;
        _log = ctx.logManager().getLog(MessageState.class);
        _nonce = nonce;
        _prefix = prefix + '[' + _nonce + "]: ";
        _created = ctx.clock().now();
        _expires = _created + 60*1000L;
        _listener = null;
        _session = null;
    }

    /**
     *  For asynchronous notification
     *  @param expires absolute time (not interval)
     *  @since 0.9.14
     */
    public MessageState(I2PAppContext ctx, long nonce, I2PSession session,
                        long expires, SendMessageStatusListener listener) {
        _context = ctx;
        _log = ctx.logManager().getLog(MessageState.class);
        _nonce = nonce;
        _prefix = session.toString() + " [" + _nonce + "]: ";
        _created = ctx.clock().now();
        _expires = expires;
        _listener = listener;
        _session = session;
    }

    public void receive(int status) {
        State oldState;
        State newState;
        synchronized (this) {
            oldState = _state;
            locked_update(status);
            newState = _state;
            this.notifyAll();
        }
        if (_listener != null) {
            // only notify on changing state, and only if we haven't expired
            if (oldState != newState && _expires > _context.clock().now())
                _listener.messageStatus(_session, _nonce, status);
        }
    }

    public void setMessageId(MessageId id) {
        _id = id;
    }

    public MessageId getMessageId() {
        return _id;
    }

    public long getElapsed() {
        return _context.clock().now() - _created;
    }

    /**
     *  @since 0.9.14
     */
    public long getExpires() {
        return _expires;
    }

    /**
     *  For guaranteed/best effort only. Not really used.
     */
    public void waitForAccept(long expiration) throws InterruptedException {
        while (true) {
            long timeToWait = expiration - _context.clock().now();
            if (timeToWait <= 0) {
                if (_log.shouldLog(Log.WARN)) 
                    _log.warn(_prefix + "Expired waiting for the status");
                return;
            }
            synchronized (this) {
                if (_state != State.INIT) {
                    if (_log.shouldLog(Log.DEBUG)) 
                        _log.debug(_prefix + "Received a confirm (one way or the other)");
                    return;
                }
                if (timeToWait > 5000)
                    timeToWait = 5000;
                this.wait(timeToWait);
            }
        }
    }

    /**
     *  Update our flags
     *  @since 0.9.14
     */
    private void locked_update(int status) {
        switch (status) {
            case MessageStatusMessage.STATUS_SEND_ACCEPTED:
                // only trumps init
                if (_state == State.INIT)
                    _state = State.ACCEPTED;
                break;

            case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE:
            case MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE:
                // does not trump failure or success
                if (_state != State.FAIL && _state != State.SUCCESS)
                    _state = State.PROBABLE_FAIL;
                break;

            case MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL:
            case MessageStatusMessage.STATUS_SEND_FAILURE_ROUTER:
            case MessageStatusMessage.STATUS_SEND_FAILURE_NETWORK:
            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_SESSION:
            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_MESSAGE:
            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_OPTIONS:
            case MessageStatusMessage.STATUS_SEND_FAILURE_OVERFLOW:
            case MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED:
            case MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL_LEASESET:
            case MessageStatusMessage.STATUS_SEND_FAILURE_NO_TUNNELS:
            case MessageStatusMessage.STATUS_SEND_FAILURE_UNSUPPORTED_ENCRYPTION:
            case MessageStatusMessage.STATUS_SEND_FAILURE_DESTINATION:
            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_LEASESET:
            case MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED_LEASESET:
            case MessageStatusMessage.STATUS_SEND_FAILURE_NO_LEASESET:
            case SendMessageStatusListener.STATUS_CANCELLED:
                // does not trump success
                if (_state != State.SUCCESS)
                    _state = State.FAIL;
                break;

            case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_SUCCESS:
            case MessageStatusMessage.STATUS_SEND_GUARANTEED_SUCCESS:
            case MessageStatusMessage.STATUS_SEND_SUCCESS_LOCAL:
                // trumps all
                _state = State.SUCCESS;
                break;

            default:
                break;
        }
    }

    /**
     *  @return true if accepted (fixme and not failed)
     *  @since 0.9.14
     */
    public boolean wasAccepted() {
        synchronized (this) {
            return _state != State.INIT && _state != State.FAIL;
        }
    }

    /**
     *  @return true if successful
     *  @since 0.9.14
     */
    public boolean wasSuccessful() {
        synchronized (this) {
            return _state == State.SUCCESS;
        }
    }

    public void cancel() {
        // Inject a fake status
        receive(SendMessageStatusListener.STATUS_CANCELLED);
    }
}
