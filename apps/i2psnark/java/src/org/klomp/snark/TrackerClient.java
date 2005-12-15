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

import java.io.*;
import java.net.*;
import java.util.*;

import org.klomp.snark.bencode.*;
import net.i2p.util.Log;

/**
 * Informs metainfo tracker of events and gets new peers for peer
 * coordinator.
 *
 * @author Mark Wielaard (mark@klomp.org)
 */
public class TrackerClient extends Thread
{
  private static final Log _log = new Log(TrackerClient.class);
  private static final String NO_EVENT = "";
  private static final String STARTED_EVENT = "started";
  private static final String COMPLETED_EVENT = "completed";
  private static final String STOPPED_EVENT = "stopped";

  private final static int SLEEP = 5; // 5 minutes.

  private final MetaInfo meta;
  private final PeerCoordinator coordinator;
  private final int port;

  private boolean stop;

  private long interval;
  private long lastRequestTime;

  public TrackerClient(MetaInfo meta, PeerCoordinator coordinator)
  {
    // Set unique name.
    super("TrackerClient-" + urlencode(coordinator.getID()));
    this.meta = meta;
    this.coordinator = coordinator;

    this.port = 6881; //(port == -1) ? 9 : port;

    stop = false;
  }

  public void start() {
      stop = false;
      super.start();
  }
  
  public boolean halted() { return stop; }
  
  /**
   * Interrupts this Thread to stop it.
   */
  public void halt()
  {
    stop = true;
    this.interrupt();
  }

  public void run()
  {
    // XXX - Support other IPs
    String announce = meta.getAnnounce(); //I2PSnarkUtil.instance().rewriteAnnounce(meta.getAnnounce());
    String infoHash = urlencode(meta.getInfoHash());
    String peerID = urlencode(coordinator.getID());

    _log.debug("Announce: [" + meta.getAnnounce() + "] infoHash: " + infoHash 
               + " xmitAnnounce: [" + announce + "]");
    
    long uploaded = coordinator.getUploaded();
    long downloaded = coordinator.getDownloaded();
    long left = coordinator.getLeft();

    boolean completed = (left == 0);

    try
      {
        boolean started = false;
        while (!started)
          {
            try
              {
                // Send start.
                TrackerInfo info = doRequest(announce, infoHash, peerID,
                                             uploaded, downloaded, left,
                                             STARTED_EVENT);
                if (!completed) {
                    Iterator it = info.getPeers().iterator();
                    while (it.hasNext()) {
                      Peer cur = (Peer)it.next();
                      coordinator.addPeer(cur);
                      int delay = 3000;
                      int c = ((int)cur.getPeerID().getAddress().calculateHash().toBase64().charAt(0)) % 10;
                      try { Thread.sleep(delay * c); } catch (InterruptedException ie) {}
                    }
                }
                started = true;
              }
            catch (IOException ioe)
              {
                // Probably not fatal (if it doesn't last to long...)
                Snark.debug
                  ("WARNING: Could not contact tracker at '"
                   + announce + "': " + ioe, Snark.WARNING);
              }

            if (!started && !stop)
              {
                Snark.debug("         Retrying in one minute...", Snark.DEBUG);
                try
                  {
                    // Sleep one minutes...
                    Thread.sleep(60*1000);
                  }
                catch(InterruptedException interrupt)
                  {
                    // ignore
                  }
              }
          }

        while(!stop)
          {
            try
              {
                // Sleep some minutes...
                Thread.sleep(SLEEP*60*1000);
              }
            catch(InterruptedException interrupt)
              {
                // ignore
              }

            if (stop)
              break;
            
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
            
            // Only do a request when necessary.
            if (event == COMPLETED_EVENT
                || coordinator.needPeers()
                || System.currentTimeMillis() > lastRequestTime + interval)
              {
                try
                  {
                    TrackerInfo info = doRequest(announce, infoHash, peerID,
                                                 uploaded, downloaded, left,
                                                 event);

                    if ( (left > 0) && (!completed) ) {
                        // we only want to talk to new people if we need things
                        // from them (duh)
                        Iterator it = info.getPeers().iterator();
                        while (it.hasNext()) {
                          Peer cur = (Peer)it.next();
                          coordinator.addPeer(cur);
                          int delay = 3000;
                          int c = ((int)cur.getPeerID().getAddress().calculateHash().toBase64().charAt(0)) % 10;
                          try { Thread.sleep(delay * c); } catch (InterruptedException ie) {}
                        }
                    }
                  }
                catch (IOException ioe)
                  {
                    // Probably not fatal (if it doesn't last to long...)
                    Snark.debug
                      ("WARNING: Could not contact tracker at '"
                       + announce + "': " + ioe, Snark.WARNING);
                  }
              }
          }
      }
    catch (Throwable t)
      {
        Snark.debug("TrackerClient: " + t, Snark.ERROR);
        t.printStackTrace();
      }
    finally
      {
        try
          {
            TrackerInfo info = doRequest(announce, infoHash, peerID, uploaded,
                                         downloaded, left, STOPPED_EVENT);
          }
        catch(IOException ioe) { /* ignored */ }
      }
    
  }
  
  private TrackerInfo doRequest(String announce, String infoHash,
                                String peerID, long uploaded,
                                long downloaded, long left, String event)
    throws IOException
  {
    String s = announce
      + "?info_hash=" + infoHash
      + "&peer_id=" + peerID
      + "&port=" + port
      + "&ip=" + I2PSnarkUtil.instance().getOurIPString() + ".i2p"
      + "&uploaded=" + uploaded
      + "&downloaded=" + downloaded
      + "&left=" + left
      + ((event != NO_EVENT) ? ("&event=" + event) : "");
    if (Snark.debug >= Snark.INFO)
      Snark.debug("Sending TrackerClient request: " + s, Snark.INFO);
      
    File fetched = I2PSnarkUtil.instance().get(s);
    if (fetched == null) {
        throw new IOException("Error fetching " + s);
    }
    
    fetched.deleteOnExit();
    InputStream in = new FileInputStream(fetched);

    TrackerInfo info = new TrackerInfo(in, coordinator.getID(),
                                       coordinator.getMetaInfo());
    if (Snark.debug >= Snark.INFO)
      Snark.debug("TrackerClient response: " + info, Snark.INFO);
    lastRequestTime = System.currentTimeMillis();
    
    String failure = info.getFailureReason();
    if (failure != null)
      throw new IOException(failure);
    
    interval = info.getInterval() * 1000;
    return info;
  }

  /**
   * Very lazy byte[] to URL encoder.  Just encodes everything, even
   * "normal" chars.
   */
  static String urlencode(byte[] bs)
  {
    StringBuffer sb = new StringBuffer(bs.length*3);
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
}
