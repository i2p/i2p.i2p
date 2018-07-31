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
import java.util.List;
import java.util.Random;

import net.i2p.data.DataHelper;
import net.i2p.util.Log;

import org.klomp.snark.dht.DHT;

/**
 * TimerTask that checks for good/bad up/downloader. Works together
 * with the PeerCoordinator to select which Peers get (un)choked.
 */
class PeerCheckerTask implements Runnable
{
  private static final long KILOPERSECOND = 1024*(PeerCoordinator.CHECK_PERIOD/1000);

  private final PeerCoordinator coordinator;
  private final I2PSnarkUtil _util;
  private final Log _log;
  private final Random random;
  private int _runCount;

  PeerCheckerTask(I2PSnarkUtil util, PeerCoordinator coordinator)
  {
    _util = util;
    _log = util.getContext().logManager().getLog(PeerCheckerTask.class);
    random = util.getContext().random();
    this.coordinator = coordinator;
  }

  public void run()
  {
        _runCount++;
        List<Peer> peerList = coordinator.peerList();
        if (peerList.isEmpty() || coordinator.halted()) {
          coordinator.setRateHistory(0, 0);
          return;
        }

        // Calculate total uploading and worst downloader.
        long worstdownload = Long.MAX_VALUE;
        Peer worstDownloader = null;

        int uploaders = 0;
        int interestedUploaders = 0;
        int removedCount = 0;

        long uploaded = 0;
        long downloaded = 0;

        // Keep track of peers we remove now,
        // we will add them back to the end of the list.
        List<Peer> removed = new ArrayList<Peer>();
        int uploadLimit = coordinator.allowedUploaders();
        boolean overBWLimit = coordinator.overUpBWLimit();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("START peers: " + peerList.size() + " uploaders: " + coordinator.getUploaders() +
                       " interested: " + coordinator.getInterestedUploaders() +
                       " limit: " + uploadLimit + " overBW? " + overBWLimit);
        DHT dht = _util.getDHT();
        boolean fetchComments = _util.utCommentsEnabled();
        int i = 0;
        for (Peer peer : peerList) {
            i++;

            // Remove dying peers
            if (!peer.isConnected())
              {
                // This was just a failsafe, right?
                //it.remove();
                //coordinator.removePeerFromPieces(peer);
                //coordinator.peerCount = coordinator.peers.size();
                continue;
              }

            if (peer.getInactiveTime() > peer.getMaxInactiveTime()) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Disconnecting peer idle " +
                              DataHelper.formatDuration(peer.getInactiveTime()) + ": " + peer);
                peer.disconnect();
                continue;
            }

            // we only count choking AND interested, so as not to steal a slot
            // from some other torrent
            if (peer.isInterested() && !peer.isChoking())
              uploaders++;

