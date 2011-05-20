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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.data.Destination;
import net.i2p.util.I2PThread;

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

  // Error messages (non-fatal)
  public final static int ERROR   = 1;

  // Warning messages
  public final static int WARNING = 2;

  // Notices (peer level)
  public final static int NOTICE  = 3;

  // Info messages (protocol policy level)
  public final static int INFO    = 4;

  // Debug info (protocol level)
  public final static int DEBUG   = 5;

  // Very low level stuff (network level)
  public final static int ALL     = 6;

  /**
   * What level of debug info to show.
   */
  //public static int debug = NOTICE;

  // Whether or not to ask the user for commands while sharing
  private static boolean command_interpreter = true;

  private static final String newline = System.getProperty("line.separator");

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

  // String indicating main activity
  String activity = "Not started";
  
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

  public static final String PROP_MAX_CONNECTIONS = "i2psnark.maxConnections";

  /** most of these used to be public, use accessors below instead */
  private String torrent;
  private MetaInfo meta;
  private Storage storage;
  private PeerCoordinator coordinator;
  private ConnectionAcceptor acceptor;
  private TrackerClient trackerclient;
  private String rootDataDir = ".";
  private final CompleteListener completeListener;
  private boolean stopped;
  private byte[] id;
  private byte[] infoHash;
  private String additionalTrackerURL;
  private final I2PSnarkUtil _util;
  private final PeerCoordinatorSet _peerCoordinatorSet;
  private String trackerProblems;
  private int trackerSeenPeers;


  /** from main() via parseArguments() single torrent */
  Snark(I2PSnarkUtil util, String torrent, String ip, int user_port,
        StorageListener slistener, CoordinatorListener clistener) { 
    this(util, torrent, ip, user_port, slistener, clistener, null, null, null, true, "."); 
  }

  /** single torrent - via router */
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

  /** multitorrent */
  public Snark(I2PSnarkUtil util, String torrent, String ip, int user_port,
        StorageListener slistener, CoordinatorListener clistener,
        CompleteListener complistener, PeerCoordinatorSet peerCoordinatorSet,
        ConnectionAcceptor connectionAcceptor, boolean start, String rootDir)
  {
    if (slistener == null)
      slistener = this;

    completeListener = complistener;
    _util = util;
    _peerCoordinatorSet = peerCoordinatorSet;
    acceptor = connectionAcceptor;

    this.torrent = torrent;
    this.rootDataDir = rootDir;

    stopped = true;
    activity = "Network setup";

    id = generateID();
    debug("My peer id: " + PeerID.idencode(id), Snark.INFO);

    int port;
    IOException lastException = null;
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
    meta = null;
    File f = null;
    InputStream in = null;
    try
      {
        f = new File(torrent);
        if (f.exists())
          in = new FileInputStream(f);
        else
          {
            activity = "Getting torrent";
            File torrentFile = _util.get(torrent, 3);
            if (torrentFile == null) {
                fatal("Unable to fetch " + torrent);
                if (false) return; // never reached - fatal(..) throws
            } else {
                torrentFile.deleteOnExit();
                in = new FileInputStream(torrentFile);
            }
          }
        meta = new MetaInfo(in);
        infoHash = meta.getInfoHash();
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

    debug(meta.toString(), INFO);
    
    // When the metainfo torrent was created from an existing file/dir
    // it already exists.
    if (storage == null)
      {
        try
          {
            activity = "Checking storage";
            storage = new Storage(_util, meta, slistener);
            if (completeListener != null) {
                storage.check(rootDataDir,
                              completeListener.getSavedTorrentTime(this),
                              completeListener.getSavedTorrentBitField(this));
            } else {
                storage.check(rootDataDir);
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
    
    if (start)
        startTorrent();
  }

  /**
   *  multitorrent, magnet
   *
   *  @param torrent a fake name for now (not a file name)
   *  @param ih 20-byte info hash
   *  @param trackerURL may be null
   *  @since 0.8.4
   */
  public Snark(I2PSnarkUtil util, String torrent, byte[] ih, String trackerURL,
        CompleteListener complistener, PeerCoordinatorSet peerCoordinatorSet,
        ConnectionAcceptor connectionAcceptor, boolean start, String rootDir)
  {
    completeListener = complistener;
    _util = util;
    _peerCoordinatorSet = peerCoordinatorSet;
    acceptor = connectionAcceptor;
    this.torrent = torrent;
    this.infoHash = ih;
    this.additionalTrackerURL = trackerURL;
    this.rootDataDir = rootDir;
    stopped = true;
    id = generateID();

    // All we have is an infoHash
    // meta remains null
    // storage remains null

    if (start)
        startTorrent();
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
    // sixteen random bytes.
    byte snark = (((3 + 7 + 10) * (1000 - 8)) / 992) - 17;
    byte[] rv = new byte[20];
    Random random = I2PAppContext.getGlobalContext().random();
    int i;
    for (i = 0; i < 9; i++)
      rv[i] = 0;
    rv[i++] = snark;
    rv[i++] = snark;
    rv[i++] = snark;
    while (i < 20)
      rv[i++] = (byte)random.nextInt(256);
    return rv;
  }

  /**
   * Start up contacting peers and querying the tracker
   */
  public void startTorrent() {
    boolean ok = _util.connect();
    if (!ok) fatal("Unable to connect to I2P");
    if (coordinator == null) {
        I2PServerSocket serversocket = _util.getServerSocket();
        if (serversocket == null)
            fatal("Unable to listen for I2P connections");
        else {
            Destination d = serversocket.getManager().getSession().getMyDestination();
            debug("Listening on I2P destination " + d.toBase64() + " / " + d.calculateHash().toBase64(), NOTICE);
        }
        debug("Starting PeerCoordinator, ConnectionAcceptor, and TrackerClient", NOTICE);
        activity = "Collecting pieces";
        coordinator = new PeerCoordinator(_util, id, infoHash, meta, storage, this, this);
        if (_peerCoordinatorSet != null) {
            // multitorrent
            _peerCoordinatorSet.add(coordinator);
            if (acceptor != null) {
                acceptor.startAccepting(_peerCoordinatorSet, serversocket);
            } else {
              // error
            }
        } else {
            // single torrent
            acceptor = new ConnectionAcceptor(_util, serversocket, new PeerAcceptor(coordinator));
        }
        // TODO pass saved closest DHT nodes to the tracker? or direct to the coordinator?
        trackerclient = new TrackerClient(_util, meta, additionalTrackerURL, coordinator, this);
    }

    stopped = false;
    boolean coordinatorChanged = false;
    if (coordinator.halted()) {
        // ok, we have already started and stopped, but the coordinator seems a bit annoying to
        // restart safely, so lets build a new one to replace the old
        if (_peerCoordinatorSet != null)
            _peerCoordinatorSet.remove(coordinator);
        PeerCoordinator newCoord = new PeerCoordinator(_util, id, infoHash, meta, storage, this, this);
        if (_peerCoordinatorSet != null)
            _peerCoordinatorSet.add(newCoord);
        coordinator = newCoord;
        coordinatorChanged = true;
    }
    if (!trackerclient.started() && !coordinatorChanged) {
        trackerclient.start();
    } else if (trackerclient.halted() || coordinatorChanged) {
        if (storage != null) {
            try {
                 storage.reopen(rootDataDir);
             }   catch (IOException ioe) {
                 try { storage.close(); } catch (IOException ioee) {
                     ioee.printStackTrace();
                 }
                 fatal("Could not reopen storage", ioe);
             }
        }
        TrackerClient newClient = new TrackerClient(_util, meta, additionalTrackerURL, coordinator, this);
        if (!trackerclient.halted())
            trackerclient.halt();
        trackerclient = newClient;
        trackerclient.start();
    } else {
        debug("NOT starting TrackerClient???", NOTICE);
    }
  }
  /**
   * Stop contacting the tracker and talking with peers
   */
  public void stopTorrent() {
    stopped = true;
    TrackerClient tc = trackerclient;
    if (tc != null)
        tc.halt();
    PeerCoordinator pc = coordinator;
    if (pc != null)
        pc.halt();
    Storage st = storage;
    if (st != null) {
        boolean changed = storage.isChanged();
        try { 
            storage.close(); 
        } catch (IOException ioe) {
            System.out.println("Error closing " + torrent);
            ioe.printStackTrace();
        }
        if (changed && completeListener != null)
            completeListener.updateStatus(this);
    }
    if (pc != null && _peerCoordinatorSet != null)
        _peerCoordinatorSet.remove(pc);
    if (_peerCoordinatorSet == null)
        _util.disconnect();
  }

  private static Snark parseArguments(String[] args)
  {
    return parseArguments(args, null, null);
  }

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
        return 0;
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
        return Collections.EMPTY_LIST;
    }

    /**
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
   * Sets debug, ip and torrent variables then creates a Snark
   * instance.  Calls usage(), which terminates the program, if
   * non-valid argument list.  The given listeners will be
   * passed to all components that take one.
   */
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
        else */ if (args[i].equals("--port"))
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
            command_interpreter = false;
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
      ("Usage: snark [--debug [level]] [--no-commands] [--port <port>]");
    System.out.println
      ("             [--eepproxy hostname portnum]");
    System.out.println
      ("             [--i2cp routerHost routerPort ['name=val name=val name=val']]");
    System.out.println
      ("             (<url>|<file>)");
    System.out.println
      ("  --debug\tShows some extra info and stacktraces");
    System.out.println
      ("    level\tHow much debug details to show");
    System.out.println
      ("         \t(defaults to "
       + NOTICE + ", with --debug to "
       + INFO + ", highest level is "
       + ALL + ").");
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
    _util.debug(s, ERROR, t);
    //System.err.println("snark: " + s + ((t == null) ? "" : (": " + t)));
    //if (debug >= INFO && t != null)
    //  t.printStackTrace();
    stopTorrent();
    throw new RuntimeException(s, t);
  }

  /**
   * Show debug info if debug is true.
   */
  private void debug(String s, int level)
  {
    _util.debug(s, level, null);
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
      meta = metainfo;
      try {
          storage = new Storage(_util, meta, this);
          storage.check(rootDataDir);
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
          }
          fatal("Could not check or create storage", ioe);
      }
  }

  private boolean allocating = false;
  public void storageCreateFile(Storage storage, String name, long length)
  {
    //if (allocating)
    //  System.out.println(); // Done with last file.

    //System.out.print("Creating file '" + name
    //                 + "' of length " + length + ": ");
    allocating = true;
  }

  // How much storage space has been allocated
  private long allocated = 0;

  public void storageAllocated(Storage storage, long length)
  {
    allocating = true;
    //System.out.print(".");
    allocated += length;
    //if (allocated == meta.getTotalLength())
    //  System.out.println(); // We have all the disk space we need.
  }

  private boolean allChecked = false;
  private boolean checking = false;
  private boolean prechecking = true;
  public void storageChecked(Storage storage, int num, boolean checked)
  {
    allocating = false;
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
    if (!checking)
      debug("Got " + (checked ? "" : "BAD ") + "piece: " + num,
                  Snark.INFO);
  }

  public void storageAllChecked(Storage storage)
  {
    //if (checking)
    //  System.out.println();

    allChecked = true;
    checking = false;
    if (storage.isChanged() && completeListener != null)
        completeListener.updateStatus(this);
  }
  
  public void storageCompleted(Storage storage)
  {
    debug("Completely received " + torrent, Snark.INFO);
    //storage.close();
    //System.out.println("Completely received: " + torrent);
    if (completeListener != null)
        completeListener.torrentComplete(this);
  }

  public void setWantedPieces(Storage storage)
  {
    coordinator.setWantedPieces();
  }

  /** SnarkSnutdown callback unused */
  public void shutdown()
  {
    // Should not be necessary since all non-deamon threads should
    // have died. But in reality this does not always happen.
    //System.exit(0);
  }
  
  public interface CompleteListener {
    public void torrentComplete(Snark snark);
    public void updateStatus(Snark snark);

    /**
     * We transitioned from magnet mode, we have now initialized our
     * metainfo and storage. The listener should now call getMetaInfo()
     * and save the data to disk.
     *
     * @return the new name for the torrent or null on error
     * @since 0.8.4
     */
    public String gotMetaInfo(Snark snark);

    // not really listeners but the easiest way to get back to an optional SnarkManager
    public long getSavedTorrentTime(Snark snark);
    public BitField getSavedTorrentBitField(Snark snark);
  }

  /** Maintain a configurable total uploader cap
   * coordinatorListener
   */
  final static int MIN_TOTAL_UPLOADERS = 4;
  final static int MAX_TOTAL_UPLOADERS = 10;
  public boolean overUploadLimit(int uploaders) {
    if (_peerCoordinatorSet == null || uploaders <= 0)
      return false;
    int totalUploaders = 0;
    for (Iterator iter = _peerCoordinatorSet.iterator(); iter.hasNext(); ) {
      PeerCoordinator c = (PeerCoordinator)iter.next();
      if (!c.halted())
        totalUploaders += c.uploaders;
    }
    int limit = _util.getMaxUploaders();
    // debug("Total uploaders: " + totalUploaders + " Limit: " + limit, Snark.DEBUG);
    return totalUploaders > limit;
  }

  public boolean overUpBWLimit() {
    if (_peerCoordinatorSet == null)
      return false;
    long total = 0;
    for (Iterator iter = _peerCoordinatorSet.iterator(); iter.hasNext(); ) {
      PeerCoordinator c = (PeerCoordinator)iter.next();
      if (!c.halted())
        total += c.getCurrentUploadRate();
    }
    long limit = 1024l * _util.getMaxUpBW();
    debug("Total up bw: " + total + " Limit: " + limit, Snark.WARNING);
    return total > limit;
  }

  public boolean overUpBWLimit(long total) {
    long limit = 1024l * _util.getMaxUpBW();
    return total > limit;
  }
}
