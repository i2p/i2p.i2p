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

  // Whether or not to ask the user for commands while sharing
  //private static boolean command_interpreter = true;

  private static final String newline = System.getProperty("line.separator");

/****
  private static final String copyright =
  "The Hunting of the Snark Project - Copyright (C) 2003 Mark J. Wielaard"
  + newline + newline
  + "Snark comes with ABSOLUTELY NO WARRANTY.  This is free software, and"
  + newline
  + "you are welcome to redistribute it under certain conditions; read the"
  + newline
  + "COPYING file for details." + newline + newline
  + "This is the I2P port, allowing anonymous bittorrent (http://www.i2p.net/)" + newline
  + "It will not work with normal torrents, so don't even try ;)";
  
  private static final String usage =
  "Press return for help. Type \"quit\" and return to stop.";
  private static final String help =
  "Commands: 'info', 'list', 'quit'.";
****/

  
/****
  private static class OOMListener implements I2PThread.OOMEventListener {
      public void outOfMemory(OutOfMemoryError err) {
          try {
              err.printStackTrace();
              System.out.println("OOM in the snark" + err);
          } catch (Throwable t) {
              System.out.println("OOM in the OOM");
          }
          //System.exit(0);
      }
      
  }
****/
  
/******** No, not maintaining a command-line client

  public static void main(String[] args)
  {
    System.out.println(copyright);
    System.out.println();

    if ( (args.length > 0) && ("--config".equals(args[0])) ) {
        I2PThread.addOOMEventListener(new OOMListener());
        SnarkManager sm = SnarkManager.instance();
        if (args.length > 1)
            sm.loadConfig(args[1]);
        System.out.println("Running in multitorrent mode");
        while (true) {
            try {
                synchronized (sm) {
                    sm.wait();
                }
            } catch (InterruptedException ie) {}
        }
    }
    
    // Parse debug, share/ip and torrent file options.
    Snark snark = parseArguments(args);

    SnarkShutdown snarkhook
      = new SnarkShutdown(snark.storage,
                          snark.coordinator,
                          snark.acceptor,
                          snark.trackerclient,
                          snark);
    //Runtime.getRuntime().addShutdownHook(snarkhook);

    Timer timer = new Timer(true);
    TimerTask monitor = new PeerMonitorTask(snark.coordinator);
    timer.schedule(monitor,
                   PeerMonitorTask.MONITOR_PERIOD,
                   PeerMonitorTask.MONITOR_PERIOD);

    // Start command interpreter
    if (Snark.command_interpreter)
      {
        boolean quit = false;
        
        System.out.println();
        System.out.println(usage);
        System.out.println();
        
        try
          {
            BufferedReader br = new BufferedReader
              (new InputStreamReader(System.in));
            String line = br.readLine();
            while(!quit && line != null)
              {
                line = line.toLowerCase();
                if ("quit".equals(line))
                  quit = true;
                else if ("list".equals(line))
                  {
                    synchronized(snark.coordinator.peers)
                      {
                        System.out.println(snark.coordinator.peers.size()
                                           + " peers -"
                                           + " (i)nterested,"
                                           + " (I)nteresting,"
                                           + " (c)hoking,"
                                           + " (C)hoked:");
                        Iterator it = snark.coordinator.peers.iterator();
                        while (it.hasNext())
                          {
                            Peer peer = (Peer)it.next();
                            System.out.println(peer);
                            System.out.println("\ti: " + peer.isInterested()
                                               + " I: " + peer.isInteresting()
                                               + " c: " + peer.isChoking()
                                               + " C: " + peer.isChoked());
                          }
                      }
                  }
                else if ("info".equals(line))
                  {
                    System.out.println("Name: " + snark.meta.getName());
                    System.out.println("Torrent: " + snark.torrent);
                    System.out.println("Tracker: " + snark.meta.getAnnounce());
                    List files = snark.meta.getFiles();
                    System.out.println("Files: "
                                       + ((files == null) ? 1 : files.size()));
                    System.out.println("Pieces: " + snark.meta.getPieces());
                    System.out.println("Piece size: "
                                       + snark.meta.getPieceLength(0) / 1024
                                       + " KB");
                    System.out.println("Total size: "
                                       + snark.meta.getTotalLength() / (1024 * 1024)
                                       + " MB");
                  }
                else if ("".equals(line) || "help".equals(line))
                  {
                    System.out.println(usage);
                    System.out.println(help);
                  }
                else
                  {
                    System.out.println("Unknown command: " + line);
                    System.out.println(usage);
                  }
                
                if (!quit)
                  {
                    System.out.println();
                    line = br.readLine();
                  }
              }
          }
        catch(IOException ioe)
          {
            System.out.println("ERROR while reading stdin: " + ioe);
          }
        
        // Explicit shutdown.
        //Runtime.getRuntime().removeShutdownHook(snarkhook);
        snarkhook.start();
      }
  }

***********/

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
  private String additionalTrackerURL;
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
   * from main() via parseArguments() single torrent
   *
   * @deprecated unused
   */
