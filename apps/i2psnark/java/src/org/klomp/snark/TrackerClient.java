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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Informs metainfo tracker of events and gets new peers for peer
 * coordinator.
 *
 * @author Mark Wielaard (mark@klomp.org)
 */
public class TrackerClient extends I2PAppThread
{
  private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(TrackerClient.class);
  private static final String NO_EVENT = "";
  private static final String STARTED_EVENT = "started";
  private static final String COMPLETED_EVENT = "completed";
  private static final String STOPPED_EVENT = "stopped";
  private static final String NOT_REGISTERED  = "torrent not registered"; //bytemonsoon

  private final static int SLEEP = 5; // 5 minutes.
  private final static int DELAY_MIN = 2000; // 2 secs.
  private final static int DELAY_MUL = 1500; // 1.5 secs.
  private final static int MAX_REGISTER_FAILS = 10; // * INITIAL_SLEEP = 15m to register
  private final static int INITIAL_SLEEP = 90*1000;
  private final static int MAX_CONSEC_FAILS = 5;    // slow down after this
  private final static int LONG_SLEEP = 30*60*1000; // sleep a while after lots of fails

  private I2PSnarkUtil _util;
  private final MetaInfo meta;
  private final String additionalTrackerURL;
  private final PeerCoordinator coordinator;
  private final Snark snark;
  private final int port;

  private boolean stop;
  private boolean started;

  private List trackers;

  /**
   * @param meta null if in magnet mode
   * @param additionalTrackerURL may be null, from the ?tr= param in magnet mode, otherwise ignored
   */
  public TrackerClient(I2PSnarkUtil util, MetaInfo meta, String additionalTrackerURL,
                       PeerCoordinator coordinator, Snark snark)
  {
    super();
    // Set unique name.
    String id = urlencode(snark.getID());
    setName("TrackerClient " + id.substring(id.length() - 12));
    _util = util;
    this.meta = meta;
    this.additionalTrackerURL = additionalTrackerURL;
    this.coordinator = coordinator;
    this.snark = snark;

    this.port = 6881; //(port == -1) ? 9 : port;
  }

    @Override
  public void start() {
      if (stop) throw new RuntimeException("Dont rerun me, create a copy");
      super.start();
      started = true;
  }
  
  public boolean halted() { return stop; }
  public boolean started() { return started; }
  
