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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
  private static final Log _log = new Log(TrackerClient.class);
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
  private final PeerCoordinator coordinator;
  private final int port;

  private boolean stop;
  private boolean started;

  private List trackers;

  public TrackerClient(I2PSnarkUtil util, MetaInfo meta, PeerCoordinator coordinator)
  {
    // Set unique name.
    super("TrackerClient-" + urlencode(coordinator.getID()));
    _util = util;
    this.meta = meta;
    this.coordinator = coordinator;

    this.port = 6881; //(port == -1) ? 9 : port;

    stop = false;
    started = false;
  }

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
  
  public void run()
  {
    String infoHash = urlencode(meta.getInfoHash());
    String peerID = urlencode(coordinator.getID());

    _log.debug("Announce: [" + meta.getAnnounce() + "] infoHash: " + infoHash);
    
    // Construct the list of trackers for this torrent,
    // starting with the primary one listed in the metainfo,
    // followed by the secondary open trackers
    // It's painful, but try to make sure if an open tracker is also
    // the primary tracker, that we don't add it twice.
    trackers = new ArrayList(2);
    trackers.add(new Tracker(meta.getAnnounce(), true));
    List tlist = _util.getOpenTrackers();
    if (tlist != null) {
        for (int i = 0; i < tlist.size(); i++) {
             String url = (String)tlist.get(i);
             if (!url.startsWith("http://")) {
                _log.error("Bad announce URL: [" + url + "]");
                continue;
             }
             int slash = url.indexOf('/', 7);
             if (slash <= 7) {
                _log.error("Bad announce URL: [" + url + "]");
                continue;
             }
             if (meta.getAnnounce().startsWith(url.substring(0, slash)))
                continue;
             String dest = _util.lookup(url.substring(7, slash));
             if (dest == null) {
                _log.error("Announce host unknown: [" + url + "]");
                continue;
             }
             if (meta.getAnnounce().startsWith("http://" + dest))
                continue;
             if (meta.getAnnounce().startsWith("http://i2p/" + dest))
                continue;
             trackers.add(new Tracker(url, false));
             _log.debug("Additional announce: [" + url + "] for infoHash: " + infoHash);
        }
    }

    long uploaded = coordinator.getUploaded();
    long downloaded = coordinator.getDownloaded();
    long left = coordinator.getLeft();

    boolean completed = (left == 0);
    int sleptTime = 0;

    try
      {
        if (!verifyConnected()) return;
        boolean started = false;
        boolean firstTime = true;
        int consecutiveFails = 0;
        Random r = new Random();
        while(!stop)
          {
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
                } else if (completed && started)
                  delay = 3*SLEEP*60*1000 + random;
                else if (coordinator.trackerProblems != null && ++consecutiveFails < MAX_CONSEC_FAILS)
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
            left = coordinator.getLeft();
            
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
                  (event == COMPLETED_EVENT || System.currentTimeMillis() > tr.lastRequestTime + tr.interval))
              {
                try
                  {
                    if (!tr.started)
                      event = STARTED_EVENT;
                    TrackerInfo info = doRequest(tr, infoHash, peerID,
                                                 uploaded, downloaded, left,
                                                 event);

                    coordinator.trackerProblems = null;
                    tr.trackerProblems = null;
                    tr.registerFails = 0;
                    tr.consecutiveFails = 0;
                    if (tr.isPrimary)
                        consecutiveFails = 0;
                    started = true;
                    tr.started = true;

                    Set peers = info.getPeers();
                    tr.seenPeers = peers.size();
                    if (coordinator.trackerSeenPeers < tr.seenPeers) // update rising number quickly
                        coordinator.trackerSeenPeers = tr.seenPeers;
                    if ( (left > 0) && (!completed) ) {
                        // we only want to talk to new people if we need things
                        // from them (duh)
                        List ordered = new ArrayList(peers);
                        Collections.shuffle(ordered);
                        Iterator it = ordered.iterator();
                        while (it.hasNext()) {
                          Peer cur = (Peer)it.next();
                          // only delay if we actually make an attempt to add peer
                          if(coordinator.addPeer(cur)) {
                            int delay = DELAY_MUL;
                            delay *= ((int)cur.getPeerID().getAddress().calculateHash().toBase64().charAt(0)) % 10;
                            delay += DELAY_MIN;
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
                      coordinator.trackerProblems = tr.trackerProblems;
                    if (tr.trackerProblems.toLowerCase().startsWith(NOT_REGISTERED)) {
                      // Give a guy some time to register it if using opentrackers too
                      if (trackers.size() == 1) {
                        stop = true;
                        coordinator.snark.stopTorrent();
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

            // we could try and total the unique peers but that's too hard for now
            coordinator.trackerSeenPeers = maxSeenPeers;
            if (!started)
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
        try
          {
            // try to contact everybody we can
            // We don't need I2CP connection for eepget
            // if (!verifyConnected()) return;
            for (Iterator iter = trackers.iterator(); iter.hasNext(); ) {
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
    String s = tr.announce
      + "?info_hash=" + infoHash
      + "&peer_id=" + peerID
      + "&port=" + port
      + "&ip=" + _util.getOurIPString() + ".i2p"
      + "&uploaded=" + uploaded
      + "&downloaded=" + downloaded
      + "&left=" + left
      + ((event != NO_EVENT) ? ("&event=" + event) : "");
    _util.debug("Sending TrackerClient request: " + s, Snark.INFO);
      
    tr.lastRequestTime = System.currentTimeMillis();
    File fetched = _util.get(s);
    if (fetched == null) {
        throw new IOException("Error fetching " + s);
    }
    
    InputStream in = null;
    try {
        in = new FileInputStream(fetched);

        TrackerInfo info = new TrackerInfo(in, coordinator.getID(),
                                           coordinator.getMetaInfo());
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
   * Very lazy byte[] to URL encoder.  Just encodes everything, even
   * "normal" chars.
   */
  public static String urlencode(byte[] bs)
  {
    StringBuilder sb = new StringBuilder(bs.length*3);
    for (int i = 0; i < bs.length; i++)
      {
        int c = bs[i] & 0xFF;
        sb.append('%');
        if (c < 16)
          sb.append('0');
        sb.append(Integer.toHexString(c));
      }
         
    return sb.toString();
  }

  private class Tracker
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
