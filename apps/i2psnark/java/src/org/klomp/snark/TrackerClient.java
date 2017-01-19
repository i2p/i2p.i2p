/* TrackerClient - Class that informs a tracker and gets new peers.
   Copyright (C) 2003 Mark J. Wielaard

   This file is part of Snark.
   
   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; either version 2, or (at your option)
   any later version.
 
   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.
 
   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software Foundation,
   Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/

package org.klomp.snark;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.ConvertToHash;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

import org.klomp.snark.bencode.InvalidBEncodingException;
import org.klomp.snark.dht.DHT;

/**
 * Informs metainfo tracker of events and gets new peers for peer
 * coordinator.
 *
 * start() creates a thread and starts it.
 * At the end of each run, a TimedEvent is queued on the SimpleTimer2 queue.
 * The TimedEvent creates a new thread and starts it, so it does not
 * clog SimpleTimer2.
 *
 * The thread runs one pass through the trackers, the PEX, and the DHT,
 * then queues a new TimedEvent and exits.
 *
 * Thus there are only threads that are actively announcing, not one thread per torrent forever.
 *
 * start() may be called again after halt().
 *
 * @author Mark Wielaard (mark@klomp.org)
 */
public class TrackerClient implements Runnable {
  private final Log _log;
  private static final String NO_EVENT = "";
  private static final String STARTED_EVENT = "started";
  private static final String COMPLETED_EVENT = "completed";
  private static final String STOPPED_EVENT = "stopped";
  private static final String NOT_REGISTERED  = "torrent not registered"; //bytemonsoon
  private static final String NOT_REGISTERED_2  = "torrent not found";    // diftracker
  private static final String NOT_REGISTERED_3  = "torrent unauthorised"; // vuze
  private static final String ERROR_GOT_HTML  = "received html";             // fake return

  private final static int SLEEP = 5; // 5 minutes.
  private final static int DELAY_MIN = 2000; // 2 secs.
  private final static int DELAY_RAND = 6*1000;
  private final static int MAX_REGISTER_FAILS = 10; // * INITIAL_SLEEP = 15m to register
  private final static int INITIAL_SLEEP = 90*1000;
  private final static int MAX_CONSEC_FAILS = 5;    // slow down after this
  private final static int LONG_SLEEP = 30*60*1000; // sleep a while after lots of fails
  private final static long MIN_TRACKER_ANNOUNCE_INTERVAL = 15*60*1000;
  private final static long MIN_DHT_ANNOUNCE_INTERVAL = 39*60*1000;
  /** No guidance in BEP 5; standard practice is K (=8) */
  private static final int DHT_ANNOUNCE_PEERS = 4;
  public static final int PORT = 6881;
  private static final int MAX_TRACKERS = 12;
  // tracker.welterde.i2p
  private static final Hash DSA_ONLY_TRACKER = ConvertToHash.getHash("cfmqlafjfmgkzbt4r3jsfyhgsr5abgxryl6fnz3d3y5a365di5aa.b32.i2p");

  private final I2PSnarkUtil _util;
  private final MetaInfo meta;
  private final String infoHash;
  private final String peerID;
  private final String additionalTrackerURL;
  private final PeerCoordinator coordinator;
  private final Snark snark;
  private final int port;
  private final String _threadName;

  private volatile boolean stop = true;
  private volatile boolean started;
  private volatile boolean _initialized;
  private volatile int _runCount;
  // running thread so it can be interrupted
  private volatile Thread _thread;
  // queued event so it can be cancelled
  private volatile SimpleTimer2.TimedEvent _event;
  // these 2 used in loop()
  private volatile boolean runStarted;
  private volatile  int consecutiveFails;
  // if we don't want anything else.
  // Not necessarily seeding, as we may have skipped some files.
  private boolean completed;
  private volatile boolean _fastUnannounce;
  private long lastDHTAnnounce;
  private final List<TCTracker> trackers;
  private final List<TCTracker> backupTrackers;
  private long _startedOn;

