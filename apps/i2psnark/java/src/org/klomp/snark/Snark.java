/* Snark - Main snark program startup class.
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
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;

import org.klomp.snark.comments.Comment;
import org.klomp.snark.comments.CommentSet;


/**
 * Main Snark program startup class.
 *
 * @author Mark Wielaard (mark@klomp.org)
 */
public class Snark
  implements StorageListener, CoordinatorListener, ShutdownListener
{
  private final static int MIN_PORT = 6881;
  private final static int MAX_PORT = 6889;

  private static final String newline = System.getProperty("line.separator");

  /** max connections */
  public static final String PROP_MAX_CONNECTIONS = "i2psnark.maxConnections";

  /** most of these used to be public, use accessors below instead */
  private String torrent;
  private MetaInfo meta;
  private Storage storage;
  private PeerCoordinator coordinator;
  private ConnectionAcceptor acceptor;
  private TrackerClient trackerclient;
  private final File rootDataDir;
  private final CompleteListener completeListener;
  private volatile boolean stopped;
  private volatile boolean starting;
  private final byte[] id;
  private final byte[] infoHash;
  private List<String> additionalTrackerURLs;
  protected final I2PSnarkUtil _util;
  private final Log _log;
  private final PeerCoordinatorSet _peerCoordinatorSet;
  private volatile String trackerProblems;
  private volatile int trackerSeenPeers;
  private volatile boolean _autoStoppable;
  // String indicating main activity
  private volatile String activity = "Not started";
  private long savedUploaded;
  private long _startedTime;
  private CommentSet _comments;
  private final Object _commentLock = new Object();
  private static final AtomicInteger __RPCID = new AtomicInteger();
  private final int _rpcID = __RPCID.incrementAndGet();

  /**
   * multitorrent
   *
   * Will not start itself. Caller must call startTorrent() if desired.
   *
   * @throws RuntimeException via fatal()
   * @throws RouterException via fatalRouter()
   */
  public Snark(I2PSnarkUtil util, String torrent, String ip, int user_port,
        StorageListener slistener, CoordinatorListener clistener,
        CompleteListener complistener, PeerCoordinatorSet peerCoordinatorSet,
        ConnectionAcceptor connectionAcceptor, String rootDir)
  {
      this(util, torrent, ip, user_port, slistener, clistener, complistener,
           peerCoordinatorSet, connectionAcceptor, rootDir, null);
  }

  /**
   * multitorrent
   *
   * Will not start itself. Caller must call startTorrent() if desired.
   *
   * @param baseFile if null, use rootDir/torrentName; if non-null, use it instead
   * @param peerCoordinatorSet non-null
   * @param connectionAcceptor non-null
   * @throws RuntimeException via fatal()
   * @throws RouterException via fatalRouter()
   * @since 0.9.11
   */
  public Snark(I2PSnarkUtil util, String torrent, String ip, int user_port,
        StorageListener slistener, CoordinatorListener clistener,
        CompleteListener complistener, PeerCoordinatorSet peerCoordinatorSet,
        ConnectionAcceptor connectionAcceptor, String rootDir, File baseFile)
  {
    if (slistener == null)
      slistener = this;

    completeListener = complistener;
    _util = util;
    _log = util.getContext().logManager().getLog(Snark.class);
    _peerCoordinatorSet = peerCoordinatorSet;
    acceptor = connectionAcceptor;

    this.torrent = torrent;
    this.rootDataDir = new File(rootDir);

    stopped = true;
    activity = "Network setup";

    id = generateID();
    if (_log.shouldLog(Log.INFO))
        _log.info("My peer id: " + PeerID.idencode(id));

/*
 * Don't start a tunnel if the torrent isn't going to be started.
 * If we are starting,
 * startTorrent() will force a connect.
 *
    boolean ok = util.connect();
    if (!ok) fatal("Unable to connect to I2P");
    I2PServerSocket serversocket = util.getServerSocket();
    if (serversocket == null)
        fatal("Unable to listen for I2P connections");
    else {
        Destination d = serversocket.getManager().getSession().getMyDestination();
        debug("Listening on I2P destination " + d.toBase64() + " / " + d.calculateHash().toBase64(), NOTICE);
    }
*/

    // Figure out what the torrent argument represents.
    File f = null;
    InputStream in = null;
    byte[] x_infoHash = null;
    try
      {
        f = new File(torrent);
        if (f.exists())
          in = new FileInputStream(f);
        else
          {
         /**** No, we don't ever fetch a torrent file this way
               and we don't want to block in the constructor
            activity = "Getting torrent";
            File torrentFile = _util.get(torrent, 3);
            if (torrentFile == null) {
                fatal("Unable to fetch " + torrent);
                if (false) return; // never reached - fatal(..) throws
            } else {
                torrentFile.deleteOnExit();
                in = new FileInputStream(torrentFile);
            }
         *****/
             throw new IOException("not found");
          }
        meta = new MetaInfo(in);
        x_infoHash = meta.getInfoHash();
      }
    catch(IOException ioe)
      {
        // OK, so it wasn't a torrent metainfo file.
        if (f != null && f.exists())
          if (ip == null)
            fatal("'" + torrent + "' exists,"
                  + " but is not a valid torrent metainfo file."
                  + System.getProperty("line.separator"), ioe);
          else
            fatal("I2PSnark does not support creating and tracking a torrent at the moment");
        /*
            {
              // Try to create a new metainfo file
             debug
               ("Trying to create metainfo torrent for '" + torrent + "'",
                NOTICE);
             try
               {
                 activity = "Creating torrent";
                 storage = new Storage
                   (f, "http://" + ip + ":" + port + "/announce", slistener);
                 storage.create();
                 meta = storage.getMetaInfo();
               }
             catch (IOException ioe2)
               {
                 fatal("Could not create torrent for '" + torrent + "'", ioe2);
               }
            }
         */
        else
          fatal("Cannot open '" + torrent + "'", ioe);
      } catch (OutOfMemoryError oom) {
          fatalRouter("ERROR - Out of memory, cannot create torrent " + torrent + ": " + oom.getMessage(), oom);
      } catch (Throwable t) {
          fatalRouter("ERROR - cannot create torrent " + torrent + ": " + t.getMessage(), t);
      } finally {
          if (in != null)
              try { in.close(); } catch (IOException ioe) {}
      }    

    infoHash = x_infoHash;  // final
    if (_log.shouldLog(Log.INFO))
        _log.info(meta.toString());
    
    // When the metainfo torrent was created from an existing file/dir
    // it already exists.
    if (storage == null)
      {
        try
          {
            activity = "Checking storage";
            boolean shouldPreserve = completeListener != null && completeListener.getSavedPreserveNamesSetting(this);
            if (baseFile == null) {
                String base = meta.getName();
                if (shouldPreserve) {
                    if (base.equals(".") || base.equals("..") || base.equals(" ") || base.length() == 0) {
                        fatal("Bad torrent name \"" + base + '"');
                    }
                } else {
                    String filtered = Storage.filterName(base);
                    if (!filtered.equals(base)) {
                        File f1 = new File(rootDataDir, base);
                        File f2 = new File(rootDataDir, filtered);
                        if (f1.exists() && !f2.exists())
                            shouldPreserve = true;
                        else
                            base = filtered;
                    }
                }
                if (_util.getFilesPublic())
                    baseFile = new File(rootDataDir, base);
                else
                    baseFile = new SecureFile(rootDataDir, base);
            }
            storage = new Storage(_util, baseFile, meta, slistener, shouldPreserve);
            if (completeListener != null) {
                storage.check(completeListener.getSavedTorrentTime(this),
                              completeListener.getSavedTorrentBitField(this));
            } else {
                storage.check();
            }
            // have to figure out when to reopen
            // if (!start)
            //    storage.close();
          }
        catch (IOException ioe)
          {
            try { storage.close(); } catch (IOException ioee) {
                ioee.printStackTrace();
            }
            fatal("Could not check or create files for " + getBaseInfo(), ioe);
          }
      }

    savedUploaded = (completeListener != null) ? completeListener.getSavedUploaded(this) : 0;
    if (completeListener != null)
        _comments = completeListener.getSavedComments(this);
  }

  /**
   *  multitorrent, magnet, Was used by snark-rpc plugin? not now?
   *
   *  Will not start itself. Caller must call startTorrent() if desired.
   *
   *  @param peerCoordinatorSet non-null
   *  @param connectionAcceptor non-null
   *  @param ignored used to be autostart
   *  @throws RuntimeException via fatal()
   *  @throws RouterException via fatalRouter()
   *  @since 0.8.4, removed in 0.9.36, restored in 0.9.45 with boolean param now ignored
   */
  protected Snark(I2PSnarkUtil util, String torrent, byte[] ih, String trackerURL,
        CompleteListener complistener, PeerCoordinatorSet peerCoordinatorSet,
        ConnectionAcceptor connectionAcceptor, boolean ignored, String rootDir) {
      this(util, torrent, ih, Collections.singletonList(trackerURL), complistener, peerCoordinatorSet, connectionAcceptor, rootDir);
  }

  /**
   *  multitorrent, magnet
   *
   *  Will not start itself. Caller must call startTorrent() if desired.
   *
   *  @param torrent a fake name for now (not a file name)
   *  @param ih 20-byte info hash
   *  @param trackerURLs may be null
   *  @param peerCoordinatorSet non-null
   *  @param connectionAcceptor non-null
   *  @throws RuntimeException via fatal()
   *  @throws RouterException via fatalRouter()
   *  @since 0.8.4
   */
  public Snark(I2PSnarkUtil util, String torrent, byte[] ih, List<String> trackerURLs,
        CompleteListener complistener, PeerCoordinatorSet peerCoordinatorSet,
        ConnectionAcceptor connectionAcceptor, String rootDir)
  {
    completeListener = complistener;
    _util = util;
    _log = util.getContext().logManager().getLog(Snark.class);
    _peerCoordinatorSet = peerCoordinatorSet;
    acceptor = connectionAcceptor;
    this.torrent = torrent;
    this.infoHash = ih;
    this.additionalTrackerURLs = trackerURLs;
    this.rootDataDir = rootDir != null ? new File(rootDir) : null;   // null only for FetchAndAdd extension
    savedUploaded = 0;
    stopped = true;
    id = generateID();

    // All we have is an infoHash
    // meta remains null
    // storage remains null
  }

  private static byte[] generateID() {
    // "Taking Three as the subject to reason about--
    // A convenient number to state--
    // We add Seven, and Ten, and then multiply out
    // By One Thousand diminished by Eight.
    //
    // "The result we proceed to divide, as you see,
    // By Nine Hundred and Ninety Two:
    // Then subtract Seventeen, and the answer must be
    // Exactly and perfectly true.

    // Create a new ID and fill it with something random.  First nine
    // zeros bytes, then three bytes filled with snark and then
    // eight random bytes.
    byte snark = (((3 + 7 + 10) * (1000 - 8)) / 992) - 17;
    byte[] rv = new byte[20];
    rv[9] = snark;
    rv[10] = snark;
    rv[11] = snark;
    try {
        I2PAppContext.getGlobalContext().random().nextBytes(rv, 12, 8);
    } catch (IllegalStateException ise) {
        // random is shut down
        throw new RouterException("Router shutdown", ise);
    }
    return rv;
  }

  /**
   * Start up contacting peers and querying the tracker.
   * Blocks if tunnel is not yet open.
   *
   * @throws RuntimeException via fatal()
   * @throws RouterException via fatalRouter()
   */
  public synchronized void startTorrent() {
      if (!stopped)
          return;
      starting = true;
      try {
          x_startTorrent();
          _startedTime = _util.getContext().clock().now();
      } finally {
          starting = false;
      }
  }

  private void x_startTorrent() {
    boolean ok = _util.connect();
    if (!ok) {
        if (_util.getContext().isRouterContext())
            fatalRouter(_util.getString("Unable to connect to I2P"), null);
        else
            fatalRouter(_util.getString("Error connecting to I2P - check your I2CP settings!") + ' ' + _util.getI2CPHost() + ':' + _util.getI2CPPort(), null);
    }
    if (coordinator == null) {
        I2PServerSocket serversocket = _util.getServerSocket();
        if (serversocket == null)
            fatalRouter("Unable to listen for I2P connections", null);
        else {
            Destination d = serversocket.getManager().getSession().getMyDestination();
            if (_log.shouldLog(Log.INFO))
                _log.info("Listening on I2P destination " + d.toBase64() + " / " + d.calculateHash().toBase64());
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Starting PeerCoordinator, ConnectionAcceptor, and TrackerClient");
        activity = "Collecting pieces";
        coordinator = new PeerCoordinator(_util, id, infoHash, meta, storage, this, this, completeListener.getBandwidthListener());
        coordinator.setUploaded(savedUploaded);
        _peerCoordinatorSet.add(coordinator);
        // TODO pass saved closest DHT nodes to the tracker? or direct to the coordinator?
        trackerclient = new TrackerClient(_util, meta, additionalTrackerURLs, coordinator, this);
    }
    // ensure acceptor is running when in multitorrent
    acceptor.startAccepting();

    stopped = false;
    if (coordinator.halted()) {
        coordinator.restart();
        _peerCoordinatorSet.add(coordinator);
    }
    if (!trackerclient.started()) {
        trackerclient.start();
    } else if (trackerclient.halted()) {
        if (storage != null) {
            try {
                 storage.reopen();
             }   catch (IOException ioe) {
                 try { storage.close(); } catch (IOException ioee) {
                     ioee.printStackTrace();
                 }
                 fatal("Could not open file for " + getBaseInfo(), ioe);
             }
        }
        trackerclient.start();
    } else {
        if (_log.shouldLog(Log.INFO))
            _log.info("NOT starting TrackerClient???");
    }
  }

  /**
   * Stop contacting the tracker and talking with peers
   */
  public void stopTorrent() {
      stopTorrent(false);
  }

  /**
   * Stop contacting the tracker and talking with peers
   * @param fast if true, limit the life of the unannounce threads
   * @since 0.9.1
   */
  public synchronized void stopTorrent(boolean fast) {
    TrackerClient tc = trackerclient;
    if (tc != null)
        tc.halt(fast);
    PeerCoordinator pc = coordinator;
    if (pc != null)
        pc.halt();
    Storage st = storage;
    if (!fast)
        // HACK: Needed a way to distinguish between user-stop and 
        // shutdown-stop. stopTorrent(true) is in stopAllTorrents().
        // (#766)
        stopped = true;
    if (st != null) {
        // TODO: Cache the config-in-mem to compare vs config-on-disk
        // (needed for auto-save to not double-save in some cases)
        long nowUploaded = getUploaded();
        // If autoStart is enabled, always save the config, so we know
        // whether to start it up next time
        boolean changed = storage.isChanged() || nowUploaded != savedUploaded ||
                          (completeListener != null && completeListener.shouldAutoStart());
        try { 
            storage.close(); 
        } catch (IOException ioe) {
            if (_log.shouldWarn())
                _log.warn("Error closing " + torrent);
            ioe.printStackTrace();
        }
        savedUploaded = nowUploaded;
        // SnarkManager.stopAllTorrents() will save comments at shutdown even if never started...
        if (completeListener != null) {
            if (changed)
                completeListener.updateStatus(this);
            synchronized(_commentLock) {
                if (_comments != null) {
                    synchronized(_comments) {
                        if (_comments.isModified())
                            completeListener.locked_saveComments(this, _comments);
                    }
                }
            }
        }
    }
    if (fast)
        // HACK: See above if(!fast)
        stopped = true;
    if (pc != null)
        _peerCoordinatorSet.remove(pc);
  }

/****
  private static Snark parseArguments(String[] args)
  {
    return parseArguments(args, null, null);
  }
****/

    // Accessors

    /**
     *  @return file name of .torrent file (should be full absolute path), or a fake name if in magnet mode.
     *  @since 0.8.4
     */
    public String getName() {
        return torrent;
    }

    /**
     *  @return base name of torrent [filtered version of getMetaInfo.getName()], or a fake name if in magnet mode
     *  @since 0.8.4
     */
    public String getBaseName() {
        if (storage != null)
            return storage.getBaseName();
        return torrent;
    }

    /**
     *  @return base name for torrent [filtered version of getMetaInfo.getName()],
     *          or a fake name if in magnet mode, followed by path info and error message,
     *          for error logging only
     *  @since 0.9.44
     */
    private String getBaseInfo() {
        if (storage != null)
            return storage.getBaseName() + " at " +
                   storage.getBase() + " - check that device is present and writable";
        return torrent;
    }

    /**
     *  @return always will be valid even in magnet mode
     *  @since 0.8.4
     */
    public byte[] getID() {
        return id;
    }

    /**
     *  @return always will be valid even in magnet mode
     *  @since 0.8.4
     */
    public byte[] getInfoHash() {
        // should always be the same
        if (meta != null)
            return meta.getInfoHash();
        return infoHash;
    }

    /**
     *  @return may be null if in magnet mode
     *  @since 0.8.4
     */
    public MetaInfo getMetaInfo() {
        return meta;
    }

    /**
     *  @return may be null if in magnet mode
     *  @since 0.8.4
     */
    public Storage getStorage() {
        return storage;
    }

    /**
     *  @since 0.8.4
     */
    public boolean isStopped() {
        return stopped;
    }

    /**
     *  Startup in progress.
     *  @since 0.9.1
     */
    public boolean isStarting() {
        return starting && stopped;
    }

    /**
     *  Set startup in progress.
     *  @since 0.9.1
     */
    public void setStarting() {
        starting = true;
    }

    /**
     *  File checking in progress.
     *  @since 0.9.3
     */
    public boolean isChecking() {
        return storage != null && storage.isChecking();
    }

    /**
     *  If checking is in progress, return completion 0.0 ... 1.0,
     *  else return 1.0.
     *  @since 0.9.23
     */
    public double getCheckingProgress() {
        if (storage != null && storage.isChecking())
            return storage.getCheckingProgress();
        else
            return 1.0d;
    }

    /**
     *  Disk allocation (ballooning) in progress.
     *  @since 0.9.3
     */
    public boolean isAllocating() {
        return storage != null && storage.isAllocating();
    }

    /**
     *  @since 0.8.4
     */
    public long getDownloadRate() {
        PeerCoordinator coord = coordinator;
        if (coord != null)
            return coord.getDownloadRate();
        return 0;
    }

    /**
     *  @since 0.8.4
     */
    public long getUploadRate() {
        PeerCoordinator coord = coordinator;
        if (coord != null)
            return coord.getUploadRate();
        return 0;
    }

    /**
     *  @since 0.8.4
     */
    public long getDownloaded() {
        PeerCoordinator coord = coordinator;
        if (coord != null)
            return coord.getDownloaded();
        return 0;
    }

    /**
     *  @since 0.8.4
     */
    public long getUploaded() {
        PeerCoordinator coord = coordinator;
        if (coord != null)
            return coord.getUploaded();
        return savedUploaded;
    }

    /**
     *  @since 0.8.4
     */
    public int getPeerCount() {
        PeerCoordinator coord = coordinator;
        if (coord != null)
            return coord.getPeerCount();
        return 0;
    }

    /**
     *  @since 0.8.4
     */
    public List<Peer> getPeerList() {
        PeerCoordinator coord = coordinator;
        if (coord != null)
            return coord.peerList();
        return Collections.emptyList();
    }

    /**
     *  Not HTML escaped.
     *  @return String returned from tracker, or null if no error
     *  @since 0.8.4
     */
    public String getTrackerProblems() {
        return trackerProblems;
    }

    /**
     *  @param p tracker error string or null
     *  @since 0.8.4
     */
    public void setTrackerProblems(String p) {
        trackerProblems = p;
    }

    /**
     *  @return count returned from tracker
     *  @since 0.8.4
     */
    public int getTrackerSeenPeers() {
        return trackerSeenPeers;
    }

    /**
     *  @since 0.8.4
     */
    public void setTrackerSeenPeers(int p) {
        trackerSeenPeers = p;
    }

    /**
     *  @since 0.8.4
     */
    public void updatePiecePriorities() {
        PeerCoordinator coord = coordinator;
        if (coord != null)
            coord.updatePiecePriorities();
    }

    /**
     *  @return total of all torrent files, or total of metainfo file if fetching magnet, or -1
     *  @since 0.8.4
     */
    public long getTotalLength() {
        if (meta != null)
            return meta.getTotalLength();
        // FIXME else return metainfo length if available
        return -1;
    }

    /**
     *  Bytes not yet in storage. Does NOT account for skipped files.
     *  @return exact value. or -1 if no storage yet.
     *          getNeeded() * pieceLength(0) isn't accurate if last piece
     *          is still needed.
     *  @since 0.8.9
     */
    public long getRemainingLength() {
        if (meta != null && storage != null) {
            long needed = storage.needed();
            long length0 = meta.getPieceLength(0);
            long remaining = needed * length0;
            // fixup if last piece is needed
            int last = meta.getPieces() - 1;
            if (last != 0 && !storage.getBitField().get(last))
                remaining -= length0 - meta.getPieceLength(last);
            return remaining;
        }
        return -1;
    }

    /**
     *  Bytes still wanted. DOES account for (i.e. does not include) skipped files.
     *  FIXME -1 when not running.
     *  @return exact value. or -1 if no storage yet or when not running.
     *  @since 0.9.1
     */
    public long getNeededLength() {
        PeerCoordinator coord = coordinator;
        if (coord != null)
            return coord.getNeededLength();
        return -1;
    }

    /**
     *  Bytes not received and set to skipped.
     *  This is not the same as the total of all skipped files,
     *  since pieces may span multiple files.
     *
     *  @return exact value. or 0 if no storage yet.
     *  @since 0.9.24
     */
    public long getSkippedLength() {
        PeerCoordinator coord = coordinator;
        if (coord != null) {
            // fast way
            long r = getRemainingLength();
            if (r <= 0)
                return 0;
            long n = coord.getNeededLength();
            return r - n;
        } else if (storage != null) {
            // slow way
            return storage.getSkippedLength();
        }
        return 0;
    }

    /**
     *  Does not account (i.e. includes) for skipped files.
     *  @return number of pieces still needed (magnet mode or not), or -1 if unknown
     *  @since 0.8.4
     */
    public long getNeeded() {
        if (storage != null)
            return storage.needed();
        if (meta != null)
            // FIXME subtract chunks we have
            return meta.getTotalLength();
        // FIXME fake
        return -1;
    }

    /**
     *  @param p the piece number
     *  @return metainfo piece length or 16K if fetching magnet
     *  @since 0.8.4
     */
    public int getPieceLength(int p) {
        if (meta != null)
            return meta.getPieceLength(p);
        return 16*1024;
    }

    /**
     *  @return number of pieces
     *  @since 0.8.4
     */
    public int getPieces() {
        if (meta != null)
            return meta.getPieces();
        // FIXME else return metainfo pieces if available
        return -1;
    }

    /**
     *  @return true if restarted
     *  @since 0.8.4
     */
    public boolean restartAcceptor() {
        acceptor.restart();
        return true;
    }

    /**
     *  @return trackerURL string from magnet-mode constructor, may be null
     *  @since 0.8.4
     */
    public String getTrackerURL() {
        return (additionalTrackerURLs != null && !additionalTrackerURLs.isEmpty()) ? additionalTrackerURLs.get(0) : null;
    }

    /**
     *  @return trackerURLs all strings from magnet-mode constructor, may be null
     *  @since 0.9.71
     */
    public List<String> getTrackerURLs() {
        return additionalTrackerURLs;
    }

    /**
     *  @since 0.9.9
     */
    public boolean isAutoStoppable() { return _autoStoppable; }

    /**
     *  @since 0.9.9
     */
    public void setAutoStoppable(boolean yes) { _autoStoppable = yes; }

  /**
   * Aborts program abnormally.
   * @throws RuntimeException always
   */
  private void fatal(String s) throws RuntimeException {
    fatal(s, null);
  }

  /**
   * Aborts program abnormally.
   * @throws RuntimeException always
   */
  private void fatal(String s, Throwable t) throws RuntimeException {
    _log.error(s, t);
    stopTorrent();
    if (t != null)
        s += ": " + t;
    if (completeListener != null)
        completeListener.fatal(this, s);
    throw new RuntimeException(s, t);
  }

  /**
   * Throws a unique exception class to blame the router that can be caught by SnarkManager
   * @throws RouterException always
   * @since 0.9.46
   */
  private void fatalRouter(String s, Throwable t) throws RouterException {
    _log.error(s, t);
    if (!_util.getContext().isRouterContext())
        System.out.println(s);
    stopTorrent(true);
    if (completeListener != null)
        completeListener.fatal(this, s);
    throw new RouterException(s, t);
  }

  /**
   * A unique exception class to blame the router that can be caught by SnarkManager
   * @since 0.9.46
   */
  static class RouterException extends RuntimeException {
      public RouterException(String s) { super(s); }
      public RouterException(String s, Throwable t) { super(s, t); }
  }


  /** CoordinatorListener - this does nothing */
  public void peerChange(PeerCoordinator coordinator, Peer peer)
  {
    // System.out.println(peer.toString());
  }
  
  /**
   * Called when the PeerCoordinator got the MetaInfo via magnet.
   * CoordinatorListener.
   * Create the storage, tell SnarkManager, and give the storage
   * back to the coordinator.
   *
   * @throws RuntimeException via fatal()
   * @since 0.8.4
   */
  public void gotMetaInfo(PeerCoordinator coordinator, MetaInfo metainfo) {
      try {
          String base = Storage.filterName(metainfo.getName());
          File baseFile;
          if (_util.getFilesPublic())
              baseFile = new File(rootDataDir, base);
          else
              baseFile = new SecureFile(rootDataDir, base);
          if (baseFile.exists())
              throw new IOException("Data location already exists: " + baseFile);
          // The following two may throw IOE...
          storage = new Storage(_util, baseFile, metainfo, this, false);
          storage.check();
          // ... so don't set meta until here
          meta = metainfo;
          if (completeListener != null) {
              String newName = completeListener.gotMetaInfo(this);
              if (newName != null)
                  torrent = newName;
              // else some horrible problem
          }
          coordinator.setStorage(storage);
      } catch (IOException ioe) {
          if (storage != null) {
              try { storage.close(); } catch (IOException ioee) {}
              // clear storage, we have a mess if we have non-null storage and null metainfo,
              // as on restart, Storage.reopen() will throw an ioe
              storage = null;
          }
          // TODO we're still in an inconsistent state, won't work if restarted
          // (PeerState "disconnecting seed that connects to seeds"
          fatal("Could not create file for " + getBaseInfo(), ioe);
      }
  }

  /**
   * Call after editing torrent.
   * Caller must ensure infohash, files, etc. did not change.
   *
   * @since 0.9.53
   */
  public void replaceMetaInfo(MetaInfo metainfo) {
      meta = metainfo;
      TrackerClient tc = trackerclient;
      if (tc != null)
          tc.reinitialize();
  }

  ///////////// Begin StorageListener methods

  //private boolean allocating = false;

  /** does nothing */
  public void storageCreateFile(Storage storage, String name, long length)
  {
    //if (allocating)
    //  System.out.println(); // Done with last file.

    //System.out.print("Creating file '" + name
    //                 + "' of length " + length + ": ");
    //allocating = true;
  }

  // How much storage space has been allocated
  private long allocated = 0;

  /** does nothing */
  public void storageAllocated(Storage storage, long length)
  {
    //allocating = true;
    //System.out.print(".");
    //allocated += length;
    //if (allocated == meta.getTotalLength())
    //  System.out.println(); // We have all the disk space we need.
  }

  private boolean allChecked;
  private boolean checking;
  //private boolean prechecking = true;

  public void storageChecked(Storage storage, int num, boolean checked)
  {
    //allocating = false;
    if (!allChecked && !checking)
      {
        // Use the MetaInfo from the storage since our own might not
        // yet be setup correctly.
        //MetaInfo meta = storage.getMetaInfo();
        //if (meta != null)
        //  System.out.print("Checking existing "
        //                   + meta.getPieces()
        //                   + " pieces: ");
        checking = true;
      }
    if (!checking) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Got " + (checked ? "" : "BAD ") + "piece: " + num);
        if (completeListener != null)
            completeListener.gotPiece(this);
    }
  }

  public void storageAllChecked(Storage storage)
  {
    //if (checking)
    //  System.out.println();

    allChecked = true;
    checking = false;
    if (storage.isChanged() && completeListener != null) {
        completeListener.updateStatus(this);
        // this saved the status, so reset the variables
        storage.clearChanged();
        savedUploaded = getUploaded();
    }
  }
  
  public void storageCompleted(Storage storage)
  {
    if (_log.shouldLog(Log.INFO))
        _log.info("Completely received " + torrent);
    //storage.close();
    //System.out.println("Completely received: " + torrent);
    if (completeListener != null) {
        completeListener.torrentComplete(this);
        // this saved the status, so reset the variables
        savedUploaded = getUploaded();
        storage.clearChanged();
    }
  }

  public void setWantedPieces(Storage storage)
  {
    if (coordinator != null)
        coordinator.setWantedPieces();
  }

  ///////////// End StorageListener methods


  /** SnarkSnutdown callback unused */
  public void shutdown()
  {
    // Should not be necessary since all non-daemon threads should
    // have died. But in reality this does not always happen.
    //System.exit(0);
  }
  
  /**
   * StorageListener and CoordinatorListener callback
   * @since 0.9.2
   */
  public void addMessage(String message) {
      if (completeListener != null)
          completeListener.addMessage(this, message);
  }


  /** Maintain a configurable total uploader cap
   * coordinatorListener
   */
  final static int MIN_TOTAL_UPLOADERS = 4;
  final static int MAX_TOTAL_UPLOADERS = 20;

  public boolean overUploadLimit(int uploaders) {
    if (uploaders <= 0)
      return false;
    int totalUploaders = 0;
    for (PeerCoordinator c : _peerCoordinatorSet) {
      if (!c.halted())
        totalUploaders += c.getInterestedUploaders();
    }
    int limit = _util.getMaxUploaders();
    if (_log.shouldLog(Log.DEBUG))
        _log.debug("Total uploaders: " + totalUploaders + " Limit: " + limit);
    return totalUploaders > limit;
  }

  /**
   *  A unique ID for this torrent, useful for RPC
   *  @return positive value unless you wrap around
   *  @since 0.9.30
   */
  public int getRPCID() {
    return _rpcID;
  }
    
    /**
     * When did we start this torrent
     * For RPC
     * @return 0 if not started before. Not cleared when stopped.
     * @since 0.9.30
     */
    public long getStartedTime() {
        return _startedTime;
    }
    
  /**
   * The current comment set for this torrent.
   * Not a copy.
   * Caller MUST synch on the returned object for all operations.
   *
   * @return may be null if none
   * @since 0.9.31
   */
  public CommentSet getComments() {
      synchronized(_commentLock) {
          return _comments;
      }
  }
    
  /**
   * Add to the current comment set for this torrent,
   * creating it if it didn't previously exist.
   *
   * @return true if the set changed
   * @since 0.9.31
   */
  public boolean addComments(List<Comment> comments) {
      synchronized(_commentLock) {
          if (_comments == null) {
              _comments = new CommentSet(comments);
              return true;
          } else {
              synchronized(_comments) {
                  return _comments.addAll(comments);
              }
          }
      }
  }

    /**
     *  @since 0.9.71
     */
    public boolean isBanned(Hash h) {
        return acceptor.isBanned(h);
    }

    /**
     *  @since 0.9.71
     */
    public void ban(Hash h) {
        if (acceptor != null)
            acceptor.ban(h);
    }
}