/****
  Snark(I2PSnarkUtil util, String torrent, String ip, int user_port,
        StorageListener slistener, CoordinatorListener clistener) { 
    this(util, torrent, ip, user_port, slistener, clistener, null, null, null, true, "."); 
  }
****/

  /**
   * single torrent - via router
   *
   * @deprecated unused
   */
/****
  public Snark(I2PAppContext ctx, Properties opts, String torrent,
               StorageListener slistener, boolean start, String rootDir) { 
    this(new I2PSnarkUtil(ctx), torrent, null, -1, slistener, null, null, null, null, false, rootDir);
    String host = opts.getProperty("i2cp.hostname");
    int port = 0;
    String s = opts.getProperty("i2cp.port");
    if (s != null) {
        try {
             port = Integer.parseInt(s);
        } catch (NumberFormatException nfe) {}
    }
    _util.setI2CPConfig(host, port, opts);
    s = opts.getProperty(SnarkManager.PROP_UPBW_MAX);
    if (s != null) {
        try {
             int v = Integer.parseInt(s);
             _util.setMaxUpBW(v);
        } catch (NumberFormatException nfe) {}
    }
    s = opts.getProperty(PROP_MAX_CONNECTIONS);
    if (s != null) {
        try {
             int v = Integer.parseInt(s);
             _util.setMaxConnections(v);
        } catch (NumberFormatException nfe) {}
    }
    if (start)
        this.startTorrent();
  }
****/

  /**
   * multitorrent
   *
   * Will not start itself. Caller must call startTorrent() if desired.
   *
   * @throws RuntimeException via fatal()
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
   * @throws RuntimeException via fatal()
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
          fatal("ERROR - Out of memory, cannot create torrent " + torrent + ": " + oom.getMessage());
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
                if (!shouldPreserve)
                    base = Storage.filterName(base);
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
            fatal("Could not check or create storage", ioe);
          }
      }


/*
 * see comment above
 *
    activity = "Collecting pieces";
    coordinator = new PeerCoordinator(id, meta, storage, clistener, this);
    PeerCoordinatorSet set = PeerCoordinatorSet.instance();
    set.add(coordinator);
    ConnectionAcceptor acceptor = ConnectionAcceptor.instance();
    acceptor.startAccepting(set, serversocket);
    trackerclient = new TrackerClient(meta, coordinator);
*/
    
    savedUploaded = (completeListener != null) ? completeListener.getSavedUploaded(this) : 0;
    if (completeListener != null)
        _comments = completeListener.getSavedComments(this);
  }

  /**
   *  multitorrent, magnet
   *
   *  Will not start itself. Caller must call startTorrent() if desired.
   *
   *  @param torrent a fake name for now (not a file name)
   *  @param ih 20-byte info hash
   *  @param trackerURL may be null
   *  @throws RuntimeException via fatal()
   *  @since 0.8.4
   */
  public Snark(I2PSnarkUtil util, String torrent, byte[] ih, String trackerURL,
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
    this.additionalTrackerURL = trackerURL;
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
    I2PAppContext.getGlobalContext().random().nextBytes(rv, 12, 8);
    return rv;
  }

  /**
   * Start up contacting peers and querying the tracker.
   * Blocks if tunnel is not yet open.
   *
   * @throws RuntimeException via fatal()
   */
  public synchronized void startTorrent() {
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
    if (!ok) fatal("Unable to connect to I2P");
    if (coordinator == null) {
        I2PServerSocket serversocket = _util.getServerSocket();
        if (serversocket == null)
            fatal("Unable to listen for I2P connections");
        else {
            Destination d = serversocket.getManager().getSession().getMyDestination();
            if (_log.shouldLog(Log.INFO))
                _log.info("Listening on I2P destination " + d.toBase64() + " / " + d.calculateHash().toBase64());
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Starting PeerCoordinator, ConnectionAcceptor, and TrackerClient");
        activity = "Collecting pieces";
        coordinator = new PeerCoordinator(_util, id, infoHash, meta, storage, this, this);
        coordinator.setUploaded(savedUploaded);
        if (_peerCoordinatorSet != null) {
            // multitorrent
            _peerCoordinatorSet.add(coordinator);
        } else {
            // single torrent
            acceptor = new ConnectionAcceptor(_util, new PeerAcceptor(coordinator));
        }
        // TODO pass saved closest DHT nodes to the tracker? or direct to the coordinator?
        trackerclient = new TrackerClient(_util, meta, additionalTrackerURL, coordinator, this);
    }
    // ensure acceptor is running when in multitorrent
    if (_peerCoordinatorSet != null && acceptor != null) {
        acceptor.startAccepting();
    }

    stopped = false;
    if (coordinator.halted()) {
        coordinator.restart();
        if (_peerCoordinatorSet != null)
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
                 fatal("Could not reopen storage", ioe);
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
        boolean changed = storage.isChanged() || nowUploaded != savedUploaded;
        try { 
            storage.close(); 
        } catch (IOException ioe) {
            System.out.println("Error closing " + torrent);
            ioe.printStackTrace();
        }
        savedUploaded = nowUploaded;
        if (changed && completeListener != null)
            completeListener.updateStatus(this);
        // TODO should save comments at shutdown even if never started...
        if (completeListener != null) {
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
    if (pc != null && _peerCoordinatorSet != null)
        _peerCoordinatorSet.remove(pc);
    if (_peerCoordinatorSet == null)
        _util.disconnect();
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
        if (acceptor == null)
            return false;
        acceptor.restart();
        return true;
    }

    /**
     *  @return trackerURL string from magnet-mode constructor, may be null
     *  @since 0.8.4
     */
    public String getTrackerURL() {
        return additionalTrackerURL;
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
   * Sets debug, ip and torrent variables then creates a Snark
   * instance.  Calls usage(), which terminates the program, if
   * non-valid argument list.  The given listeners will be
   * passed to all components that take one.
   */
/****
  private static Snark parseArguments(String[] args,
                              StorageListener slistener,
                              CoordinatorListener clistener)
  {
    int user_port = -1;
    String ip = null;
    String torrent = null;

    I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
    boolean configured = util.configured();
    
    int i = 0;
    while (i < args.length)
      {
****/
/*
        if (args[i].equals("--debug"))
          {
            debug = INFO;
            i++;

            // Try if there is an level argument.
            if (i < args.length)
              {
                try
                  {
                    int level = Integer.parseInt(args[i]);
                    if (level >= 0)
                      {
                        debug = level;
                        i++;
                      }
                  }
                catch (NumberFormatException nfe) { }
              }
          }
        else */
/****
          if (args[i].equals("--port"))
          {
            if (args.length - 1 < i + 1)
              usage("--port needs port number to listen on");
            try
              {
                user_port = Integer.parseInt(args[i + 1]);
              }
            catch (NumberFormatException nfe)
              {
                usage("--port argument must be a number (" + nfe + ")");
              }
            i += 2;
          }
        else if (args[i].equals("--no-commands"))
          {
            //command_interpreter = false;
            i++;
          }
        //else if (args[i].equals("--eepproxy"))
        //  {
        //    String proxyHost = args[i+1];
        //    String proxyPort = args[i+2];
        //    if (!configured)
        //        util.setProxy(proxyHost, Integer.parseInt(proxyPort));
        //    i += 3;
        //  }
        else if (args[i].equals("--i2cp"))
          {
            String i2cpHost = args[i+1];
            String i2cpPort = args[i+2];
            Properties opts = null;
            if (i+3 < args.length) {
                if (!args[i+3].startsWith("--")) {
                    opts = new Properties();
                    StringTokenizer tok = new StringTokenizer(args[i+3], " \t");
                    while (tok.hasMoreTokens()) {
                        String str = tok.nextToken();
                        int split = str.indexOf('=');
                        if (split > 0) {
                            opts.setProperty(str.substring(0, split), str.substring(split+1));
                        }
                    }
                }
            }
            if (!configured)
                util.setI2CPConfig(i2cpHost, Integer.parseInt(i2cpPort), opts);
            i += 3 + (opts != null ? 1 : 0);
          }
        else
          {
            torrent = args[i];
            i++;
            break;
          }
      }

    if (torrent == null || i != args.length)
      if (torrent != null && torrent.startsWith("-"))
        usage("Unknow option '" + torrent + "'.");
      else
        usage("Need exactly one <url>, <file> or <dir>.");

    return new Snark(util, torrent, ip, user_port, slistener, clistener);
  }
  
  private static void usage(String s)
  {
    System.out.println("snark: " + s);
    usage();
  }

  private static void usage()
  {
    System.out.println
      ("Usage: snark [--no-commands] [--port <port>]");
    System.out.println
      ("             [--eepproxy hostname portnum]");
    System.out.println
      ("             [--i2cp routerHost routerPort ['name=val name=val name=val']]");
    System.out.println
      ("             (<url>|<file>)");
    System.out.println
      ("  --no-commands\tDon't read interactive commands or show usage info.");
    System.out.println
      ("  --port\tThe port to listen on for incomming connections");
    System.out.println
      ("        \t(if not given defaults to first free port between "
       + MIN_PORT + "-" + MAX_PORT + ").");
    System.out.println
      ("  --share\tStart torrent tracker on <ip> address or <host> name.");
    System.out.println
      ("  --eepproxy\thttp proxy to use (default of 127.0.0.1 port 4444)");
    System.out.println
      ("  --i2cp\tlocation of your I2P router (default of 127.0.0.1 port 7654)");
    System.out.println
      ("        \toptional settings may be included, such as");
    System.out.println
      ("        \tinbound.length=2 outbound.length=2 inbound.lengthVariance=-1 ");
    System.out.println
      ("  <url>  \tURL pointing to .torrent metainfo file to download/share.");
    System.out.println
      ("  <file> \tEither a local .torrent metainfo file to download");
    System.out.println
      ("         \tor (with --share) a file to share.");
  }
****/

  /**
   * Aborts program abnormally.
   */
  private void fatal(String s)
  {
    fatal(s, null);
  }

  /**
   * Aborts program abnormally.
   */
  private void fatal(String s, Throwable t)
  {
    _log.error(s, t);
    //System.err.println("snark: " + s + ((t == null) ? "" : (": " + t)));
    //if (debug >= INFO && t != null)
    //  t.printStackTrace();
    stopTorrent();
    if (t != null)
        s += ": " + t;
    if (completeListener != null)
        completeListener.fatal(this, s);
    throw new RuntimeException(s, t);
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
          fatal("Could not create data files", ioe);
      }
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
    if (_peerCoordinatorSet == null || uploaders <= 0)
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

  public boolean overUpBWLimit() {
    if (_peerCoordinatorSet == null)
      return false;
    long total = 0;
    for (PeerCoordinator c : _peerCoordinatorSet) {
      if (!c.halted())
        total += c.getCurrentUploadRate();
    }
    long limit = 1024l * _util.getMaxUpBW();
    if (_log.shouldLog(Log.INFO))
        _log.info("Total up bw: " + total + " Limit: " + limit);
    return total > limit;
  }

  public boolean overUpBWLimit(long total) {
    long limit = 1024l * _util.getMaxUpBW();
    return total > limit;
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
}
