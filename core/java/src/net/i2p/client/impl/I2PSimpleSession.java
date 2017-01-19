package net.i2p.client.impl;

/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.Properties;

import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSessionException;
import net.i2p.data.i2cp.BandwidthLimitsMessage;
import net.i2p.data.i2cp.DestReplyMessage;
import net.i2p.data.i2cp.DisconnectMessage;
import net.i2p.data.i2cp.GetDateMessage;
import net.i2p.data.i2cp.HostReplyMessage;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.data.i2cp.SetDateMessage;
import net.i2p.internal.InternalClientManager;
import net.i2p.internal.QueuedI2CPMessageReader;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SystemVersion;

/**
 * Create a new session for doing naming and bandwidth queries only. Do not create a Destination.
 * Don't create a producer. Do not send/receive messages to other Destinations.
 * Cannot handle multiple simultaneous queries atm.
 * Could be expanded to ask the router other things.
 *
 * @author zzz
 */
public class I2PSimpleSession extends I2PSessionImpl2 {

    private static final int BUF_SIZE = 1024;

    /**
     * Create a new session for doing naming and bandwidth queries only. Do not create a destination.
     *
     * @throws I2PSessionException if there is a problem
     */
    public I2PSimpleSession(I2PAppContext context, Properties options) throws I2PSessionException {
        super(context, options, new SimpleMessageHandlerMap(context));
    }

    /**
     * Connect to the router and establish a session.  This call blocks until 
     * a session is granted.
     *
     * NOT threadsafe, do not call from multiple threads.
     *
     * @throws I2PSessionException if there is a configuration error or the router is
     *                             not reachable
     */
    @Override
    public void connect() throws I2PSessionException {
        changeState(State.OPENING);
        boolean success = false;
        try {
            // protect w/ closeSocket()
            synchronized(_stateLock) {
                // If we are in the router JVM, connect using the interal queue
                if (_context.isRouterContext()) {
                    // _socket and _writer remain null
                    InternalClientManager mgr = _context.internalClientManager();
                    if (mgr == null)
                        throw new I2PSessionException("Router is not ready for connections");
                    // the following may throw an I2PSessionException
                    _queue = mgr.connect();
                    _reader = new QueuedI2CPMessageReader(_queue, this);
                    _reader.startReading();
                } else {
                    if (Boolean.parseBoolean(getOptions().getProperty(PROP_ENABLE_SSL))) {
                        try {
                            I2PSSLSocketFactory fact = new I2PSSLSocketFactory(_context, false, "certificates/i2cp");
                            _socket = fact.createSocket(_hostname, _portNum);
                        } catch (GeneralSecurityException gse) {
                            IOException ioe = new IOException("SSL Fail");
                            ioe.initCause(gse);
                            throw ioe;
                        }
                    } else {
                        _socket = new Socket(_hostname, _portNum);
                    }
                    _socket.setKeepAlive(true);
                    OutputStream out = _socket.getOutputStream();
                    out.write(I2PClient.PROTOCOL_BYTE);
                    out.flush();
                    _writer = new ClientWriterRunner(out, this);
                    _writer.startWriting();
                    InputStream in = new BufferedInputStream(_socket.getInputStream(), BUF_SIZE);
                    _reader = new I2CPMessageReader(in, this);
                    _reader.startReading();
                }
            }
            // must be out of synch block for writer to get unblocked
            if (!_context.isRouterContext()) {
                Properties opts = getOptions();
                // Send auth message if required
                // Auth was not enforced on a simple session until 0.9.11
                // We will get disconnected for router version < 0.9.11 since it doesn't
                // support the AuthMessage
                if ((!opts.containsKey(PROP_USER)) && (!opts.containsKey(PROP_PW))) {
                    // auto-add auth if not set in the options
                    String configUser = _context.getProperty(PROP_USER);
                    String configPW = _context.getProperty(PROP_PW);
                    if (configUser != null && configPW != null) {
                        opts.setProperty(PROP_USER, configUser);
                        opts.setProperty(PROP_PW, configPW);
                    }
                }
                if (opts.containsKey(PROP_USER) && opts.containsKey(PROP_PW)) {
                    Properties auth = new OrderedProperties();
                    auth.setProperty(PROP_USER, opts.getProperty(PROP_USER));
                    auth.setProperty(PROP_PW, opts.getProperty(PROP_PW));
                    sendMessage_unchecked(new GetDateMessage(CoreVersion.VERSION, auth));
                } else {
                    // we must now send a GetDate even in SimpleSession, or we won't know
                    // what version we are talking with and cannot use HostLookup
                    sendMessage_unchecked(new GetDateMessage(CoreVersion.VERSION));
                }
                waitForDate();
            }
            // we do not receive payload messages, so we do not need an AvailabilityNotifier
            // ... or an Idle timer, or a VerifyUsage
            success = true;
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix() + " simple session connected");
        } catch (InterruptedException ie) {
            throw new I2PSessionException("Interrupted", ie);
        } catch (UnknownHostException uhe) {
            throw new I2PSessionException(getPrefix() + "Cannot connect to the router on " + _hostname + ':' + _portNum, uhe);
        } catch (IOException ioe) {
            // Generate the best error message as this will be logged
            String msg;
            if (_context.isRouterContext())
                msg = "Failed internal router binding";
            else if (SystemVersion.isAndroid() &&
                    Boolean.parseBoolean(getOptions().getProperty(PROP_DOMAIN_SOCKET)))
                msg = "Failed to bind to the router";
            else
                msg = "Cannot connect to the router on " + _hostname + ':' + _portNum;
            throw new I2PSessionException(getPrefix() + msg, ioe);
        } finally {
            changeState(success ? State.OPEN : State.CLOSED);
        }
    }

    /**
     * Ignore, does nothing
     * @since 0.8.4
     */
    @Override
    public void updateOptions(Properties options) {}

    /**
     * Only map message handlers that we will use
     */
    private static class SimpleMessageHandlerMap extends I2PClientMessageHandlerMap {
        public SimpleMessageHandlerMap(I2PAppContext context) {
            int highest = Math.max(DestReplyMessage.MESSAGE_TYPE, BandwidthLimitsMessage.MESSAGE_TYPE);
            highest = Math.max(highest, DisconnectMessage.MESSAGE_TYPE);
            highest = Math.max(highest, HostReplyMessage.MESSAGE_TYPE);
            highest = Math.max(highest, SetDateMessage.MESSAGE_TYPE);
            _handlers = new I2CPMessageHandler[highest+1];
            _handlers[DestReplyMessage.MESSAGE_TYPE] = new DestReplyMessageHandler(context);
            _handlers[BandwidthLimitsMessage.MESSAGE_TYPE] = new BWLimitsMessageHandler(context);
            _handlers[DisconnectMessage.MESSAGE_TYPE] = new DisconnectMessageHandler(context);
            _handlers[HostReplyMessage.MESSAGE_TYPE] = new HostReplyMessageHandler(context);
            _handlers[SetDateMessage.MESSAGE_TYPE] = new SetDateMessageHandler(context);
        }
    }
}
