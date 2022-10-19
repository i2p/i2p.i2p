package org.klomp.snark;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.SendMessageOptions;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.client.datagram.I2PDatagramMaker;
import net.i2p.client.datagram.I2PInvalidDatagramException;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 *  One of these for all trackers and info hashes.
 *  Ref: BEP 15, proposal 160
 *
 *  The main difference from BEP 15 is that the announce response
 *  contains a 32-byte hash instead of a 4-byte IP and a 2-byte port.
 *
 *  This implements only "fast mode".
 *  We send only repliable datagrams, and
 *  receive only raw datagrams, as follows:
 *
 *<pre>
 *  client		tracker		type
 *  ------		-------		----
 *   announce  --&gt;			repliable
 *            	&lt;-- 	ann resp	raw
 *</pre>
 *
 *  @since 0.9.53, enabled in 0.9.54
 */
class UDPTrackerClient implements I2PSessionMuxedListener {

    private final I2PAppContext _context;
    private final Log _log;
    /** hook to inject and receive datagrams */
    private final I2PSession _session;
    private final I2PSnarkUtil _util;
    private final Hash _myHash;
    /** unsigned dgrams */
    private final int _rPort;
    /** dest and port to tracker data */
    private final ConcurrentHashMap<HostPort, Tracker> _trackers;
    /** our TID to tracker */
    private final Map<Integer, ReplyWaiter> _sentQueries;
    private boolean _isRunning;

    public static final int EVENT_NONE = 0;
    public static final int EVENT_COMPLETED = 1;
    public static final int EVENT_STARTED = 2;
    public static final int EVENT_STOPPED = 3;

    private static final int ACTION_CONNECT = 0;
    private static final int ACTION_ANNOUNCE = 1;
    private static final int ACTION_SCRAPE = 2;
    private static final int ACTION_ERROR = 3;

    private static final int SEND_CRYPTO_TAGS = 8;
    private static final int LOW_CRYPTO_TAGS = 4;

    private static final long DEFAULT_TIMEOUT = 15*1000;
    private static final long DEFAULT_QUERY_TIMEOUT = 60*1000;
    private static final long CLEAN_TIME = 163*1000;

    /** in seconds */
    private static final int DEFAULT_INTERVAL = 60*60;
    private static final int MIN_INTERVAL = 15*60;
    private static final int MAX_INTERVAL = 8*60*60;

    private enum WaitState { INIT, SUCCESS, TIMEOUT, FAIL }

    /**
     *
     */
    public UDPTrackerClient(I2PAppContext ctx, I2PSession session, I2PSnarkUtil util) {
        _context = ctx;
        _session = session;
        _util = util;
        _log = ctx.logManager().getLog(UDPTrackerClient.class);
        _rPort = TrackerClient.PORT - 1;
        _myHash = session.getMyDestination().calculateHash();
        _trackers = new ConcurrentHashMap<HostPort, Tracker>(8);
        _sentQueries = new ConcurrentHashMap<Integer, ReplyWaiter>(32);
    }


    /**
     *  Can't be restarted after stopping?
     */
    public synchronized void start() {
        if (_isRunning)
            return;
        _session.addMuxedSessionListener(this, I2PSession.PROTO_DATAGRAM_RAW, _rPort);
        _isRunning = true;
    }

    /**
     *  Stop everything.
     */
    public synchronized void stop() {
        if (!_isRunning)
            return;
        _isRunning = false;
        _session.removeListener(I2PSession.PROTO_DATAGRAM_RAW, _rPort);
        _trackers.clear();
        for (ReplyWaiter w : _sentQueries.values()) {
            w.cancel();
        }
        _sentQueries.clear();
    }

