package net.i2p.heartbeat;

import net.i2p.data.Destination;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Responsible for actually conducting the tests, coordinating the storing of the 
 * stats, and the management of the rates.  This has its own thread specific for
 * pumping data around as well.
 *
 */
class ClientEngine {
    private static final Log _log = new Log(ClientEngine.class);
    /** who can send our pings? */
    private Heartbeat _heartbeat;
    /** actual test state */
    private PeerData _data;
    /** have we been stopped? */
    private boolean _active;
    /** used to generate engine IDs */
    private static int __id = 0;
    /** this engine's id, unique to the {test,sendingClient,startTime} */
    private int _id;
    private static PeerDataWriter writer = new PeerDataWriter();

    /**
     * Create a new engine that will send its pings through the given heartbeat
     * system, and will coordinate the test according to the configuration specified.
     * @param heartbeat the Heartbeat to send pings through
     * @param config the Configuration to load configuration from =p
     */
    public ClientEngine(Heartbeat heartbeat, ClientConfig config) {
        _heartbeat = heartbeat;
        _data = new PeerData(config);
        _active = false;
        _id = ++__id;
    }

    /** stop sending any more pings or writing any more state */
    public void stopEngine() {
        _active = false;
        if (_log.shouldLog(Log.INFO))
            _log.info("Stopping engine talking to peer " + _data.getConfig().getPeer().calculateHash().toBase64());
    }

    /** start up the test (this does not block, as it fires up the test thread) */
    public void startEngine() {
        _active = true;
        I2PThread t = new I2PThread(new ClientRunner());
        t.setName("HeartbeatClient " + _id);
        t.start();
    }

    /**
     * Who are we testing? 
     * @return the Destination (peer) we're testing
     */
    public Destination getPeer() {
        return _data.getConfig().getPeer();
    }

    /**
     * What is our series identifier (used to locally identify a test) 
     * @return the series identifier
     */
    public int getSeriesNum() {
        return _id;
    }

    /** 
     * receive notification from the heartbeat system that a pong was received in 
     * reply to a ping we have sent.  
     *
     * @param sentOn when did we send the ping?
     * @param replyOn when did the peer send the pong?
     */
    public void receivePong(long sentOn, long replyOn) {
        _data.pongReceived(sentOn, replyOn);
    }

    /** fire off a new ping */
    private void doSend() {
        long now = Clock.getInstance().now();
        _data.addPing(now);
        _heartbeat.sendPing(_data.getConfig().getPeer(), _id, now, _data.getConfig().getSendSize());
    }

    /** our actual heartbeat pumper - this drives the test */
    private class ClientRunner implements Runnable {

        /**
         * @see java.lang.Runnable#run()
         */
        public void run() {
            if (_log.shouldLog(Log.INFO))
                _log.info("Starting engine talking to peer " + _data.getConfig().getPeer().calculateHash().toBase64());

            // when do we need to send the next PING?
            long nextSend = Clock.getInstance().now();
            // when do we need to write out the next state data?
            long nextWrite = Clock.getInstance().now();

            while (_active) {

                if (Clock.getInstance().now() >= nextSend) {
                    doSend();
                    nextSend = Clock.getInstance().now() + _data.getConfig().getSendFrequency() * 1000;
                }

                if (Clock.getInstance().now() >= nextWrite) {
                    boolean written = writer.persist(_data);
                    if (!written) {
                        if (_log.shouldLog(Log.ERROR)) _log.error("Unable to write the client state data");
                    } else {
                        if (_log.shouldLog(Log.DEBUG)) _log.debug("Client state data written");
                    }
                }

                _data.cleanup();

                long timeToWait = nextSend - Clock.getInstance().now();
                if (timeToWait > 0) {
                    try {
                        Thread.sleep(timeToWait);
                    } catch (InterruptedException ie) {
                    }
                }
            }
        }
    }
}