  /**
   * Interrupts this Thread to stop it.
   */
  public void halt()
  {
    stop = true;
    this.interrupt();
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
  
    @Override
  public void run()
  {
    String infoHash = urlencode(snark.getInfoHash());
    String peerID = urlencode(snark.getID());


    // Construct the list of trackers for this torrent,
    // starting with the primary one listed in the metainfo,
    // followed by the secondary open trackers
    // It's painful, but try to make sure if an open tracker is also
    // the primary tracker, that we don't add it twice.
    // todo: check for b32 matches as well
    trackers = new ArrayList(2);
    String primary = null;
    if (meta != null)
        primary = meta.getAnnounce();
    else if (additionalTrackerURL != null)
        primary = additionalTrackerURL;
    if (primary != null) {
        if (isValidAnnounce(primary)) {
            trackers.add(new Tracker(primary, true));
            _log.debug("Announce: [" + primary + "] infoHash: " + infoHash);
        } else {
            _log.warn("Skipping invalid or non-i2p announce: " + primary);
        }
    } else {
        _log.warn("No primary announce");
        primary = "";
    }
    List tlist = _util.getOpenTrackers();
    if (tlist != null && (meta == null || !meta.isPrivate())) {
        for (int i = 0; i < tlist.size(); i++) {
             String url = (String)tlist.get(i);
             if (!isValidAnnounce(url)) {
                _log.error("Bad announce URL: [" + url + "]");
                continue;
             }
             int slash = url.indexOf('/', 7);
             if (slash <= 7) {
                _log.error("Bad announce URL: [" + url + "]");
                continue;
             }
             if (primary.startsWith(url.substring(0, slash)))
                continue;
             String dest = _util.lookup(url.substring(7, slash));
             if (dest == null) {
                _log.error("Announce host unknown: [" + url.substring(7, slash) + "]");
                continue;
             }
             if (primary.startsWith("http://" + dest))
                continue;
             if (primary.startsWith("http://i2p/" + dest))
                continue;
             // opentrackers are primary if we don't have primary
             trackers.add(new Tracker(url, primary.equals("")));
             _log.debug("Additional announce: [" + url + "] for infoHash: " + infoHash);
        }
    }

    if (trackers.isEmpty()) {
        stop = true;
        // FIXME translate
        SnarkManager.instance().addMessage("No valid trackers for " + this.snark.getBaseName() + " - enable opentrackers?");
        _log.error("No valid trackers for " + this.snark.getBaseName());
        // FIXME keep going if DHT enabled
        this.snark.stopTorrent();
        return;
    }

    long uploaded = coordinator.getUploaded();
    long downloaded = coordinator.getDownloaded();
    long left = coordinator.getLeft();

    boolean completed = (left == 0);
    int sleptTime = 0;

    try
      {
        if (!verifyConnected()) return;
        boolean runStarted = false;
        boolean firstTime = true;
        int consecutiveFails = 0;
        Random r = I2PAppContext.getGlobalContext().random();
        while(!stop)
          {
            // Local DHT tracker announce
            if (_util.getDHT() != null)
                _util.getDHT().announce(snark.getInfoHash());
            try
              {
                // Sleep some minutes...
                // Sleep the minimum interval for all the trackers, but 60s minimum
                // except for the first time...
                int delay;
                int random = r.nextInt(120*1000);
                if (firstTime) {
                  delay = r.nextInt(30*1000);
                  firstTime = false;
                } else if (completed && runStarted)
                  delay = 3*SLEEP*60*1000 + random;
                else if (snark.getTrackerProblems() != null && ++consecutiveFails < MAX_CONSEC_FAILS)
                  delay = INITIAL_SLEEP;
                else
                  // sleep a while, when we wake up we will contact only the trackers whose intervals have passed
                  delay = SLEEP*60*1000 + random;

                if (delay > 0)
                  Thread.sleep(delay);
              }
            catch(InterruptedException interrupt)
              {
                // ignore
              }

            if (stop)
              break;
       
            if (!verifyConnected()) return;
            
            uploaded = coordinator.getUploaded();
            downloaded = coordinator.getDownloaded();
            left = coordinator.getLeft();   // -1 in magnet mode
            
            // First time we got a complete download?
            String event;
            if (!completed && left == 0)
              {
                completed = true;
                event = COMPLETED_EVENT;
              }
            else
              event = NO_EVENT;
            
            // *** loop once for each tracker
            // Only do a request when necessary.
            sleptTime = 0;
            int maxSeenPeers = 0;
            for (Iterator iter = trackers.iterator(); iter.hasNext(); ) {
              Tracker tr = (Tracker)iter.next();
              if ((!stop) && (!tr.stop) &&
                  (completed || coordinator.needPeers()) &&
                  (event.equals(COMPLETED_EVENT) || System.currentTimeMillis() > tr.lastRequestTime + tr.interval))
              {
                try
                  {
                    if (!tr.started)
                      event = STARTED_EVENT;
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

                    Set<Peer> peers = info.getPeers();
                    tr.seenPeers = info.getPeerCount();
                    if (snark.getTrackerSeenPeers() < tr.seenPeers) // update rising number quickly
                        snark.setTrackerSeenPeers(tr.seenPeers);

                    // pass everybody over to our tracker
                    if (_util.getDHT() != null) {
                        for (Peer peer : peers) {
                            _util.getDHT().announce(snark.getInfoHash(), peer.getPeerID().getDestHash());
                        }
                    }

                    if ( (left != 0) && (!completed) ) {
                        // we only want to talk to new people if we need things
                        // from them (duh)
                        List<Peer> ordered = new ArrayList(peers);
                        Collections.shuffle(ordered, r);
                        Iterator<Peer> it = ordered.iterator();
                        while ((!stop) && it.hasNext()) {
                          Peer cur = it.next();
                          // FIXME if id == us || dest == us continue;
                          // only delay if we actually make an attempt to add peer
                          if(coordinator.addPeer(cur) && it.hasNext()) {
                            int delay = (DELAY_MUL * r.nextInt(10)) + DELAY_MIN;
                            sleptTime += delay;
                            try { Thread.sleep(delay); } catch (InterruptedException ie) {}
                          }
                        }
                    }
                  }
                catch (IOException ioe)
                  {
                    // Probably not fatal (if it doesn't last to long...)
                    _util.debug
                      ("WARNING: Could not contact tracker at '"
                       + tr.announce + "': " + ioe, Snark.WARNING);
                    tr.trackerProblems = ioe.getMessage();
                    // don't show secondary tracker problems to the user
                    if (tr.isPrimary)
                      snark.setTrackerProblems(tr.trackerProblems);
                    if (tr.trackerProblems.toLowerCase(Locale.US).startsWith(NOT_REGISTERED)) {
                      // Give a guy some time to register it if using opentrackers too
                      if (trackers.size() == 1) {
                        stop = true;
                        snark.stopTorrent();
                      } else { // hopefully each on the opentrackers list is really open
                        if (tr.registerFails++ > MAX_REGISTER_FAILS)
                          tr.stop = true;
                      }
                    }
                    if (++tr.consecutiveFails == MAX_CONSEC_FAILS) {
                        tr.seenPeers = 0;
                        if (tr.interval < LONG_SLEEP)
                            tr.interval = LONG_SLEEP;  // slow down
                    }
                  }
              }
              if ((!tr.stop) && maxSeenPeers < tr.seenPeers)
                  maxSeenPeers = tr.seenPeers;
            }  // *** end of trackers loop here

            // Get peers from PEX
            if (left > 0 && coordinator.needPeers() && (meta == null || !meta.isPrivate()) && !stop) {
                Set<PeerID> pids = coordinator.getPEXPeers();
                if (!pids.isEmpty()) {
                    _util.debug("Got " + pids.size() + " from PEX", Snark.INFO);
                    List<Peer> peers = new ArrayList(pids.size());
                    for (PeerID pID : pids) {
                        peers.add(new Peer(pID, snark.getID(), snark.getInfoHash(), snark.getMetaInfo()));
                    }
                    Collections.shuffle(peers, r);
                    Iterator<Peer> it = peers.iterator();
                    while ((!stop) && it.hasNext()) {
                        Peer cur = it.next();
                        if (coordinator.addPeer(cur) && it.hasNext()) {
                            int delay = (DELAY_MUL * r.nextInt(10)) + DELAY_MIN;
                            try { Thread.sleep(delay); } catch (InterruptedException ie) {}
                         }
                    }
                }
            }

            // Get peers from DHT
            // FIXME this needs to be in its own thread
            if (_util.getDHT() != null && (meta == null || !meta.isPrivate()) && !stop) {
                int numwant;
                if (left == 0 || event.equals(STOPPED_EVENT) || !coordinator.needPeers())
                    numwant = 1;
                else
                    numwant = _util.getMaxConnections();
                List<Hash> hashes = _util.getDHT().getPeers(snark.getInfoHash(), numwant, 2*60*1000);
                _util.debug("Got " + hashes + " from DHT", Snark.INFO);
                // announce  ourselves while the token is still good
                // FIXME this needs to be in its own thread
                if (!stop) {
                    int good = _util.getDHT().announce(snark.getInfoHash(), 8, 5*60*1000);
                    _util.debug("Sent " + good + " good announces to DHT", Snark.INFO);
                }

                // now try these peers
                if ((!stop) && !hashes.isEmpty()) {
                    List<Peer> peers = new ArrayList(hashes.size());
                    for (Hash h : hashes) {
                        PeerID pID = new PeerID(h.getData());
                        peers.add(new Peer(pID, snark.getID(), snark.getInfoHash(), snark.getMetaInfo()));
                    }
                    Collections.shuffle(peers, r);
                    Iterator<Peer> it = peers.iterator();
                    while ((!stop) && it.hasNext()) {
                        Peer cur = it.next();
                        if (coordinator.addPeer(cur) && it.hasNext()) {
                            int delay = (DELAY_MUL * r.nextInt(10)) + DELAY_MIN;
                            try { Thread.sleep(delay); } catch (InterruptedException ie) {}
                         }
                    }
                }
            }


            // we could try and total the unique peers but that's too hard for now
            snark.setTrackerSeenPeers(maxSeenPeers);
            if (!runStarted)
                _util.debug("         Retrying in one minute...", Snark.DEBUG);
          } // *** end of while loop
      } // try
    catch (Throwable t)
      {
        _util.debug("TrackerClient: " + t, Snark.ERROR, t);
        if (t instanceof OutOfMemoryError)
            throw (OutOfMemoryError)t;
      }
    finally
      {
        // Local DHT tracker unannounce
        if (_util.getDHT() != null)
            _util.getDHT().unannounce(snark.getInfoHash());
        try
          {
            // try to contact everybody we can
            // Don't try to restart I2CP connection just to say goodbye
            for (Iterator iter = trackers.iterator(); iter.hasNext(); ) {
              if (!_util.connected()) return;
              Tracker tr = (Tracker)iter.next();
              if (tr.started && (!tr.stop) && tr.trackerProblems == null)
                  doRequest(tr, infoHash, peerID, uploaded,
                                         downloaded, left, STOPPED_EVENT);
            }
          }
        catch(IOException ioe) { /* ignored */ }
      }
    
  }
  
  private TrackerInfo doRequest(Tracker tr, String infoHash,
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
    if (left == 0 || event.equals(STOPPED_EVENT) || !coordinator.needPeers())
        buf.append('0');
    else
        buf.append(_util.getMaxConnections());
    String s = buf.toString();
    _util.debug("Sending TrackerClient request: " + s, Snark.INFO);
      
    tr.lastRequestTime = System.currentTimeMillis();
    File fetched = _util.get(s);
    if (fetched == null) {
        throw new IOException("Error fetching " + s);
    }
    
    InputStream in = null;
    try {
        in = new FileInputStream(fetched);

        TrackerInfo info = new TrackerInfo(in, snark.getID(),
                                           snark.getInfoHash(), snark.getMetaInfo());
        _util.debug("TrackerClient response: " + info, Snark.INFO);

        String failure = info.getFailureReason();
        if (failure != null)
          throw new IOException(failure);

        tr.interval = info.getInterval() * 1000;
        return info;
    } finally {
        if (in != null) try { in.close(); } catch (IOException ioe) {}
        fetched.delete();
    }
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
   *  @return true for i2p hosts only
   *  @since 0.7.12
   */
  static boolean isValidAnnounce(String ann) {
    URL url;
    try {
       url = new URL(ann);
    } catch (MalformedURLException mue) {
       return false;
    }
    return url.getProtocol().equals("http") &&
           (url.getHost().endsWith(".i2p") || url.getHost().equals("i2p")) &&
           url.getPort() < 0;
  }

  private static class Tracker
  {
      String announce;
      boolean isPrimary;
      long interval;
      long lastRequestTime;
      String trackerProblems;
      boolean stop;
      boolean started;
      int registerFails;
      int consecutiveFails;
      int seenPeers;

      public Tracker(String a, boolean p)
      {
          announce = a;
          isPrimary = p;
          interval = INITIAL_SLEEP;
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
