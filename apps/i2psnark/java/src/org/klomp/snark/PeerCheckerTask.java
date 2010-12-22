/* PeerCheckTasks - TimerTask that checks for good/bad up/downloaders.
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.TimerTask;

import net.i2p.I2PAppContext;

/**
 * TimerTask that checks for good/bad up/downloader. Works together
 * with the PeerCoordinator to select which Peers get (un)choked.
 */
class PeerCheckerTask extends TimerTask
{
  private static final long KILOPERSECOND = 1024*(PeerCoordinator.CHECK_PERIOD/1000);

  private final PeerCoordinator coordinator;
  private final I2PSnarkUtil _util;
  private int _runCount;

  PeerCheckerTask(I2PSnarkUtil util, PeerCoordinator coordinator)
  {
    _util = util;
    this.coordinator = coordinator;
  }

  private static final Random random = I2PAppContext.getGlobalContext().random();

  public void run()
  {
        _runCount++;
        List<Peer> peerList = coordinator.peerList();
        if (peerList.isEmpty() || coordinator.halted()) {
          coordinator.setRateHistory(0, 0);
          if (coordinator.halted())
            cancel();
          return;
        }

        // Calculate total uploading and worst downloader.
        long worstdownload = Long.MAX_VALUE;
        Peer worstDownloader = null;

        int peers = 0;
        int uploaders = 0;
        int downloaders = 0;
        int removedCount = 0;

        long uploaded = 0;
        long downloaded = 0;

        // Keep track of peers we remove now,
        // we will add them back to the end of the list.
        List<Peer> removed = new ArrayList();
        int uploadLimit = coordinator.allowedUploaders();
        boolean overBWLimit = coordinator.overUpBWLimit();
        for (Peer peer : peerList) {

            // Remove dying peers
            if (!peer.isConnected())
              {
                // This was just a failsafe, right?
                //it.remove();
                //coordinator.removePeerFromPieces(peer);
                //coordinator.peerCount = coordinator.peers.size();
                continue;
              }

            peers++;

            if (!peer.isChoking())
              uploaders++;
            if (!peer.isChoked() && peer.isInteresting())
              downloaders++;

            long upload = peer.getUploaded();
            uploaded += upload;
            long download = peer.getDownloaded();
            downloaded += download;
	    peer.setRateHistory(upload, download);
            peer.resetCounters();

            _util.debug(peer + ":", Snark.DEBUG);
            _util.debug(" ul: " + upload*1024/KILOPERSECOND
                        + " dl: " + download*1024/KILOPERSECOND
                        + " i: " + peer.isInterested()
                        + " I: " + peer.isInteresting()
                        + " c: " + peer.isChoking()
                        + " C: " + peer.isChoked(),
                        Snark.DEBUG);

            // Choke a percentage of them rather than all so it isn't so drastic...
            // unless this torrent is over the limit all by itself.
            // choke 5/8 of the time when seeding and 3/8 when leeching
            boolean overBWLimitChoke = upload > 0 &&
                                       ((overBWLimit && (random.nextInt(8) > (coordinator.completed() ? 2 : 4))) ||
                                        (coordinator.overUpBWLimit(uploaded)));

            // If we are at our max uploaders and we have lots of other
            // interested peers try to make some room.
            // (Note use of coordinator.uploaders)
            if (((coordinator.uploaders == uploadLimit
                && coordinator.interestedAndChoking > 0)
                || coordinator.uploaders > uploadLimit
                || overBWLimitChoke)
                && !peer.isChoking())
              {
                // Check if it still wants pieces from us.
                if (!peer.isInterested())
                  {
                    _util.debug("Choke uninterested peer: " + peer,
                                Snark.INFO);
                    peer.setChoking(true);
                    uploaders--;
                    coordinator.uploaders--;
                    
                    // Put it at the back of the list
                    removed.add(peer);
                  }
                else if (overBWLimitChoke)
                  {
                    _util.debug("BW limit (" + upload + "/" + uploaded + "), choke peer: " + peer,
                                Snark.INFO);
                    peer.setChoking(true);
                    uploaders--;
                    coordinator.uploaders--;
                    removedCount++;

                    // Put it at the back of the list for fairness, even though we won't be unchoking this time
                    removed.add(peer);
                  }
                else if (peer.isInteresting() && peer.isChoked())
                  {
                    // If they are choking us make someone else a downloader
                    _util.debug("Choke choking peer: " + peer, Snark.DEBUG);
                    peer.setChoking(true);
                    uploaders--;
                    coordinator.uploaders--;
                    removedCount++;
                    
                    // Put it at the back of the list
                    removed.add(peer);
                  }
                else if (!peer.isInteresting() && !coordinator.completed())
                  {
                    // If they aren't interesting make someone else a downloader
                    _util.debug("Choke uninteresting peer: " + peer, Snark.DEBUG);
                    peer.setChoking(true);
                    uploaders--;
                    coordinator.uploaders--;
                    removedCount++;
                    
                    // Put it at the back of the list
                    removed.add(peer);
                  }
                else if (peer.isInteresting()
                         && !peer.isChoked()
                         && download == 0)
                  {
                    // We are downloading but didn't receive anything...
                    _util.debug("Choke downloader that doesn't deliver:"
                                + peer, Snark.DEBUG);
                    peer.setChoking(true);
                    uploaders--;
                    coordinator.uploaders--;
                    removedCount++;
                    
                    // Put it at the back of the list
                    removed.add(peer);
                  }
                else if (peer.isInteresting() && !peer.isChoked() &&
                         download < worstdownload)
                  {
                    // Make sure download is good if we are uploading
                    worstdownload = download;
                    worstDownloader = peer;
                  }
                else if (upload < worstdownload && coordinator.completed())
                  {
                    // Make sure upload is good if we are seeding
                    worstdownload = upload;
                    worstDownloader = peer;
                  }
              }
            peer.retransmitRequests();
            peer.keepAlive();
            // announce them to local tracker (TrackerClient does this too)
            if (_util.getDHT() != null && (_runCount % 5) == 0) {
                _util.getDHT().announce(coordinator.getInfoHash(), peer.getPeerID().getDestHash());
            }
          }

        // Resync actual uploaders value
        // (can shift a bit by disconnecting peers)
        coordinator.uploaders = uploaders;

        // Remove the worst downloader if needed. (uploader if seeding)
        if (((uploaders == uploadLimit
            && coordinator.interestedAndChoking > 0)
            || uploaders > uploadLimit)
            && worstDownloader != null)
          {
            _util.debug("Choke worst downloader: " + worstDownloader,
                        Snark.DEBUG);

            worstDownloader.setChoking(true);
            coordinator.uploaders--;
            removedCount++;

            // Put it at the back of the list
            removed.add(worstDownloader);
          }
        
        // Optimistically unchoke a peer
        if ((!overBWLimit) && !coordinator.overUpBWLimit(uploaded))
            coordinator.unchokePeer();

        // Put peers back at the end of the list that we removed earlier.
        synchronized (coordinator.peers) {
            for(Peer peer : removed) { 
                if (coordinator.peers.remove(peer))
                    coordinator.peers.add(peer);
            }
        }
        coordinator.interestedAndChoking += removedCount;

	// store the rates
	coordinator.setRateHistory(uploaded, downloaded);

        // close out unused files, but we don't need to do it every time
        Storage storage = coordinator.getStorage();
        if (storage != null && (_runCount % 4) == 0) {
                storage.cleanRAFs();
        }

        // announce ourselves to local tracker (TrackerClient does this too)
        if (_util.getDHT() != null && (_runCount % 16) == 0) {
            _util.getDHT().announce(coordinator.getInfoHash());
        }
  }
}