  /**
   * Call start() to start it.
   *
   * @param meta null if in magnet mode
   * @param additionalTrackerURL may be null, from the ?tr= param in magnet mode, otherwise ignored
   */
  public TrackerClient(I2PSnarkUtil util, MetaInfo meta, String additionalTrackerURL,
                       PeerCoordinator coordinator, Snark snark)
  {
    super();
    // Set unique name.
    String id = urlencode(snark.getID());
    _threadName = "TrackerClient " + id.substring(id.length() - 12);
    _util = util;
    _log = util.getContext().logManager().getLog(TrackerClient.class);
    this.meta = meta;
    this.additionalTrackerURL = additionalTrackerURL;
    this.coordinator = coordinator;
    this.snark = snark;

    this.port = PORT; //(port == -1) ? 9 : port;
    this.infoHash = urlencode(snark.getInfoHash());
    this.peerID = urlencode(snark.getID());
    this.trackers = new ArrayList<TCTracker>(2);
    this.backupTrackers = new ArrayList<TCTracker>(2);
  }

  public synchronized void start() {
      if (!stop) {
          if (_log.shouldLog(Log.WARN))
              _log.warn("Already started: " + _threadName);
          return;
      }
      stop = false;
      consecutiveFails = 0;
      runStarted = false;
      _fastUnannounce = false;
      snark.setTrackerProblems(null);
      _thread = new I2PAppThread(this, _threadName + " #" + (++_runCount), true);
      _thread.start();
      started = true;
  }
  
  public boolean halted() { return stop; }
  public boolean started() { return started; }
  