            long upload = peer.getUploaded();
            uploaded += upload;
            long download = peer.getDownloaded();
            downloaded += download;
	    peer.setRateHistory(upload, download);
            peer.resetCounters();

            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug(peer + ":"
                        + " ul: " + upload*1024/KILOPERSECOND
                        + " dl: " + download*1024/KILOPERSECOND
                        + " i: " + peer.isInterested()
                        + " I: " + peer.isInteresting()
                        + " c: " + peer.isChoking()
                        + " C: " + peer.isChoked());
            }

            // Choke a percentage of them rather than all so it isn't so drastic...
            // unless this torrent is over the limit all by itself.
            // choke 5/8 of the time when seeding and 3/8 when leeching
            boolean overBWLimitChoke = upload > 0 &&
                                       ((overBWLimit && (random.nextInt(8) > (coordinator.completed() ? 2 : 4))) ||
                                        (coordinator.overUpBWLimit(uploaded)));

            // If we are at our max uploaders and we have lots of other
            // interested peers try to make some room.
            // (Note use of coordinator.uploaders)
            int cup = coordinator.getUploaders();
            if (((cup == uploadLimit
                  && coordinator.getInterestedAndChoking() > 0)
                 || cup > uploadLimit
                 || overBWLimitChoke)
                && !peer.isChoking())
              {
                // Check if it still wants pieces from us.
                if (!peer.isInterested())
                  {
                    // Note that we only choke if we are over our limits,
                    // so a peer may remain unchoked even if uninterested.
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Choke uninterested peer: " + peer);
                    peer.setChoking(true);
                    uploaders--;
                    coordinator.decrementUploaders(false);
                    
                    // Put it at the back of the list
                    removed.add(peer);
                  }
                else if (overBWLimitChoke)
                  {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("BW limit (" + upload + "/" + uploaded + "), choke peer: " + peer);
                    peer.setChoking(true);
                    uploaders--;
                    interestedUploaders--;
                    coordinator.decrementUploaders(true);
                    removedCount++;

                    // Put it at the back of the list for fairness, even though we won't be unchoking this time
                    removed.add(peer);
                  }
                else if (peer.isInteresting() && peer.isChoked())
                  {
                    // If they are choking us make someone else a downloader
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Choke choking peer: " + peer);
                    peer.setChoking(true);
                    uploaders--;
                    interestedUploaders--;
                    coordinator.decrementUploaders(true);
                    removedCount++;
                    
                    // Put it at the back of the list
                    removed.add(peer);
                  }
                else if (!peer.isInteresting() && !coordinator.completed())
                  {
                    // If they aren't interesting make someone else a downloader
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Choke uninteresting peer: " + peer);
                    peer.setChoking(true);
                    uploaders--;
                    interestedUploaders--;
                    coordinator.decrementUploaders(true);
                    removedCount++;
                    
                    // Put it at the back of the list
                    removed.add(peer);
                  }
                else if (peer.isInteresting()
                         && !peer.isChoked()
                         && download == 0)
                  {
                    // We are downloading but didn't receive anything...
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Choke downloader that doesn't deliver: " + peer);
                    peer.setChoking(true);
                    uploaders--;
                    interestedUploaders--;
                    coordinator.decrementUploaders(true);
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
            // send PEX, about every 12 minutes
            if (((_runCount + i) % 17) == 0 && !peer.isCompleted())
                coordinator.sendPeers(peer);
            // send Comment Request, about every 30 minutes
            if (fetchComments && ((_runCount + i) % 47) == 0)
                coordinator.sendCommentReq(peer);
            // cheap failsafe for seeds connected to seeds, stop pinging and hopefully
            // the inactive checker (above) will eventually disconnect it
            if (coordinator.getNeededLength() > 0 || !peer.isCompleted())
                peer.keepAlive();
            // announce them to local tracker (TrackerClient does this too)
            if (dht != null && (_runCount % 5) == 0) {
                dht.announce(coordinator.getInfoHash(), peer.getPeerID().getDestHash(),
                             peer.isCompleted());
            }
        } // for peer

        // Resync actual uploaders value
        // (can shift a bit by disconnecting peers)
        coordinator.setUploaders(uploaders, interestedUploaders);

        // Remove the worst downloader if needed. (uploader if seeding)
        if (((uploaders == uploadLimit
            && coordinator.getInterestedAndChoking() > 0)
            || uploaders > uploadLimit)
            && worstDownloader != null)
          {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Choke worst downloader: " + worstDownloader);

            worstDownloader.setChoking(true);
            coordinator.decrementUploaders(worstDownloader.isInterested());
            removedCount++;

            // Put it at the back of the list
            removed.add(worstDownloader);
          }
        
        boolean coordOver = coordinator.overUpBWLimit(uploaded);
        synchronized (coordinator.peers) {
            if ((!overBWLimit) && !coordOver) {
                // Optimistically unchoke a peer
                // must be called inside synch
                coordinator.unchokePeer();
            }
            // Put peers back at the end of the list that we removed earlier.
            for(Peer peer : removed) { 
                if (coordinator.peers.remove(peer))
                    coordinator.peers.add(peer);
            }
        }

        coordinator.addInterestedAndChoking(removedCount);

	// store the rates
	coordinator.setRateHistory(uploaded, downloaded);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("END peers: " + peerList.size() + " uploaders: " + uploaders +
                       " interested: " + interestedUploaders);

        // close out unused files, but we don't need to do it every time
        Storage storage = coordinator.getStorage();
        if (storage != null) {
            // The more files a torrent has, the more often we call the cleaner,
            // to keep from running out of FDs
            int files = storage.getFileCount();
            int skip;
            if (files == 1)
                skip = 6;
            else if (files <= 4)
                skip = 4;
            else if (files <= 20)
                skip = 3;
            else if (files <= 50)
                skip = 2;
            else
                skip = 1;

            if ((_runCount % skip) == 0)
                storage.cleanRAFs();
        }

        // announce ourselves to local tracker (TrackerClient does this too)
        if (dht != null && (_runCount % 16) == 0) {
            dht.announce(coordinator.getInfoHash(), coordinator.completed());
        }
  }
}
