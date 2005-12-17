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

import java.io.*;
import java.net.*;
import java.util.*;

import org.klomp.snark.bencode.*;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PServerSocket;
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
  public static int debug = NOTICE;

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
              I2PSnarkUtil.instance().debug("OOM in the snark", Snark.ERROR, err);
          } catch (Throwable t) {
              System.out.println("OOM in the OOM");
          }
          System.exit(0);
      }
      
  }
  
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
            debug("ERROR while reading stdin: " + ioe, ERROR);
          }
        
        // Explicit shutdown.
        Runtime.getRuntime().removeShutdownHook(snarkhook);
        snarkhook.start();
      }
  }

  public String torrent;
  public MetaInfo meta;
  public Storage storage;
  public PeerCoordinator coordinator;
  public ConnectionAcceptor acceptor;
  public TrackerClient trackerclient;
  public String rootDataDir = ".";
  public CompleteListener completeListener;
  public boolean stopped;

  Snark(String torrent, String ip, int user_port,
        StorageListener slistener, CoordinatorListener clistener) { 
    this(torrent, ip, user_port, slistener, clistener, true, "."); 
  }
  Snark(String torrent, String ip, int user_port,
        StorageListener slistener, CoordinatorListener clistener, boolean start, String rootDir)
  {
    if (slistener == null)
      slistener = this;

    if (clistener == null)
      clistener = this;

    this.torrent = torrent;
    this.rootDataDir = rootDir;

    stopped = true;
    activity = "Network setup";

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
    byte[] id = new byte[20];
    Random random = new Random();
    int i;
    for (i = 0; i < 9; i++)
      id[i] = 0;
    id[i++] = snark;
    id[i++] = snark;
    id[i++] = snark;
    while (i < 20)
      id[i++] = (byte)random.nextInt(256);

    Snark.debug("My peer id: " + PeerID.idencode(id), Snark.INFO);

    int port;
    IOException lastException = null;
    boolean ok = I2PSnarkUtil.instance().connect();
    if (!ok) fatal("Unable to connect to I2P");
    I2PServerSocket serversocket = I2PSnarkUtil.instance().getServerSocket();
    if (serversocket == null)
        fatal("Unable to listen for I2P connections");
    else
        debug("Listening on I2P destination " + serversocket.getManager().getSession().getMyDestination().toBase64(), NOTICE);

    // Figure out what the torrent argument represents.
    meta = null;
    File f = null;
    try
      {
        InputStream in = null;
        f = new File(torrent);
        if (f.exists())
          in = new FileInputStream(f);
        else
          {
            activity = "Getting torrent";
            File torrentFile = I2PSnarkUtil.instance().get(torrent);
            if (torrentFile == null) {
                fatal("Unable to fetch " + torrent);
                if (false) return; // never reached - fatal(..) throws
            } else {
                torrentFile.deleteOnExit();
                in = new FileInputStream(torrentFile);
            }
          }
        meta = new MetaInfo(new BDecoder(in));
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
             Snark.debug
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
      }
    
    debug(meta.toString(), INFO);
    
    // When the metainfo torrent was created from an existing file/dir
    // it already exists.
    if (storage == null)
      {
        try
          {
            activity = "Checking storage";
            storage = new Storage(meta, slistener);
            storage.check(rootDataDir);
          }
        catch (IOException ioe)
          {
            try { storage.close(); } catch (IOException ioee) {
                ioee.printStackTrace();
            }
            fatal("Could not create storage", ioe);
          }
      }

    activity = "Collecting pieces";
    coordinator = new PeerCoordinator(id, meta, storage, clistener, this);
    PeerCoordinatorSet set = PeerCoordinatorSet.instance();
    set.add(coordinator);
    PeerAcceptor peeracceptor = new PeerAcceptor(set); //coordinator);
    ConnectionAcceptor acceptor = new ConnectionAcceptor(serversocket,
                                                         peeracceptor);

    trackerclient = new TrackerClient(meta, coordinator);
    if (start)
        startTorrent();
  }
  /**
   * Start up contacting peers and querying the tracker
   */
  public void startTorrent() {
    stopped = false;
    boolean coordinatorChanged = false;
    if (coordinator.halted()) {
        // ok, we have already started and stopped, but the coordinator seems a bit annoying to
        // restart safely, so lets build a new one to replace the old
        PeerCoordinatorSet set = PeerCoordinatorSet.instance();
        set.remove(coordinator);
        PeerCoordinator newCoord = new PeerCoordinator(coordinator.getID(), coordinator.getMetaInfo(), 
                                                       coordinator.getStorage(), coordinator.getListener(), this);
        set.add(newCoord);
        coordinator = newCoord;
        coordinatorChanged = true;
    }
    if (!trackerclient.started() && !coordinatorChanged) {
        trackerclient.start();
    } else if (trackerclient.halted() || coordinatorChanged) {
        TrackerClient newClient = new TrackerClient(coordinator.getMetaInfo(), coordinator);
        if (!trackerclient.halted())
            trackerclient.halt();
        trackerclient = newClient;
        trackerclient.start();
    }
  }
  /**
   * Stop contacting the tracker and talking with peers
   */
  public void stopTorrent() {
    stopped = true;
    trackerclient.halt();
    coordinator.halt();
    try { 
        storage.close(); 
    } catch (IOException ioe) {
        System.out.println("Error closing " + torrent);
        ioe.printStackTrace();
    }
    PeerCoordinatorSet.instance().remove(coordinator);
  }

  static Snark parseArguments(String[] args)
  {
    return parseArguments(args, null, null);
  }

  /**
   * Sets debug, ip and torrent variables then creates a Snark
   * instance.  Calls usage(), which terminates the program, if
   * non-valid argument list.  The given listeners will be
   * passed to all components that take one.
   */
  static Snark parseArguments(String[] args,
                              StorageListener slistener,
                              CoordinatorListener clistener)
  {
    int user_port = -1;
    String ip = null;
    String torrent = null;

    boolean configured = I2PSnarkUtil.instance().configured();
    
    int i = 0;
    while (i < args.length)
      {
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
        else if (args[i].equals("--port"))
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
        else if (args[i].equals("--eepproxy"))
          {
            String proxyHost = args[i+1];
            String proxyPort = args[i+2];
            if (!configured)
                I2PSnarkUtil.instance().setProxy(proxyHost, Integer.parseInt(proxyPort));
            i += 3;
          }
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
                I2PSnarkUtil.instance().setI2CPConfig(i2cpHost, Integer.parseInt(i2cpPort), opts);
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

    return new Snark(torrent, ip, user_port, slistener, clistener);
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
    System.exit(-1);
  }

  /**
   * Aborts program abnormally.
   */
  public void fatal(String s)
  {
    fatal(s, null);
  }

  /**
   * Aborts program abnormally.
   */
  public void fatal(String s, Throwable t)
  {
    I2PSnarkUtil.instance().debug(s, ERROR, t);
    //System.err.println("snark: " + s + ((t == null) ? "" : (": " + t)));
    //if (debug >= INFO && t != null)
    //  t.printStackTrace();
    stopTorrent();
    throw new RuntimeException("die bart die");
  }

  /**
   * Show debug info if debug is true.
   */
  public static void debug(String s, int level)
  {
    I2PSnarkUtil.instance().debug(s, level, null);
    //if (debug >= level)
    //  System.out.println(s);
  }

  public void peerChange(PeerCoordinator coordinator, Peer peer)
  {
    // System.out.println(peer.toString());
  }
  
  boolean allocating = false;
  public void storageCreateFile(Storage storage, String name, long length)
  {
    if (allocating)
      System.out.println(); // Done with last file.

    System.out.print("Creating file '" + name
                     + "' of length " + length + ": ");
    allocating = true;
  }

  // How much storage space has been allocated
  private long allocated = 0;

  public void storageAllocated(Storage storage, long length)
  {
    allocating = true;
    System.out.print(".");
    allocated += length;
    if (allocated == meta.getTotalLength())
      System.out.println(); // We have all the disk space we need.
  }

  boolean allChecked = false;
  boolean checking = false;
  boolean prechecking = true;
  public void storageChecked(Storage storage, int num, boolean checked)
  {
    allocating = false;
    if (!allChecked && !checking)
      {
        // Use the MetaInfo from the storage since our own might not
        // yet be setup correctly.
        MetaInfo meta = storage.getMetaInfo();
        if (meta != null)
          System.out.print("Checking existing "
                           + meta.getPieces()
                           + " pieces: ");
        checking = true;
      }
    if (checking)
      if (checked)
        System.out.print("+");
      else
        System.out.print("-");
    else
      Snark.debug("Got " + (checked ? "" : "BAD ") + "piece: " + num,
                  Snark.INFO);
  }

  public void storageAllChecked(Storage storage)
  {
    if (checking)
      System.out.println();

    allChecked = true;
    checking = false;
  }
  
  public void storageCompleted(Storage storage)
  {
    Snark.debug("Completely received " + torrent, Snark.INFO);
    //storage.close();
    System.out.println("Completely received: " + torrent);
    if (completeListener != null)
        completeListener.torrentComplete(this);
  }

  public void shutdown()
  {
    // Should not be necessary since all non-deamon threads should
    // have died. But in reality this does not always happen.
    System.exit(0);
  }
  
  public interface CompleteListener {
    public void torrentComplete(Snark snark);
  }
}
