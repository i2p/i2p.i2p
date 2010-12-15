package net.i2p.client;

/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.i2cp.BandwidthLimitsMessage;
import net.i2p.data.i2cp.DestLookupMessage;
import net.i2p.data.i2cp.DestReplyMessage;
import net.i2p.data.i2cp.GetBandwidthLimitsMessage;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.internal.I2CPMessageQueue;
import net.i2p.internal.InternalClientManager;
import net.i2p.internal.QueuedI2CPMessageReader;
import net.i2p.util.I2PAppThread;

/**
 * Create a new session for doing naming and bandwidth queries only. Do not create a Destination.
 * Don't create a producer. Do not send/receive messages to other Destinations.
 * Cannot handle multiple simultaneous queries atm.
 * Could be expanded to ask the router other things.
 *
 * @author zzz
 */
class I2PSimpleSession extends I2PSessionImpl2 {
    private boolean _destReceived;
    private /* FIXME final FIXME */ Object _destReceivedLock;
    private Destination _destination;
    private boolean _bwReceived;
    private /* FIXME final FIXME */ Object _bwReceivedLock;
    private int[] _bwLimits;

    /**
     * Create a new session for doing naming and bandwidth queries only. Do not create a destination.
     *
     * @throws I2PSessionException if there is a problem
     */
    public I2PSimpleSession(I2PAppContext context, Properties options) throws I2PSessionException {
        // Warning, does not call super()
        _context = context;
        _log = context.logManager().getLog(I2PSimpleSession.class);
        _handlerMap = new SimpleMessageHandlerMap(context);
        _closed = true;
        _closing = false;
        if (options == null)
            options = System.getProperties();
        loadConfig(options);
    }

    /**
     * Connect to the router and establish a session.  This call blocks until 
     * a session is granted.
     *
     * @throws I2PSessionException if there is a configuration error or the router is
     *                             not reachable
     */
    @Override
    public void connect() throws I2PSessionException {
        _closed = false;
        
        try {
            // If we are in the router JVM, connect using the interal queue
            if (_context.isRouterContext()) {
                // _socket, _out, and _writer remain null
                InternalClientManager mgr = _context.internalClientManager();
                if (mgr == null)
                    throw new I2PSessionException("Router is not ready for connections");
                // the following may throw an I2PSessionException
                _queue = mgr.connect();
                _reader = new QueuedI2CPMessageReader(_queue, this);
            } else {
                if (Boolean.valueOf(getOptions().getProperty(PROP_ENABLE_SSL)).booleanValue())
                    _socket = I2CPSSLSocketFactory.createSocket(_context, _hostname, _portNum);
                else
                    _socket = new Socket(_hostname, _portNum);
                _out = _socket.getOutputStream();
                synchronized (_out) {
                    _out.write(I2PClient.PROTOCOL_BYTE);
                    _out.flush();
                }
                _writer = new ClientWriterRunner(_out, this);
                InputStream in = _socket.getInputStream();
                _reader = new I2CPMessageReader(in, this);
            }
            // we do not receive payload messages, so we do not need an AvailabilityNotifier
            _reader.startReading();

        } catch (UnknownHostException uhe) {
            _closed = true;
            throw new I2PSessionException(getPrefix() + "Cannot connect to the router on " + _hostname + ':' + _portNum, uhe);
        } catch (IOException ioe) {
            _closed = true;
            throw new I2PSessionException(getPrefix() + "Cannot connect to the router on " + _hostname + ':' + _portNum, ioe);
        }
    }

    /** called by the message handler */
    void destReceived(Destination d) {
        _destReceived = true;
        _destination = d;
        synchronized (_destReceivedLock) {
            _destReceivedLock.notifyAll();
        }
    }

    void bwReceived(int[] i) {
        _bwReceived = true;
        _bwLimits = i;
        synchronized (_bwReceivedLock) {
            _bwReceivedLock.notifyAll();
        }
    }

    @Override
    public Destination lookupDest(Hash h) throws I2PSessionException {
        if (_closed)
            return null;
        _destReceivedLock = new Object();
        sendMessage(new DestLookupMessage(h));
        for (int i = 0; i < 10 && !_destReceived; i++) {
            try {
                synchronized (_destReceivedLock) {
                    _destReceivedLock.wait(1000);
                }
            } catch (InterruptedException ie) {}
        }
        _destReceived = false;
        return _destination;
    }

    @Override
    public int[] bandwidthLimits() throws I2PSessionException {
        if (_closed)
            return null;
        _bwReceivedLock = new Object();
        sendMessage(new GetBandwidthLimitsMessage());
        for (int i = 0; i < 5 && !_bwReceived; i++) {
            try {
                synchronized (_bwReceivedLock) {
                    _bwReceivedLock.wait(1000);
                }
            } catch (InterruptedException ie) {}
        }
        _bwReceived = false;
        return _bwLimits;
    }

    /**
     * Only map message handlers that we will use
     */
    class SimpleMessageHandlerMap extends I2PClientMessageHandlerMap {
        public SimpleMessageHandlerMap(I2PAppContext context) {
            int highest = Math.max(DestReplyMessage.MESSAGE_TYPE, BandwidthLimitsMessage.MESSAGE_TYPE);
            _handlers = new I2CPMessageHandler[highest+1];
            _handlers[DestReplyMessage.MESSAGE_TYPE] = new DestReplyMessageHandler(context);
            _handlers[BandwidthLimitsMessage.MESSAGE_TYPE] = new BWLimitsMessageHandler(context);
        }
    }
}