    /**
     *  Announce and get peers for a torrent.
     *  Blocking!
     *  Caller should run in a thread.
     *
     *  @param ih the Info Hash (torrent)
     *  @param max maximum number of peers to return
     *  @param maxWait the maximum time to wait (ms) must be greater than 0
     *  @param fast if true, don't wait for dest, no retx, ...
     *  @return null on fail or if fast is true
     */
    public TrackerResponse announce(byte[] ih, byte[] peerID, int max, long maxWait,
                                    String toHost, int toPort,
                                    long downloaded, long left, long uploaded,
                                    int event, boolean fast) {
        long now = _context.clock().now();
        long end = now + maxWait;
        if (toPort < 0)
            throw new IllegalArgumentException();
        Tracker tr = getTracker(toHost, toPort);
        if (tr.getDest(fast) == null) {
            if (_log.shouldInfo())
                _log.info("cannot resolve " + tr);
            return null;
        }
        long toWait = end - now;
        if (!fast)
            toWait = toWait * 3 / 4;
        if (toWait < 1000) {
            if (_log.shouldInfo())
                _log.info("out of time after resolving: " + tr);
            return null;
        }
        if (fast) {
            toWait = 0;
        } else {
            toWait = end - now;
            if (toWait < 1000) {
                if (_log.shouldInfo())
                    _log.info("out of time after getting conn: " + tr);
                return null;
            }
        }
        ReplyWaiter w = sendAnnounce(tr, 0, ih, peerID,
                                     downloaded, left, uploaded, event, max, toWait);
        if (fast)
            return null;
        if (w == null) {
            if (_log.shouldInfo())
                _log.info("initial announce failed: " + tr);
            return null;
        }
        boolean success = waitAndRetransmit(w, end);
        _sentQueries.remove(w.getID());
        if (success)
            return w.getReplyObject();
        if (_log.shouldInfo())
            _log.info("announce failed after retx: " + tr);
        return null;
    }

    //////// private below here

    /**
     *  @return non-null
     */
    private Tracker getTracker(String host, int port) {
        Tracker ndp = new Tracker(host, port);
        Tracker odp = _trackers.putIfAbsent(ndp, ndp);
        if (odp != null)
            ndp = odp;
        return ndp;
    }

    ///// Sending.....

    /**
     *  Send one time with a new tid
     *  @param toWait if <= 0 does not register
     *  @return null on failure or if toWait <= 0
     */
    private ReplyWaiter sendAnnounce(Tracker tr, long connID,
                                 byte[] ih, byte[] id,
                                 long downloaded, long left, long uploaded,
                                 int event, int numWant, long toWait) {
        int tid = _context.random().nextInt();
        byte[] payload = sendAnnounce(tr, tid, connID, ih, id, downloaded, left, uploaded, event, numWant);
        if (payload != null) {
            if (toWait > 0) {
                ReplyWaiter rv = new ReplyWaiter(tid, tr, payload, toWait);
                _sentQueries.put(Integer.valueOf(tid), rv);
                if (_log.shouldInfo())
                    _log.info("Sent: " + rv + " timeout: " + toWait);
                return rv;
            }
            if (_log.shouldInfo())
                _log.info("Sent annc " + event + " to " + tr + " no wait");
        }
        return null;
    }

    /**
     *  Send one time with given tid
     *  @return the payload or null on failure
     */
    private byte[] sendAnnounce(Tracker tr, int tid, long connID,
                                 byte[] ih, byte[] id,
                                 long downloaded, long left, long uploaded,
                                 int event, int numWant) {
        byte[] payload = new byte[98];
        DataHelper.toLong8(payload, 0, connID);
        DataHelper.toLong(payload, 8, 4, ACTION_ANNOUNCE);
        DataHelper.toLong(payload, 12, 4, tid);
        System.arraycopy(ih, 0, payload, 16, 20);
        System.arraycopy(id, 0, payload, 36, 20);
        DataHelper.toLong(payload, 56, 8, downloaded);
        DataHelper.toLong(payload, 64, 8, left);
        DataHelper.toLong(payload, 72, 8, uploaded);
        DataHelper.toLong(payload, 80, 4, event);
        DataHelper.toLong(payload, 92, 4, numWant);
        DataHelper.toLong(payload, 96, 2, TrackerClient.PORT);
        boolean rv = sendMessage(tr.getDest(true), tr.getPort(), payload, true);
        return rv ? payload : null;
    }

    /**
     *  wait after initial send, resend if necessary
     */
    private boolean waitAndRetransmit(ReplyWaiter w, long untilTime) {
        synchronized(w) {
            while(true) {
                try {
                    long toWait = untilTime - _context.clock().now();
                    if (toWait <= 0)
                        return false;
                    w.wait(toWait);
                } catch (InterruptedException ie) {
                    return false;
                }
                switch (w.getState()) {
                    case INIT:
                        continue;

                    case SUCCESS:
                        return true;

                    case FAIL:
                        return false;

                    case TIMEOUT:
                        if (_log.shouldInfo())
                            _log.info("Timeout: " + w);
                        long toWait = untilTime - _context.clock().now();
                        if (toWait <= 1000)
                            return false;
                        boolean ok = resend(w, Math.min(toWait, w.getSentTo().getTimeout()));
                        if (!ok)
                            return false;
                        continue;
                }
            }
        }
    }