  /**
   * Interrupts this Thread to stop it.
   * @param fast if true, limit the life of the unannounce threads
   */
  public synchronized void halt(boolean fast) {
    boolean wasStopped = stop;
    if (wasStopped) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("Already stopped: " + _threadName);
    } else {
        if (_log.shouldLog(Log.WARN))
            _log.warn("Stopping: " + _threadName);
        stop = true;
    }
    SimpleTimer2.TimedEvent e = _event;
    if (e != null) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Cancelling next announce " + _threadName);
        e.cancel();
        _event = null;
    }
    Thread t = _thread;
    if (t != null) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Interrupting " + t.getName());
        t.interrupt();
    }
    _fastUnannounce = true;
    if (!wasStopped)
        unannounce();
  }

  private void queueLoop(long delay) {
      _event = new Runner(delay);
  }

  private class Runner extends SimpleTimer2.TimedEvent {
      public Runner(long delay) {
          super(_util.getContext().simpleTimer2(), delay);
      }

      public void timeReached() {
          _event = null;
          _thread = new I2PAppThread(TrackerClient.this, _threadName + " #" + (++_runCount), true);
          _thread.start();
      }
  }

  private boolean verifyConnected() {
    while (!stop && !_util.connected()) {
        boolean ok = _util.connect();
        if (!ok) {
            try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
        }
    }
    return !stop && _util.connected();
  }
  
  /**
   *  Setup the first time only,
   *  then one pass (usually) through the trackers, PEX, and DHT.
   *  This will take several seconds to several minutes.
   */
  public void run() {
      long begin = _util.getContext().clock().now();
      if (_log.shouldLog(Log.DEBUG))
          _log.debug("Start " + Thread.currentThread().getName());
      try {
          if (!_initialized) {
              setup();
          }
          if (trackers.isEmpty() && _util.getDHT() == null) {
              stop = true;
              this.snark.addMessage(_util.getString("No valid trackers for {0} - enable opentrackers or DHT?",
                                              this.snark.getBaseName()));
              _log.error("No valid trackers for " + this.snark.getBaseName());
              this.snark.stopTorrent();
              return;
          }
          if (!_initialized) {
              _initialized = true;
              // FIXME only when starting everybody at once, not for a single torrent
              long delay = _util.getContext().random().nextInt(30*1000);
              try {
                  Thread.sleep(delay);
              } catch (InterruptedException ie) {}
          }
          loop();
      } finally {
          // don't hold ref
          _thread = null;
          if (_log.shouldLog(Log.DEBUG))
              _log.debug("Finish " + Thread.currentThread().getName() +
                         " after " + DataHelper.formatDuration(_util.getContext().clock().now() - begin));
      }
  }

  /**
   *  Do this one time only (not every time it is started).
   *  @since 0.9.1
   */
  private void setup() {
    // Construct the list of trackers for this torrent,
    // starting with the primary one listed in the metainfo,
    // followed by the secondary open trackers
    // It's painful, but try to make sure if an open tracker is also
    // the primary tracker, that we don't add it twice.
    // todo: check for b32 matches as well
    String primary = null;
    if (meta != null)
        primary = meta.getAnnounce();
    else if (additionalTrackerURL != null)
        primary = additionalTrackerURL;
    Set<Hash> trackerHashes = new HashSet<Hash>(8);

    // primary tracker
    if (primary != null) {
        if (isNewValidTracker(trackerHashes, primary)) {
            trackers.add(new TCTracker(primary, true));
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Announce: [" + primary + "] infoHash: " + infoHash);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Skipping invalid or non-i2p announce: " + primary);
        }
    } else {
        _log.warn("No primary announce");
    }

    // announce list
    // We completely ignore the BEP 12 processing rules
    if (meta != null && !meta.isPrivate()) {
        List<List<String>> list = meta.getAnnounceList();
        if (list != null) {
            for (List<String> llist : list) {
                for (String url : llist) {
                    if (!isNewValidTracker(trackerHashes, url))
                        continue;
                    trackers.add(new TCTracker(url, trackers.isEmpty()));
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Additional announce (list): [" + url + "] for infoHash: " + infoHash);
                }
            }
            if (trackers.size() > 2) {
                // shuffle everything but the primary
                TCTracker pri = trackers.remove(0);
                Collections.shuffle(trackers, _util.getContext().random());
                trackers.add(0, pri);
            }
        }
    }

    // configured open trackers
    if (meta == null || !meta.isPrivate()) {
        List<String> tlist = _util.getOpenTrackers();
        for (int i = 0; i < tlist.size(); i++) {
            String url = tlist.get(i);
            if (!isNewValidTracker(trackerHashes, url))
                continue;
            // opentrackers are primary if we don't have primary
            trackers.add(new TCTracker(url, trackers.isEmpty()));
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Additional announce: [" + url + "] for infoHash: " + infoHash);
        }
    }

    // backup trackers if DHT needs bootstrapping
    if (trackers.isEmpty() && (meta == null || !meta.isPrivate())) {
        List<String> tlist = _util.getBackupTrackers();
        for (int i = 0; i < tlist.size(); i++) {
            String url = tlist.get(i);
            if (!isNewValidTracker(trackerHashes, url))
                continue;
            backupTrackers.add(new TCTracker(url, false));
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Backup announce: [" + url + "] for infoHash: " + infoHash);
        }
        if (backupTrackers.isEmpty()) {
            backupTrackers.add(new TCTracker(SnarkManager.DEFAULT_BACKUP_TRACKER, false));
        } else if (trackers.size() > 1) {
            Collections.shuffle(backupTrackers, _util.getContext().random());
        }
    }
    this.completed = coordinator.getLeft() == 0;
    _startedOn = _util.getContext().clock().now();
  }

  /**
   *  @param existing the ones we already know about
   *  @param ann an announce URL non-null
   *  @return true if ann is valid and new; adds to existing if returns true
   *  @since 0.9.5
   */
  private boolean isNewValidTracker(Set<Hash> existing, String ann) {
      Hash h = getHostHash(ann);
      if (h == null) {
          if (_log.shouldLog(Log.WARN))
              _log.warn("Bad announce URL: [" + ann + ']');
          return false;
      }
      // comment this out if tracker.welterde.i2p upgrades
      if (h.equals(DSA_ONLY_TRACKER)) {
          Destination dest = _util.getMyDestination();
          if (dest != null && dest.getSigType() != SigType.DSA_SHA1) {
              if (_log.shouldLog(Log.WARN))
                  _log.warn("Skipping incompatible tracker: " + ann);
              return false;
          }
      }
      if (existing.size() >= MAX_TRACKERS) {
          if (_log.shouldLog(Log.INFO))
              _log.info("Not using announce URL, we have enough: [" + ann + ']');
          return false;
      }
      boolean rv = existing.add(h);
      if (!rv) {
          if (_log.shouldLog(Log.INFO))
             _log.info("Dup announce URL: [" + ann + ']');
      }
      return rv;
  }

  /**
   *  Announce to all the trackers, get peers from PEX and DHT, then queue up a SimpleTimer2 event.
   *  This will take several seconds to several minutes.
   *  @since 0.9.1
   */
  private void loop() {
    try
      {
        // normally this will only go once, then call queueLoop() and return
        while(!stop)
          {
            if (!verifyConnected()) {
                stop = true;
                return;
            }

            // Local DHT tracker announce
            DHT dht = _util.getDHT();
            if (dht != null && (meta == null || !meta.isPrivate()))
                dht.announce(snark.getInfoHash(), coordinator.completed());

            int oldSeenPeers = snark.getTrackerSeenPeers();
            int maxSeenPeers = 0;
            if (!trackers.isEmpty()) {
                maxSeenPeers = getPeersFromTrackers(trackers);
                // fast update for UI at startup
                if (maxSeenPeers > oldSeenPeers)
                    snark.setTrackerSeenPeers(maxSeenPeers);
            }
            int p = getPeersFromPEX();
            if (p > maxSeenPeers)
                maxSeenPeers = p;
            p = getPeersFromDHT();
            if (p > maxSeenPeers) {
                maxSeenPeers = p;
                // fast update for UI at startup
                if (maxSeenPeers > oldSeenPeers)
                    snark.setTrackerSeenPeers(maxSeenPeers);
            }
            // backup if DHT needs bootstrapping
            if (trackers.isEmpty() && !backupTrackers.isEmpty() && dht != null && dht.size() < 16) {
                p = getPeersFromTrackers(backupTrackers);
                if (p > maxSeenPeers)
                    maxSeenPeers = p;
            }

            // we could try and total the unique peers but that's too hard for now
            snark.setTrackerSeenPeers(maxSeenPeers);

            if (stop)
                return;

            try {
                // Sleep some minutes...
                // Sleep the minimum interval for all the trackers, but 60s minimum
                int delay;
                Random r = _util.getContext().random();
                int random = r.nextInt(120*1000);
                if (completed && runStarted)
                  delay = 3*SLEEP*60*1000 + random;
                else if (snark.getTrackerProblems() != null && ++consecutiveFails < MAX_CONSEC_FAILS)
                  delay = INITIAL_SLEEP;
                else if ((!runStarted) && _runCount < MAX_CONSEC_FAILS)
                  delay = INITIAL_SLEEP;
                else
                  // sleep a while, when we wake up we will contact only the trackers whose intervals have passed
                  delay = SLEEP*60*1000 + random;

                if (delay > 20*1000) {
                  // put ourselves on SimpleTimer2
                  if (_log.shouldLog(Log.DEBUG))
                      _log.debug("Requeueing in " + DataHelper.formatDuration(delay) + ": " + Thread.currentThread().getName());
                  queueLoop(delay);
                  return;
                } else if (delay > 0) {
                  Thread.sleep(delay);
                }
              } catch(InterruptedException interrupt) {}
          } // *** end of while loop
      } // try
    catch (Throwable t)
      {
        _log.error("TrackerClient: " + t, t);
        if (t instanceof OutOfMemoryError)
            throw (OutOfMemoryError)t;
      }
  }

  /**
   *  @return max peers seen
   */
  private int getPeersFromTrackers(List<TCTracker> trckrs) {
            long left = coordinator.getLeft();   // -1 in magnet mode
            
            // First time we got a complete download?
            boolean newlyCompleted;
            if (!completed && left == 0) {
                completed = true;
                newlyCompleted = true;
            } else {
                newlyCompleted = false;
            }

            // *** loop once for each tracker
            int maxSeenPeers = 0;
            for (TCTracker tr : trckrs) {
              if ((!stop) && (!tr.stop) &&
                  (completed || coordinator.needOutboundPeers() || !tr.started) &&
                  (newlyCompleted || System.currentTimeMillis() > tr.lastRequestTime + tr.interval))
              {
                try
                  {
                    long uploaded = coordinator.getUploaded();
                    long downloaded = coordinator.getDownloaded();
                    long len = snark.getTotalLength();
                    if (len > 0 && downloaded > len)
                        downloaded = len;
                    left = coordinator.getLeft();
                    String event;
                    if (!tr.started) {
                        event = STARTED_EVENT;
                    } else if (newlyCompleted) {
                        event = COMPLETED_EVENT;
                    } else {
                        event = NO_EVENT;
                    }
                    TrackerInfo info = doRequest(tr, infoHash, peerID,
                                                 uploaded, downloaded, left,
                                                 event);

                    snark.setTrackerProblems(null);
                    tr.trackerProblems = null;
                    tr.registerFails = 0;
                    tr.consecutiveFails = 0;
                    if (tr.isPrimary)
                        consecutiveFails = 0;
                    runStarted = true;
                    tr.started = true;
                    tr.seenPeers = info.getPeerCount();
                    if (snark.getTrackerSeenPeers() < tr.seenPeers) // update rising number quickly
                        snark.setTrackerSeenPeers(tr.seenPeers);

                    // auto stop
                    // These are very high thresholds for now, not configurable,
                    // just for update torrent
                    if (completed &&
                        tr.isPrimary &&
                        snark.isAutoStoppable() &&
                        !snark.isChecking() &&
                        info.getSeedCount() > 100 &&
                        coordinator.getPeerCount() <= 0 &&
                        _util.getContext().clock().now() > _startedOn + 30*60*1000 &&
                        snark.getTotalLength() > 0 &&
                        uploaded >= snark.getTotalLength() / 2) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Auto stopping " + snark.getBaseName());
                        snark.setAutoStoppable(false);
                        snark.stopTorrent();
                        return tr.seenPeers;
                    }

                    Set<Peer> peers = info.getPeers();

                    // pass everybody over to our tracker
                    DHT dht = _util.getDHT();
                    if (dht != null) {
                        for (Peer peer : peers) {
                            dht.announce(snark.getInfoHash(), peer.getPeerID().getDestHash(),
                                         false);  // TODO actual seed/leech status
                        }
                    }

                    if (coordinator.needOutboundPeers()) {
                        // we only want to talk to new people if we need things
                        // from them (duh)
                        List<Peer> ordered = new ArrayList<Peer>(peers);
                        Random r = _util.getContext().random();
                        Collections.shuffle(ordered, r);
                        Iterator<Peer> it = ordered.iterator();
                        while ((!stop) && it.hasNext() && coordinator.needOutboundPeers()) {
                          Peer cur = it.next();
                          // FIXME if id == us || dest == us continue;
                          // only delay if we actually make an attempt to add peer
                          if(coordinator.addPeer(cur) && it.hasNext()) {
                            int delay = r.nextInt(DELAY_RAND) + DELAY_MIN;
                            try { Thread.sleep(delay); } catch (InterruptedException ie) {}
                          }
                        }
                    }
                  }
                catch (IOException ioe)
                  {
                    // Probably not fatal (if it doesn't last to long...)
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Could not contact tracker at '" + tr.announce + "': " + ioe);
                    tr.trackerProblems = ioe.getMessage();
                    // don't show secondary tracker problems to the user
                    // ... and only if we don't have any peers at all. Otherwise, PEX/DHT will save us.
                    if (tr.isPrimary && coordinator.getPeers() <= 0 &&
                        (!completed || _util.getDHT() == null || _util.getDHT().size() <= 0))
                      snark.setTrackerProblems(tr.trackerProblems);
                    String tplc = tr.trackerProblems.toLowerCase(Locale.US);
                    if (tplc.startsWith(NOT_REGISTERED) || tplc.startsWith(NOT_REGISTERED_2) ||
                        tplc.startsWith(NOT_REGISTERED_3) || tplc.startsWith(ERROR_GOT_HTML)) {
                      // Give a guy some time to register it if using opentrackers too
                      //if (trckrs.size() == 1) {
                      //  stop = true;
                      //  snark.stopTorrent();
                      //} else { // hopefully each on the opentrackers list is really open
                        if (tr.registerFails++ > MAX_REGISTER_FAILS ||
                            !completed ||              // no use retrying if we aren't seeding
                            tplc.startsWith(ERROR_GOT_HTML) ||   // fake msg from doRequest()
                            (!tr.isPrimary && tr.registerFails > MAX_REGISTER_FAILS / 2))
                          if (_log.shouldLog(Log.WARN))
                              _log.warn("Not longer announcing to " + tr.announce + " : " +
                                        tr.trackerProblems + " after " + tr.registerFails + " failures");
                          tr.stop = true;
                      //
                    }
                    if (++tr.consecutiveFails == MAX_CONSEC_FAILS) {
                        tr.seenPeers = 0;
                        if (tr.interval < LONG_SLEEP)
                            tr.interval = LONG_SLEEP;  // slow down
                    }
                  }
              } else {
                  if (_log.shouldLog(Log.INFO))
                      _log.info("Not announcing to " + tr.announce + " last announce was " +
                               new Date(tr.lastRequestTime) + " interval is " + DataHelper.formatDuration(tr.interval));
              }
              if ((!tr.stop) && maxSeenPeers < tr.seenPeers)
                  maxSeenPeers = tr.seenPeers;
            }  // *** end of trackers loop here

            return maxSeenPeers;
  }

  /**
   *  @return max peers seen
   */
  private int getPeersFromPEX() {
            // Get peers from PEX
            int rv = 0;
            if (coordinator.needOutboundPeers() && (meta == null || !meta.isPrivate()) && !stop) {
                Set<PeerID> pids = coordinator.getPEXPeers();
                if (!pids.isEmpty()) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Got " + pids.size() + " from PEX");
                    List<Peer> peers = new ArrayList<Peer>(pids.size());
                    for (PeerID pID : pids) {
                        peers.add(new Peer(pID, snark.getID(), snark.getInfoHash(), snark.getMetaInfo()));
                    }
                    Random r = _util.getContext().random();
                    Collections.shuffle(peers, r);
                    Iterator<Peer> it = peers.iterator();
                    while ((!stop) && it.hasNext() && coordinator.needOutboundPeers()) {
                        Peer cur = it.next();
                        if (coordinator.addPeer(cur) && it.hasNext()) {
                            int delay = r.nextInt(DELAY_RAND) + DELAY_MIN;
                            try { Thread.sleep(delay); } catch (InterruptedException ie) {}
                         }
                    }
                    rv = pids.size();
                    pids.clear();
                }
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Not getting PEX peers");
            }
            return rv;
    }

  /**
   *  @return max peers seen
   */
  private int getPeersFromDHT() {
            // Get peers from DHT
            // FIXME this needs to be in its own thread
            int rv = 0;
            DHT dht = _util.getDHT();
            if (dht != null &&
                (meta == null || !meta.isPrivate()) &&
                (!stop) &&
                (meta == null || _util.getContext().clock().now() >  lastDHTAnnounce + MIN_DHT_ANNOUNCE_INTERVAL)) {
                int numwant;
                if (!coordinator.needOutboundPeers())
                    numwant = 1;
                else
                    numwant = _util.getMaxConnections();
                Collection<Hash> hashes = dht.getPeersAndAnnounce(snark.getInfoHash(), numwant,
                                                                  5*60*1000, DHT_ANNOUNCE_PEERS, 3*60*1000,
                                                                  coordinator.completed(), numwant <= 1);
                if (!hashes.isEmpty()) {
                    runStarted = true;
                    lastDHTAnnounce = _util.getContext().clock().now();
                    rv = hashes.size();
                } else {
                    lastDHTAnnounce = 0;
                }
                if (_log.shouldLog(Log.INFO))
                    _log.info("Got " + hashes + " from DHT");

                // now try these peers
                if ((!stop) && !hashes.isEmpty()) {
                    List<Peer> peers = new ArrayList<Peer>(hashes.size());
                    for (Hash h : hashes) {
                        try {
                            PeerID pID = new PeerID(h.getData(), _util);
                            peers.add(new Peer(pID, snark.getID(), snark.getInfoHash(), snark.getMetaInfo()));
                        } catch (InvalidBEncodingException ibe) {}
                    }
                    Random r = _util.getContext().random();
                    Collections.shuffle(peers, r);
                    Iterator<Peer> it = peers.iterator();
                    while ((!stop) && it.hasNext() && coordinator.needOutboundPeers()) {
                        Peer cur = it.next();
                        if (coordinator.addPeer(cur) && it.hasNext()) {
                            int delay = r.nextInt(DELAY_RAND) + DELAY_MIN;
                            try { Thread.sleep(delay); } catch (InterruptedException ie) {}
                         }
                    }
                }
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Not getting DHT peers");
            }
            return rv;
  }


  /**
   *  Creates a thread for each tracker in parallel if tunnel is still open
   *  @since 0.9.1
   */
  private void unannounce() {
      // Local DHT tracker unannounce
      DHT dht = _util.getDHT();
      if (dht != null)
          dht.unannounce(snark.getInfoHash());
      int i = 0;
      for (TCTracker tr : trackers) {
          if (_util.connected() &&
              tr.started && (!tr.stop) && tr.trackerProblems == null) {
              try {
                  (new I2PAppThread(new Unannouncer(tr), _threadName + " U" + (++i), true)).start();
              } catch (OutOfMemoryError oom) {
                  // probably ran out of threads, ignore
                  tr.reset();
              }
          } else {
              tr.reset();
          }
      }
  }

  /**
   *  Send "stopped" to a single tracker
   *  @since 0.9.1
   */
  private class Unannouncer implements Runnable {
     private final TCTracker tr;

     public Unannouncer(TCTracker tr) {
         this.tr = tr;
     }

     public void run() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Running unannounce " + _threadName + " to " + tr.announce);
        long uploaded = coordinator.getUploaded();
        long downloaded = coordinator.getDownloaded();
        long len = snark.getTotalLength();
        if (len > 0 && downloaded > len)
            downloaded = len;
        long left = coordinator.getLeft();
        try
          {
            // Don't try to restart I2CP connection just to say goodbye
              if (_util.connected()) {
                  if (tr.started && (!tr.stop) && tr.trackerProblems == null)
                      doRequest(tr, infoHash, peerID, uploaded,
                                         downloaded, left, STOPPED_EVENT);
              }
          }
        catch(IOException ioe) { /* ignored */ }
        tr.reset();
     }
  }
  
  /**
   *
   *  Note: IOException message text gets displayed in the UI
   *
   */
  private TrackerInfo doRequest(TCTracker tr, String infoHash,
                                String peerID, long uploaded,
                                long downloaded, long left, String event)
    throws IOException
  {
    StringBuilder buf = new StringBuilder(512);
    buf.append(tr.announce);
    if (tr.announce.contains("?"))
        buf.append('&');
    else
        buf.append('?');
    buf.append("info_hash=").append(infoHash)
       .append("&peer_id=").append(peerID)
       .append("&port=").append(port)
       .append("&ip=" ).append(_util.getOurIPString()).append(".i2p")
       .append("&uploaded=").append(uploaded)
       .append("&downloaded=").append(downloaded)
       .append("&left=");
    // What do we send for left in magnet mode? Can we omit it?
    if (left >= 0)
        buf.append(left);
    else
        buf.append('1');
    buf.append("&compact=1");  // NOTE: opentracker will return 400 for &compact alone
    if (! event.equals(NO_EVENT))
        buf.append("&event=").append(event);
    buf.append("&numwant=");
    boolean small = left == 0 || event.equals(STOPPED_EVENT) || !coordinator.needOutboundPeers();
    if (small)
        buf.append('0');
    else
        buf.append(_util.getMaxConnections());
    String s = buf.toString();
    if (_log.shouldLog(Log.INFO))
        _log.info("Sending TrackerClient request: " + s);
      
    tr.lastRequestTime = System.currentTimeMillis();
    // Don't wait for a response to stopped when shutting down
    boolean fast = _fastUnannounce && event.equals(STOPPED_EVENT);
    byte[] fetched = _util.get(s, true, fast ? -1 : 0, small ? 128 : 1024, small ? 1024 : 32*1024);
    if (fetched == null)
        throw new IOException("No response from " + tr.host);
    if (fetched.length == 0)
        throw new IOException("No data from " + tr.host);
    // The HTML check only works if we didn't exceed the maxium fetch size specified in get(),
    // otherwise we already threw an IOE.
    if (fetched[0] == '<')
        throw new IOException(ERROR_GOT_HTML + " from " + tr.host);
    
        InputStream in = new ByteArrayInputStream(fetched);

        TrackerInfo info = new TrackerInfo(in, snark.getID(),
                                           snark.getInfoHash(), snark.getMetaInfo(), _util);
        if (_log.shouldLog(Log.INFO))
            _log.info("TrackerClient " + tr.host + " response: " + info);

        String failure = info.getFailureReason();
        if (failure != null)
            throw new IOException("Tracker " + tr.host + " responded with: " + failure);

        tr.interval = Math.max(MIN_TRACKER_ANNOUNCE_INTERVAL, info.getInterval() * 1000l);
        return info;
  }

  /**
   * Very lazy byte[] to URL encoder.  Just encodes almost everything, even
   * some "normal" chars.
   * By not encoding about 1/4 of the chars, we make random data like hashes about 16% smaller.
   *
   * RFC1738: 0-9a-zA-Z$-_.+!*'(),
   * Us:      0-9a-zA-Z
   *
   */
  public static String urlencode(byte[] bs)
  {
    StringBuilder sb = new StringBuilder(bs.length*3);
    for (int i = 0; i < bs.length; i++)
      {
        int c = bs[i] & 0xFF;
        if ((c >= '0' && c <= '9') ||
            (c >= 'A' && c <= 'Z') ||
            (c >= 'a' && c <= 'z')) {
            sb.append((char)c);
        } else {
            sb.append('%');
            if (c < 16)
              sb.append('0');
            sb.append(Integer.toHexString(c));
        }
      }
         
    return sb.toString();
  }

  /**
   *  @param ann an announce URL, may be null, returns false if null
   *  @return true for i2p hosts only
   *  @since 0.7.12
   */
  public static boolean isValidAnnounce(String ann) {
    if (ann == null)
        return false;
    URI url;
    try {
        url = new URI(ann);
    } catch (URISyntaxException use) {
        return false;
    }
    String path = url.getPath();
    if (path == null || !path.startsWith("/"))
        return false;
    return "http".equals(url.getScheme()) && url.getHost() != null &&
           (url.getHost().endsWith(".i2p") || url.getHost().equals("i2p"));
  }

  /**
   *  This also validates the URL.
   *
   *  @param ann an announce URL non-null
   *  @return a Hash for i2p hosts only, null otherwise
   *  @since 0.9.5
   */
  private static Hash getHostHash(String ann) {
    URI url;
    try {
        url = new URI(ann);
    } catch (URISyntaxException use) {
        return null;
    }
    if (!"http".equals(url.getScheme()))
        return null;
    String host = url.getHost();
    if (host == null)
        return null;
    if (host.endsWith(".i2p")) {
        String path = url.getPath();
        if (path == null || !path.startsWith("/"))
            return null;
        return ConvertToHash.getHash(host);
    }
    if (host.equals("i2p")) {
        String path = url.getPath();
        if (path == null || path.length() < 517 ||
            !path.startsWith("/"))
            return null;
        String[] parts = DataHelper.split(path.substring(1), "[/\\?&;]", 2);
        return ConvertToHash.getHash(parts[0]);
    }
    return null;
  }

  private static class TCTracker
  {
      final String announce;
      final String host;
      final boolean isPrimary;
      long interval;
      long lastRequestTime;
      String trackerProblems;
      boolean stop;
      boolean started;
      int registerFails;
      int consecutiveFails;
      int seenPeers;

      /**
       *  @param a must be a valid http URL with a path
       *  @param p true if primary
       */
      public TCTracker(String a, boolean p)
      {
          announce = a;
          String s = a.substring(7);
          host = s.substring(0, s.indexOf('/'));
          isPrimary = p;
          interval = INITIAL_SLEEP;
      }

      /**
       *  Call before restarting
       *  @since 0.9.1
       */
      public void reset() {
          lastRequestTime = 0;
          trackerProblems = null;
          stop = false;
          started = false;
          registerFails = 0;
          consecutiveFails = 0;
          seenPeers = 0;
      }
  }
}