    /**
     *  Resend the stored payload
     *  @return success
     */
    private boolean resend(ReplyWaiter w, long toWait) {
        Tracker tr = w.getSentTo();
        int port = tr.getPort();
        if (_log.shouldInfo())
            _log.info("Resending: " + w + " timeout: " + toWait);
        boolean rv = sendMessage(tr.getDest(true), port, w.getPayload(), true);
        if (rv) {
            _sentQueries.put(Integer.valueOf(w.getID()), w);
            w.schedule(toWait);
        }
        return rv;
    }

    /**
     *  Lowest-level send message call.
     *  @param dest may be null, returns false
     *  @param repliable true for conn request, false for announce
     *  @return success
     */
    private boolean sendMessage(Destination dest, int toPort, byte[] payload, boolean repliable) {
        if (!_isRunning) {
            if (_log.shouldInfo())
                _log.info("send failed, not running");
            return false;
        }
        if (dest == null) {
            if (_log.shouldInfo())
                _log.info("send failed, no dest");
            return false;
        }
        if (dest.calculateHash().equals(_myHash))
            throw new IllegalArgumentException("don't send to ourselves");

        if (repliable) {
            I2PDatagramMaker dgMaker = new I2PDatagramMaker(_session);
            payload = dgMaker.makeI2PDatagram(payload);
            if (payload == null) {
                if (_log.shouldWarn())
                    _log.warn("DGM fail");
                return false;
            }
        }

        SendMessageOptions opts = new SendMessageOptions();
        opts.setDate(_context.clock().now() + 60*1000);
        opts.setTagsToSend(SEND_CRYPTO_TAGS);
        opts.setTagThreshold(LOW_CRYPTO_TAGS);
        if (!repliable)
            opts.setSendLeaseSet(false);
        try {
            boolean success = _session.sendMessage(dest, payload, 0, payload.length,
                                                   repliable ? I2PSession.PROTO_DATAGRAM : I2PSession.PROTO_DATAGRAM_RAW,
                                                   _rPort, toPort, opts);
            if (success) {
                // ...
            } else {
                if (_log.shouldWarn())
                    _log.warn("sendMessage fail");
            }
            return success;
        } catch (I2PSessionException ise) {
            if (_log.shouldWarn())
                _log.warn("sendMessage fail", ise);
            return false;
        }
    }

    ///// Reception.....

    /**
     *  @param from dest or null if it didn't come in on signed port
     */
    private void receiveMessage(Destination from, int fromPort, byte[] payload) {
        if (payload.length < 8) {
            if (_log.shouldInfo())
                _log.info("Got short message: " + payload.length + " bytes");
            return;
        }

        int action = (int) DataHelper.fromLong(payload, 0, 4);
        int tid = (int) DataHelper.fromLong(payload, 4, 4);
        ReplyWaiter waiter = _sentQueries.remove(Integer.valueOf(tid));
        if (waiter == null) {
            if (_log.shouldInfo())
                _log.info("Rcvd msg with no one waiting: " + tid);
            return;
        }

        if (action == ACTION_ANNOUNCE) {
            receiveAnnounce(waiter, payload);
        } else if (action == ACTION_ERROR) {
            receiveError(waiter, payload);
        } else {
            // includes ACTION_CONNECT
            if (_log.shouldInfo())
                _log.info("Rcvd msg with unknown action: " + action + " for: " + waiter);
            waiter.gotReply(false);
            Tracker tr = waiter.getSentTo();
            tr.gotError();
        }
    }

    private void receiveAnnounce(ReplyWaiter waiter, byte[] payload) {
        Tracker tr = waiter.getSentTo();
        if (payload.length >= 22) {
            int interval = Math.min(MAX_INTERVAL, Math.max(MIN_INTERVAL,
                                                           (int) DataHelper.fromLong(payload, 8, 4)));
            int leeches = (int) DataHelper.fromLong(payload, 12, 4);
            int seeds = (int) DataHelper.fromLong(payload, 16, 4);
            int peers = (int) DataHelper.fromLong(payload, 20, 2);
            if (22 + (peers * Hash.HASH_LENGTH) > payload.length) {
                if (_log.shouldWarn())
                    _log.warn("Short reply");
                waiter.gotReply(false);
                tr.gotError();
                return;
            }
            if (_log.shouldInfo())
                _log.info("Rcvd " + peers + " peers from " + tr);
            Set<Hash> hashes;
            if (peers > 0) {
                hashes = new HashSet<Hash>(peers);
                for (int off = 20; off < payload.length; off += Hash.HASH_LENGTH) {
                    hashes.add(Hash.create(payload, off));
                }
            } else {
                hashes = Collections.emptySet();
            }
            TrackerResponse resp = new TrackerResponse(interval, seeds, leeches, hashes);
            waiter.gotResponse(resp);
            tr.setInterval(interval);
        } else {
            waiter.gotReply(false);
            tr.gotError();
        }
    }

    private void receiveError(ReplyWaiter waiter, byte[] payload) {
        String msg;
        if (payload.length > 8) {
            msg = DataHelper.getUTF8(payload, 8, payload.length - 8);
        } else {
            msg = "";
        }
        TrackerResponse resp = new TrackerResponse(msg);
        waiter.gotResponse(resp);
        Tracker tr = waiter.getSentTo();
        tr.gotError();
    }

    // I2PSessionMuxedListener interface ----------------

    /**
     * Instruct the client that the given session has received a message
     *
     * Will be called only if you register via addMuxedSessionListener().
     * Will be called only for the proto(s) and toPort(s) you register for.
     *
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message - why it's a long and not an int is a mystery
     * @param proto 1-254 or 0 for unspecified
     * @param fromPort 1-65535 or 0 for unspecified
     * @param toPort 1-65535 or 0 for unspecified
     */
    public void messageAvailable(I2PSession session, int msgId, long size, int proto, int fromPort, int toPort) {
        // TODO throttle
        try {
            byte[] payload = session.receiveMessage(msgId);
            if (payload == null)
                return;
            if (toPort == _rPort) {
                // raw
                receiveMessage(null, fromPort, payload);
            } else {
                if (_log.shouldWarn())
                    _log.warn("msg on bad port");
            }
        } catch (I2PSessionException e) {
            if (_log.shouldWarn())
                _log.warn("bad msg");
        }
    }

    /** for non-muxed */
    public void messageAvailable(I2PSession session, int msgId, long size) {}

    public void reportAbuse(I2PSession session, int severity) {}

    public void disconnected(I2PSession session) {
        if (_log.shouldWarn())
            _log.warn("UDPTC disconnected");
    }

    public void errorOccurred(I2PSession session, String message, Throwable error) {
        if (_log.shouldWarn())
            _log.warn("UDPTC got error msg: ", error);
    }

    public static class TrackerResponse {

        private final int interval, complete, incomplete;
        private final String error;
        private final Set<Hash> peers;

        /** success */
        public TrackerResponse(int interval, int seeds, int leeches, Set<Hash> peers) {
            this.interval = interval;
            complete = seeds;
            incomplete = leeches;
            this.peers = peers;
            error = null;
        }

        /** failure */
        public TrackerResponse(String errorMsg) {
            interval = DEFAULT_INTERVAL;
            complete = 0;
            incomplete = 0;
            peers = null;
            error = errorMsg;
        }

        public Set<Hash> getPeers() {
            return peers;
        }

        public int getPeerCount() {
            int pc = peers == null ? 0 : peers.size();
            return Math.max(pc, complete + incomplete - 1);
        }

        public int getSeedCount() {
            return complete;
        }

        public int getLeechCount() {
            return incomplete;
        }

        public String getFailureReason() {
            return error;
        }

        /** in seconds */
        public int getInterval() {
            return interval;
        }
    }

    private static class HostPort {

        protected final String host;
        protected final int port;

        /**
         *  @param port the announce port
         */
        public HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /**
         *  @return the announce port
         */
        public int getPort() {
            return port;
        }

        @Override
        public int hashCode() {
            return host.hashCode() ^ port;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || !(o instanceof HostPort))
                return false;
            HostPort dp = (HostPort) o;
            return port == dp.port && host.equals(dp.host);
        }

        @Override
        public String toString() {
            return "UDP Tracker " + host + ':' + port;
        }
    }

    private class Tracker extends HostPort {

        private final Object destLock = new Object();
        private Destination dest;
        private long expires;
        private long lastHeardFrom;
        private long lastFailed;
        private int consecFails;
        private int interval = DEFAULT_INTERVAL;

        private static final long DELAY = 15*1000;

        public Tracker(String host, int port) {
            super(host, port);
        }

        /**
         *  @param fast if true, do not lookup
         *  @return dest or null
         */
        public Destination getDest(boolean fast) {
            synchronized(destLock) {
                if (dest == null && !fast)
                    dest = _util.getDestination(host);
                return dest;
            }
        }

        /** does not change state */
        public synchronized void replyTimeout() {
            consecFails++;
            lastFailed = _context.clock().now();
        }

        public synchronized int getInterval() {
            return interval;
        }

        /** sets heardFrom; calls notify */
        public synchronized void setInterval(int interval) {
            long now = _context.clock().now();
            lastHeardFrom = now;
            consecFails = 0;
            this.interval = interval;
            this.notifyAll();
        }

        /** sets heardFrom; calls notify */
        public synchronized void gotError() {
            long now = _context.clock().now();
            lastHeardFrom = now;
            consecFails = 0;
            this.notifyAll();
        }

        /** doubled for each consecutive failure */
        public synchronized long getTimeout() {
            return DEFAULT_TIMEOUT << Math.min(consecFails, 3);
        }

        @Override
        public String toString() {
            return "UDP Tracker " + host + ':' + port + " hasDest? " + (dest != null);
        }
    }

    /**
     * Callback for replies
     */
    private class ReplyWaiter extends SimpleTimer2.TimedEvent {
        private final int tid;
        private final Tracker sentTo;
        private final byte[] data;
        private TrackerResponse replyObject;
        private WaitState state = WaitState.INIT;

        /**
         *  Either wait on this object with a timeout, or use non-null Runnables.
         *  Any sent data to be remembered may be stored by setSentObject().
         *  Reply object may be in getReplyObject().
         */
        public ReplyWaiter(int tid, Tracker tracker, byte[] payload, long toWait) {
            super(SimpleTimer2.getInstance(), toWait);
            this.tid = tid;
            sentTo = tracker;
            data = payload;
        }

        public int getID() {
            return tid;
        }

        public Tracker getSentTo() {
            return sentTo;
        }

        public byte[] getPayload() {
            return data;
        }

        /**
         *  @return may be null depending on what happened. Cast to expected type.
         */
        public synchronized TrackerResponse getReplyObject() {
            return replyObject;
        }

        /**
         *  If true, we got a reply, and getReplyObject() may contain something.
         */
        public synchronized WaitState getState() {
            return state;
        }

        /**
         *  Will notify this.
         *  Also removes from _sentQueries and calls heardFrom().
         *  Sets state to SUCCESS or FAIL.
         */
        public synchronized void gotReply(boolean success) {
            cancel();
            _sentQueries.remove(Integer.valueOf(tid));
            setState(success ? WaitState.SUCCESS : WaitState.FAIL);
        }

        /**
         *  Will notify this and run onReply.
         *  Also removes from _sentQueries and calls heardFrom().
         */
        private synchronized void setState(WaitState state) {
            this.state = state;
            this.notifyAll();
        }

        /**
         *  Will notify this.
         *  Also removes from _sentQueries and calls heardFrom().
         *  Sets state to SUCCESS.
         */
        public synchronized void gotResponse(TrackerResponse resp) {
            replyObject = resp;
            gotReply(true);
        }

        /**
         *  Sets state to INIT.
         */
        @Override
        public synchronized void schedule(long toWait) {
            state = WaitState.INIT;
            super.schedule(toWait);
        }

        /** timer callback on timeout */
        public synchronized void timeReached() {
            // don't trump success or failure
            if (state != WaitState.INIT)
                return;
            //if (action == ACTION_CONNECT)
            //    sentTo.connFailed();
            //else
                sentTo.replyTimeout();
            setState(WaitState.TIMEOUT);
            if (_log.shouldWarn())
                _log.warn("timeout waiting for reply from " + sentTo);
        }

        @Override
        public String toString() {
            return "Waiting for ID: " + tid + " to: " + sentTo + " state: " + state;
        }
    }
}
