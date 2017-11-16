/* Storage - Class used to store and retrieve pieces.
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

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import gnu.getopt.Getopt;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA1;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;
import net.i2p.util.SystemVersion;

/**
 * Maintains pieces on disk. Can be used to store and retrieve pieces.
 */
public class Storage implements Closeable
{
  private final MetaInfo metainfo;
  private final List<TorrentFile> _torrentFiles;
  private final File _base;
  private final StorageListener listener;
  private final I2PSnarkUtil _util;
  private final Log _log;

  private /* FIXME final FIXME */ BitField bitfield; // BitField to represent the pieces
  private int needed; // Number of pieces needed
  private boolean _probablyComplete;  // use this to decide whether to open files RO

  private final int piece_size;
  private final int pieces;
  private final long total_length;
  private final boolean _preserveFileNames;
  private boolean changed;
  private volatile boolean _isChecking;
  private final AtomicInteger _allocateCount = new AtomicInteger();
  private final AtomicInteger _checkProgress = new AtomicInteger();

  /** The default piece size. */
  private static final int DEFAULT_PIECE_SIZE = 256*1024;
  /** bigger than this will be rejected */
  public static final int MAX_PIECE_SIZE = 16*1024*1024;
  /** The maximum number of pieces in a torrent. */
  public static final int MAX_PIECES = 32*1024;
  public static final long MAX_TOTAL_SIZE = MAX_PIECE_SIZE * (long) MAX_PIECES;

  private static final Map<String, String> _filterNameCache = new ConcurrentHashMap<String, String>();

  private static final boolean _isWindows = SystemVersion.isWindows();
  private static final boolean _isARM = SystemVersion.isARM();

  private static final int BUFSIZE = PeerState.PARTSIZE;
  private static final ByteCache _cache = ByteCache.getInstance(16, BUFSIZE);

  /**
   * Creates a new storage based on the supplied MetaInfo.
   *
   * Does not check storage. Caller MUST call check(), which will
   * try to create and/or check all needed files in the MetaInfo.
   *
   * @param baseFile the torrent data file or dir
   * @param preserveFileNames if true, do not remap names to a 'safe' charset
   */
  public Storage(I2PSnarkUtil util, File baseFile, MetaInfo metainfo, StorageListener listener, boolean preserveFileNames)
  {
    _util = util;
    _log = util.getContext().logManager().getLog(Storage.class);
    _base = baseFile;
    this.metainfo = metainfo;
    this.listener = listener;
    needed = metainfo.getPieces();
    bitfield = new BitField(needed);
    piece_size = metainfo.getPieceLength(0);
    pieces = needed;
    total_length = metainfo.getTotalLength();
    List<List<String>> files = metainfo.getFiles();
    int sz = files != null ? files.size() : 1;
    _torrentFiles = new ArrayList<TorrentFile>(sz);
    _preserveFileNames = preserveFileNames;
  }

  /**
   * Creates a storage from the existing file or directory.
   * Creates an in-memory metainfo but does not save it to
   * a file, caller must do that.
   *
   * Creates the metainfo, this may take a LONG time. BLOCKING.
   *
   * @param announce may be null
   * @param listener may be null
   * @param created_by may be null
   * @throws IOException when creating and/or checking files fails.
   */
  public Storage(I2PSnarkUtil util, File baseFile, String announce,
                 List<List<String>> announce_list,
                 String created_by,
                 boolean privateTorrent, StorageListener listener)
    throws IOException
  {
    _util = util;
    _base = baseFile;
    _log = util.getContext().logManager().getLog(Storage.class);
    this.listener = listener;
    _preserveFileNames = true;
    // Create names, rafs and lengths arrays.
    _torrentFiles = getFiles(baseFile);
    
    long total = 0;
    ArrayList<Long> lengthsList = new ArrayList<Long>();
    for (TorrentFile tf : _torrentFiles)
      {
        long length = tf.length;
        total += length;
        lengthsList.add(Long.valueOf(length));
      }

    if (total <= 0)
        throw new IOException("Torrent contains no data");
    if (total > MAX_TOTAL_SIZE)
        throw new IOException("Torrent too big (" + total + " bytes), max is " + MAX_TOTAL_SIZE);

    int pc_size;
    if (total <= 5*1024*1024)
        pc_size = DEFAULT_PIECE_SIZE / 4;
    else if (total <= 10*1024*1024)
        pc_size = DEFAULT_PIECE_SIZE / 2;
    else
        pc_size = DEFAULT_PIECE_SIZE;
    int pcs = (int) ((total - 1)/pc_size) + 1;
    while (pcs > (MAX_PIECES / 3) && pc_size < MAX_PIECE_SIZE)
      {
        pc_size *= 2;
        pcs = (int) ((total - 1)/pc_size) +1;
      }
    piece_size = pc_size;
    pieces = pcs;
    total_length = total;

    bitfield = new BitField(pieces);
    needed = 0;

    List<List<String>> files = new ArrayList<List<String>>();
    for (TorrentFile tf : _torrentFiles)
      {
        List<String> file = new ArrayList<String>();
        StringTokenizer st = new StringTokenizer(tf.name, File.separator);
        while (st.hasMoreTokens())
          {
            String part = st.nextToken();
            file.add(part);
          }
        files.add(file);
      }

    if (files.size() == 1 && !baseFile.isDirectory())
      {
        files = null;
        lengthsList = null;
      }

    // TODO thread this so we can return and show something on the UI
    byte[] piece_hashes = fast_digestCreate();
    metainfo = new MetaInfo(announce, baseFile.getName(), null, files,
                            lengthsList, piece_size, piece_hashes, total, privateTorrent,
                            announce_list, created_by);

  }

  /**
   * Creates piece hashes for a new storage.
   * This does NOT create the files, just the hashes.
   * Also sets all the bitfield bits.
   *
   *  FIXME we can run out of fd's doing this,
   *  maybe some sort of global close-RAF-right-away flag
   *  would do the trick
   */
  private byte[] fast_digestCreate() throws IOException {
    // Calculate piece_hashes
    MessageDigest digest = SHA1.getInstance();

    byte[] piece_hashes = new byte[20 * pieces];

    byte[] piece = new byte[piece_size];
    for (int i = 0; i < pieces; i++)
      {
        int length = getUncheckedPiece(i, piece);
        digest.update(piece, 0, length);
        byte[] hash = digest.digest();
        System.arraycopy(hash, 0, piece_hashes, 20 * i, 20);
        bitfield.set(i);
      }
    return piece_hashes;
  }

  private List<TorrentFile> getFiles(File base) throws IOException
  {
    if (base.getAbsolutePath().equals("/"))
        throw new IOException("Don't seed root");
    List<File> files = new ArrayList<File>();
    addFiles(files, base);

    int size = files.size();
    List<TorrentFile> rv = new ArrayList<TorrentFile>(size);

    for (File f : files) {
        rv.add(new TorrentFile(base, f));
    }
    // Sort to prevent exposing OS type, and to make it more likely
    // the same torrent created twice will have the same infohash.
    Collections.sort(rv);
    return rv;
  }

  /**
   *  @throws IOException if too many total files
   */
  private void addFiles(List<File> l, File f) throws IOException {
    if (!f.isDirectory()) {
        if (l.size() >= SnarkManager.MAX_FILES_PER_TORRENT)
            throw new IOException("Too many files, limit is " + SnarkManager.MAX_FILES_PER_TORRENT + ", zip them?");
        l.add(f);
    } else {
        File[] files = f.listFiles();
        if (files == null)
          {
            if (_log.shouldLog(Log.WARN))
                _log.warn("WARNING: Skipping '" + f 
                        + "' not a normal file.");
            return;
          }
        for (int i = 0; i < files.length; i++)
          addFiles(l, files[i]);
      }
  }

  /**
   * Returns the MetaInfo associated with this Storage.
   */
  public MetaInfo getMetaInfo()
  {
    return metainfo;
  }

  /**
   * How many pieces are still missing from this storage.
   */
  public int needed()
  {
    return needed;
  }

  /**
   * Whether or not this storage contains all pieces if the MetaInfo.
   */
  public boolean complete()
  {
    return needed == 0;
  }

  /**
   *  Has the storage changed since instantiation?
   *  @since 0.8.5
   */
  public boolean isChanged() {
      return changed;
  }

  /**
   *  Clear the storage changed variable
   *  @since 0.9.30
   */
  void clearChanged() {
      changed = false;
  }

  /**
   *  File checking in progress.
   *  @since 0.9.3
   */
  public boolean isChecking() {
      return _isChecking;
  }

  /**
   *  If checking is in progress, return completion 0.0 ... 1.0,
   *  else return 1.0.
   *  @since 0.9.23
   */
  public double getCheckingProgress() {
      if (_isChecking)
          return _checkProgress.get() / (double) pieces;
      else
          return 1.0d;
  }

  /**
   *  Disk allocation (ballooning) in progress.
   *  Always false on Windows.
   *  @since 0.9.3
   */
  public boolean isAllocating() {
      return _allocateCount.get() > 0;
  }

  /**
   *  Get index to pass to remaining(), getPriority(), setPriority()
   *
   *  @param file non-canonical path (non-directory)
   *  @return internal index of file; -1 if unknown file
   *  @since 0.9.15
   */
  public int indexOf(File file) {
      for (int i = 0; i < _torrentFiles.size(); i++) {
          File f = _torrentFiles.get(i).RAFfile;
          if (f.equals(file))
              return i;
      }
      return -1;
  }

  /**
   *  @param fileIndex as obtained from indexOf
   *  @return number of bytes remaining; -1 if unknown file
   *  @since 0.7.14
   */
/****
  public long remaining(int fileIndex) {
      if (fileIndex < 0 || fileIndex >= _torrentFiles.size())
          return -1;
      if (complete())
          return 0;
      long bytes = 0;
      for (int i = 0; i < _torrentFiles.size(); i++) {
          TorrentFile tf = _torrentFiles.get(i);
          if (i == fileIndex) {
              long start = bytes;
              long end = start + tf.length;
              int pc = (int) (bytes / piece_size);
              long rv = 0;
              if (!bitfield.get(pc))
                  rv = Math.min(piece_size - (start % piece_size), tf.length);
              for (int j = pc + 1; (((long)j) * piece_size) < end && j < pieces; j++) {
                  if (!bitfield.get(j)) {
                      if (((long)(j+1))*piece_size < end)
                          rv += piece_size;
                      else
                          rv += end - (((long)j) * piece_size);
                  }
              }
              return rv;
          }
          bytes += tf.length;
      }
      return -1;
  }
****/

  /**
   *  For efficiency, calculate remaining bytes for all files at once
   *
   *  @return number of bytes remaining for each file, use indexOf() to get index for a file
   *  @since 0.9.23
   */
  public long[] remaining() {
      long[] rv = new long[_torrentFiles.size()];
      if (complete())
          return rv;
      long bytes = 0;
      for (int i = 0; i < _torrentFiles.size(); i++) {
          TorrentFile tf = _torrentFiles.get(i);
          long start = bytes;
          long end = start + tf.length;
          int pc = (int) (bytes / piece_size);
          long rvi = 0;
          if (!bitfield.get(pc))
              rvi = Math.min(piece_size - (start % piece_size), tf.length);
          for (int j = pc + 1; (((long)j) * piece_size) < end && j < pieces; j++) {
              if (!bitfield.get(j)) {
                  if (((long)(j+1))*piece_size < end)
                      rvi += piece_size;
                  else
                      rvi += end - (((long)j) * piece_size);
              }
          }
          rv[i] = rvi;
          bytes += tf.length;
      }
      return rv;
  }

  /**
   *  @param fileIndex as obtained from indexOf
   *  @since 0.8.1
   */
  public int getPriority(int fileIndex) {
      if (complete() || metainfo.getFiles() == null)
          return 0;
      if (fileIndex < 0 || fileIndex >= _torrentFiles.size())
          return 0;
      return _torrentFiles.get(fileIndex).priority;
  }

  /**
   *  Must call Snark.updatePiecePriorities()
   *  (which calls getPiecePriorities()) after calling this.
   *  @param fileIndex as obtained from indexOf
   *  @param pri default 0; &lt;0 to disable
   *  @since 0.8.1
   */
  public void setPriority(int fileIndex, int pri) {
      if (complete() || metainfo.getFiles() == null)
          return;
      if (fileIndex < 0 || fileIndex >= _torrentFiles.size())
          return;
      _torrentFiles.get(fileIndex).priority = pri;
  }

  /**
   *  Get the file priorities array.
   *  @return null on error, if complete, or if only one file
   *  @since 0.8.1
   */
  public int[] getFilePriorities() {
      if (complete())
          return null;
      int sz = _torrentFiles.size();
      if (sz <= 1)
          return null;
      int[] priorities = new int[sz];
      for (int i = 0; i < sz; i++) {
          priorities[i] = _torrentFiles.get(i).priority;
      }
      return priorities;
  }

  /**
   *  Set the file priorities array.
   *  Only call this when stopped, but after check()
   *  @param p may be null
   *  @since 0.8.1
   */
  void setFilePriorities(int[] p) {
      if (p == null) {
          for (TorrentFile tf : _torrentFiles) {
              tf.priority = 0;
          }
      } else {
          int sz = _torrentFiles.size();
          if (p.length != sz)
              throw new IllegalArgumentException();
          for (int i = 0; i < sz; i++) {
              _torrentFiles.get(i).priority = p[i];
          }
      }
  }

  /**
   *  Call setPriority() for all changed files first,
   *  then call this.
   *  Set the piece priority to the highest priority
   *  of all files spanning the piece.
   *  Caller must pass array to the PeerCoordinator.
   *  @return null on error, if complete, or if only one file
   *  @since 0.8.1
   */
  public int[] getPiecePriorities() {
      if (complete() || metainfo.getFiles() == null)
          return null;
      int[] rv = new int[metainfo.getPieces()];
      int file = 0;
      long pcEnd = -1;
      long fileEnd = _torrentFiles.get(0).length - 1;
      for (int i = 0; i < rv.length; i++) {
          pcEnd += piece_size;
          int pri = _torrentFiles.get(file).priority;
          while (fileEnd <= pcEnd && file < _torrentFiles.size() - 1) {
              file++;
              TorrentFile tf = _torrentFiles.get(file);
              long oldFileEnd = fileEnd;
              fileEnd += tf.length;
              if (tf.priority > pri && oldFileEnd < pcEnd)
                  pri = tf.priority;
          }
          rv[i] = pri;
      }
      return rv;
  }

  /**
   *  Call setPriority() for all changed files first,
   *  then call this.
   *  The length of all the pieces that are not yet downloaded,
   *  and are set to skipped.
   *  This is not the same as the total of all skipped files,
   *  since pieces may span multiple files.
   *
   *  @return 0 on error, if complete, or if only one file
   *  @since 0.9.24
   */
  public long getSkippedLength() {
      int[] pri = getPiecePriorities();
      if (pri == null)
          return 0;
      long rv = 0;
      final int end = pri.length - 1;
      for (int i = 0; i <= end; i++) {
          if (pri[i] <= -9 && !bitfield.get(i)) {
              rv += (i != end) ? piece_size : metainfo.getPieceLength(i);
          }
      }
      return rv;
  }

  /**
   * The BitField that tells which pieces this storage contains.
   * Do not change this since this is the current state of the storage.
   */
  public BitField getBitField()
  {
    return bitfield;
  }

  /**
   *  The base file or directory name of the data,
   *  as specified in the .torrent file, but filtered to remove
   *  illegal characters. This is where the data actually is,
   *  relative to the snark base dir.
   *
   *  @since 0.7.14
   */
  public String getBaseName() {
      return optFilterName(metainfo.getName());
  }

  /** @since 0.9.15 */
  public boolean getPreserveFileNames() {
      return _preserveFileNames;
  }

  /**
   * Creates (and/or checks) all files from the metainfo file list.
   * Only call this once, and only after the constructor with the metainfo.
   * Use recheck() to check again later.
   *
   * @throws IllegalStateException if called more than once
   */
  public void check() throws IOException
  {
    check(0, null);
  }

  /**
   * Creates (and/or checks) all files from the metainfo file list.
   * Use a saved bitfield and timestamp from a config file.
   * Only call this once, and only after the constructor with the metainfo.
   * Use recheck() to check again later.
   *
   * @throws IllegalStateException if called more than once
   */
  public void check(long savedTime, BitField savedBitField) throws IOException
  {
    boolean areFilesPublic = _util.getFilesPublic();
    boolean useSavedBitField = savedTime > 0 && savedBitField != null;

    if (!_torrentFiles.isEmpty())
        throw new IllegalStateException();
    List<List<String>> files = metainfo.getFiles();
    if (files == null)
      {
        // Create base as file.
        if (_log.shouldLog(Log.INFO))
            _log.info("Creating/Checking file: " + _base);
        // createNewFile() can throw a "Permission denied" IOE even if the file exists???
        // so do it second
        if (!_base.exists() && !_base.createNewFile())
          throw new IOException("Could not create file " + _base);

        _torrentFiles.add(new TorrentFile(_base, _base, metainfo.getTotalLength()));
        if (useSavedBitField) {
            long lm = _base.lastModified();
            if (lm <= 0 || lm > savedTime)
                useSavedBitField = false;
            else if (_base.length() != metainfo.getTotalLength())
                useSavedBitField = false;
        }
      }
    else
      {
        // Create base as dir.
        if (_log.shouldLog(Log.INFO))
            _log.info("Creating/Checking directory: " + _base);
        if (!_base.mkdir() && !_base.isDirectory())
          throw new IOException("Could not create directory " + _base);

        List<Long> ls = metainfo.getLengths();
        int size = files.size();
        long total = 0;
        for (int i = 0; i < size; i++)
          {
            List<String> path = files.get(i);
            File f = createFileFromNames(_base, path, areFilesPublic);
            // dup file name check after filtering
            for (int j = 0; j < i; j++) {
                if (f.equals(_torrentFiles.get(j).RAFfile)) {
                    // Rename and start the check over again
                    // Copy path since metainfo list is unmodifiable
                    path = new ArrayList<String>(path);
                    int last = path.size() - 1;
                    String lastPath = path.get(last);
                    int dot = lastPath.lastIndexOf('.');
                    // foo.mp3 -> foo_.mp3; foo -> _foo
                    if (dot >= 0)
                        lastPath = lastPath.substring(0, dot) + '_' + lastPath.substring(dot);
                    else
                        lastPath = '_' + lastPath;
                    path.set(last, lastPath);
                    f = createFileFromNames(_base, path, areFilesPublic);
                    j = 0;
                }
            }
            long len = ls.get(i).longValue();
            _torrentFiles.add(new TorrentFile(_base, f, len));
            total += len;
            if (useSavedBitField) {
                long lm = f.lastModified();
                if (lm <= 0 || lm > savedTime)
                    useSavedBitField = false;
                else if (f.length() != len)
                    useSavedBitField = false;
            }
          }

        // Sanity check for metainfo file.
        long metalength = metainfo.getTotalLength();
        if (total != metalength)
          throw new IOException("File lengths do not add up "
                                + total + " != " + metalength);
      }
    if (useSavedBitField) {
      bitfield = savedBitField;
      needed = metainfo.getPieces() - bitfield.count();
      _probablyComplete = complete();
      if (_log.shouldLog(Log.INFO))
          _log.info("Found saved state and files unchanged, skipping check");
    } else {
      // the following sets the needed variable
      changed = true;
      if (_log.shouldLog(Log.INFO))
          _log.info("Forcing check");
      checkCreateFiles(false);
    }
    if (complete()) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Torrent is complete");
    } else {
        // fixme saved priorities
        if (_log.shouldLog(Log.INFO))
            _log.info("Still need " + needed + " out of " + metainfo.getPieces() + " pieces");
    }
  }

  /**
   * Doesn't really reopen the file descriptors for a restart.
   * Just does an existence check but no length check or data reverification
   *
   * @throws IOException on fail
   */
  public void reopen() throws IOException
  {
      if (_torrentFiles.isEmpty())
          throw new IOException("Storage not checked yet");
      for (TorrentFile tf : _torrentFiles) {
          if (!tf.RAFfile.exists())
              throw new IOException("File does not exist: " + tf);
      }
  }

  private static final char[] ILLEGAL = new char[] {
        '<', '>', ':', '"', '/', '\\', '|', '?', '*',
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
        0x7f,
        0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
        0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0x8f,
        0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97,
        0x98, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f,
        // unicode newlines
        0x2028, 0x2029
     };

  /**
   *  Filter the name, but only if configured to do so.
   *  We will do so on torrents received from others, but not
   *  on those we created ourselves, so we do not lose track of files.
   *
   *  @since 0.9.15
   */
  private String optFilterName(String name) {
      if (_preserveFileNames)
          return name;
      return filterName(name);
  }

  /**
   * Removes 'suspicious' characters from the given file name.
   * http://msdn.microsoft.com/en-us/library/aa365247%28VS.85%29.aspx
   * Then replace chars not supported in the charset.
   *
   * This is called frequently and it can be pretty slow so cache the result.
   *
   * TODO: If multiple files in the same torrent map to the same filter name,
   * the whole torrent will blow up. Check at torrent creation?
   */
  public static String filterName(String name)
  {
    String rv = _filterNameCache.get(name);
    if (rv != null)
        return rv;
    if (name.equals(".") || name.equals(" ")) {
        rv = "_";
    } else {
        rv = name;
        if (rv.startsWith("."))
            rv = '_' + rv.substring(1);
        if (rv.endsWith(".") || rv.endsWith(" "))
            rv = rv.substring(0, rv.length() - 1) + '_';
        for (int i = 0; i < ILLEGAL.length; i++) {
            if (rv.indexOf(ILLEGAL[i]) >= 0)
                rv = rv.replace(ILLEGAL[i], '_');
        }
        // Replace characters not supported in the charset
        if (!Charset.defaultCharset().name().equals("UTF-8")) {
            try {
                CharsetEncoder enc = Charset.defaultCharset().newEncoder();
                if (!enc.canEncode(rv)) {
                    String repl = rv;
                    for (int i = 0; i < rv.length(); i++) {
                        char c = rv.charAt(i);
                        if (!enc.canEncode(c))
                            repl = repl.replace(c, '_');
                    }
                    rv = repl;
                }
            } catch (RuntimeException ex) {
                ex.printStackTrace();
            }
        }
    }
    _filterNameCache.put(name, rv);
    return rv;
  }

  /**
   *  Note that filtering each path element individually may lead to
   *  things going in the wrong place if there are duplicates
   *  in intermediate path elements after filtering.
   *
   *  @param names path elements
   */
  private File createFileFromNames(File base, List<String> names, boolean areFilesPublic) throws IOException
  {
    File f = null;
    Iterator<String> it = names.iterator();
    while (it.hasNext())
      {
        String name = optFilterName(it.next());
        if (it.hasNext())
          {
            // Another dir in the hierarchy.
            if (areFilesPublic)
                f = new File(base, name);
            else
                f = new SecureFile(base, name);
            if (!f.mkdir() && !f.isDirectory())
              throw new IOException("Could not create directory " + f);
            base = f;
          }
        else
          {
            // The final element (file) in the hierarchy.
            if (areFilesPublic)
                f = new File(base, name);
            else
                f = new SecureFile(base, name);
            // createNewFile() can throw a "Permission denied" IOE even if the file exists???
            // so do it second
            if (!f.exists() && !f.createNewFile())
              throw new IOException("Could not create file " + f);
          }
      }
    return f;
  }

  /**
   *  The base file or directory.
   *  @return the File
   *  @since 0.9.15
   */
  public File getBase() {
      return _base;
  }

  /**
   *  Does not include directories. Unsorted.
   *  @return a new List
   *  @since 0.9.15
   */
  public List<File> getFiles() {
      List<File> rv = new ArrayList<File>(_torrentFiles.size());
      for (TorrentFile tf : _torrentFiles) {
          rv.add(tf.RAFfile);
      }
      return rv;
  }

  /**
   *  Does not include directories.
   *  @since 0.9.23
   */
  public int getFileCount() {
      return _torrentFiles.size();
  }

  /**
   *  Includes the base for a multi-file torrent.
   *  Sorted bottom-up for easy deletion.
   *  Slow. Use for deletion only.
   *  @return a new Set or null for a single-file torrent
   *  @since 0.9.15
   */
  public SortedSet<File> getDirectories() {
      if (!_base.isDirectory())
          return null;
      SortedSet<File> rv = new TreeSet<File>(Collections.reverseOrder());
      rv.add(_base);
      for (TorrentFile tf : _torrentFiles) {
          File f = tf.RAFfile;
          do {
              f = f.getParentFile();
          } while (f != null && rv.add(f));
      }
      return rv;
  }

  /**
   *  Blocking. Holds lock.
   *  Recommend running only when stopped.
   *  Caller should thread.
   *  Calls listener.setWantedPieces() on completion if anything changed.
   *
   *  @return true if anything changed, false otherwise
   *  @since 0.9.23
   */
  public boolean recheck() throws IOException {
      boolean changed = checkCreateFiles(true);
      if (listener != null && changed)
          listener.setWantedPieces(this);
      return changed;
  }

  /**
   * This is called at the beginning, and at presumed completion,
   * so we have to be careful about locking.
   *
   * TODO thread the checking so we can return and display
   * something on the UI
   *
   * @param recheck if true, this is a check after we downloaded the
   *        last piece, and we don't modify the global bitfield unless
   *        the check fails.
   * @return true if changed (only valid if recheck == true)
   */
  private boolean checkCreateFiles(boolean recheck) throws IOException {
      synchronized(this) {
          _isChecking = true;
          try {
              return locked_checkCreateFiles(recheck);
          } finally {
              _isChecking = false;
          }
      }
  }

  /**
   *  @return true if changed (only valid if recheck == true)
   */
  private boolean locked_checkCreateFiles(boolean recheck) throws IOException
  {
    _checkProgress.set(0);
    // Whether we are resuming or not,
    // if any of the files already exists we assume we are resuming.
    boolean resume = false;

    _probablyComplete = true;
    // use local variables during the check
    int need = metainfo.getPieces();
    BitField bfield;
    if (recheck) {
        bfield = new BitField(need);
    } else {
        bfield = bitfield;
    }

    // Make sure all files are available and of correct length
    // The files should all exist as they have been created with zero length by createFilesFromNames()
    long lengthProgress = 0;
    for (TorrentFile tf : _torrentFiles)
      {
        long length = tf.RAFfile.length();
        lengthProgress += tf.length;
        if(tf.RAFfile.exists() && length == tf.length)
          {
            if (listener != null)
              listener.storageAllocated(this, length);
            _checkProgress.set(0);
            resume = true; // XXX Could dynamicly check
          }
        else if (length == 0) {
          changed = true;
          synchronized(tf) {
              allocateFile(tf);
              // close as we go so we don't run out of file descriptors
              try {
                  tf.closeRAF();
              } catch (IOException ioe) {}
          }
          if (!resume)
              _checkProgress.set((int) (pieces * lengthProgress / total_length));
        } else {
          String msg = "File '" + tf.name + "' exists, but has wrong length (expected " +
                       tf.length + " but found " + length + ") - repairing corruption";
          if (listener != null)
              listener.addMessage(msg);
          _log.error(msg);
          changed = true;
          resume = true;
          _checkProgress.set(0);
          _probablyComplete = false; // to force RW
          synchronized(tf) {
              RandomAccessFile raf = tf.checkRAF();
              raf.setLength(tf.length);
              try {
                  tf.closeRAF();
              } catch (IOException ioe) {}
          }
        }
      }

    // Check which pieces match and which don't
    if (resume)
      {
        byte[] piece = new byte[piece_size];
        int file = 0;
        long fileEnd = _torrentFiles.get(0).length;
        long pieceEnd = 0;
        for (int i = 0; i < pieces; i++)
          {
            _checkProgress.set(i);
            int length = getUncheckedPiece(i, piece);
            boolean correctHash = metainfo.checkPiece(i, piece, 0, length);
            // close as we go so we don't run out of file descriptors
            pieceEnd += length;
            while (fileEnd <= pieceEnd) {
                TorrentFile tf = _torrentFiles.get(file);
                try {
                    tf.closeRAF();
                } catch (IOException ioe) {}
                if (++file >= _torrentFiles.size())
                    break;
                fileEnd += _torrentFiles.get(file).length;
            }
            if (correctHash)
              {
                bfield.set(i);
                need--;
              }

            if (listener != null)
              listener.storageChecked(this, i, correctHash);
          }
      }

    _checkProgress.set(pieces);
    _probablyComplete = complete();
    // close all the files so we don't end up with a zillion open ones;
    // we will reopen as needed
    // Now closed above to avoid running out of file descriptors
    //for (int i = 0; i < rafs.length; i++) {
    //  synchronized(RAFlock[i]) {
    //    try {
    //      closeRAF(i);
    //    } catch (IOException ioe) {}
    //  }
    //}

    // do this here so we don't confuse the user during checking
    needed = need;
    boolean rv = false;
    if (recheck) {
        // FIXME bogus synch
        synchronized(bitfield) {
            rv = !bfield.equals(bitfield);
            bitfield = bfield;
        }
    }

    if (listener != null) {
      listener.storageAllChecked(this);
      if (needed <= 0)
        listener.storageCompleted(this);
    }
    return rv;
  }

  /**
   *  This creates a (presumably) sparse file so that reads won't fail with IOE.
   *  Sets isSparse[nr] = true. balloonFile(nr) should be called later to
   *  defrag the file.
   *
   *  This calls OpenRAF(); caller must synchronize and call closeRAF().
   */
  private void allocateFile(TorrentFile tf) throws IOException
  {
    // caller synchronized
    tf.allocateFile();
    if (listener != null) {
        listener.storageCreateFile(this, tf.name, tf.length);
        listener.storageAllocated(this, tf.length);
    }
    // caller will close rafs[nr]
  }


  /**
   * Closes the Storage and makes sure that all RandomAccessFiles are
   * closed. The Storage is unusable after this.
   */
  public void close() throws IOException
  {
    for (TorrentFile tf : _torrentFiles)
      {
        try {
            tf.closeRAF();
        } catch (IOException ioe) {
            _log.error("Error closing " + tf, ioe);
            // gobble gobble
        }
      }
    changed = false;
  }

  /**
   * Returns a byte array containing a portion of the requested piece or null if
   * the storage doesn't contain the piece yet.
   */
  public ByteArray getPiece(int piece, int off, int len) throws IOException
  {
    if (!bitfield.get(piece))
      return null;

    //Catch a common place for OOMs esp. on 1MB pieces
    ByteArray rv;
    byte[] bs;
    try {
        // Will be restored to cache in Message.sendMessage()
        if (len == BUFSIZE)
            rv = _cache.acquire();
        else
            rv = new ByteArray(new byte[len]);
    } catch (OutOfMemoryError oom) {
      if (_log.shouldLog(Log.WARN))
          _log.warn("Out of memory, can't honor request for piece " + piece, oom);
      return null;
    }
    bs = rv.getData();
    getUncheckedPiece(piece, bs, off, len);
    return rv;
  }

  /**
   * Put the piece in the Storage if it is correct.
   * Warning - takes a LONG time if complete as it does the recheck here.
   * TODO thread the recheck?
   *
   * @return true if the piece was correct (sha metainfo hash
   * matches), otherwise false.
   * @throws IOException when some storage related error occurs.
   */
  public boolean putPiece(PartialPiece pp) throws IOException
  {
      int piece = pp.getPiece();
      try {
          synchronized(bitfield) {
              if (bitfield.get(piece))
                  return true; // No need to store twice.
          }

          // TODO alternative - check hash on the fly as we write to the file,
          // to save another I/O pass
          boolean correctHash = metainfo.checkPiece(pp);
          if (!correctHash) {
              if (listener != null)
                  listener.storageChecked(this, piece, false);
              return false;
          }

          // Early typecast, avoid possibly overflowing a temp integer
          long start = (long) piece * (long) piece_size;
          int i = 0;
          long raflen = _torrentFiles.get(i).length;
          while (start > raflen) {
              i++;
              start -= raflen;
              raflen = _torrentFiles.get(i).length;
          }
    
          int written = 0;
          int length = metainfo.getPieceLength(piece);
          while (written < length) {
              int need = length - written;
              int len = (start + need < raflen) ? need : (int)(raflen - start);
              TorrentFile tf = _torrentFiles.get(i);
              synchronized(tf) {
                  try {
                      RandomAccessFile raf = tf.checkRAF();
                      if (tf.isSparse) {
                          // If the file is a newly created sparse file,
                          // AND we aren't skipping it, balloon it with all
                          // zeros to un-sparse it by allocating the space.
                          // Obviously this could take a while.
                          // Once we have written to it, it isn't empty/sparse any more.
                          if (tf.priority >= 0) {
                              if (_log.shouldLog(Log.INFO))
                                  _log.info("Ballooning " + tf);
                              tf.balloonFile();
                          } else {
                              tf.isSparse = false;
                          }
                      }
                      raf.seek(start);
                      //rafs[i].write(bs, off + written, len);
                      pp.write(raf, written, len);
                  } catch (IOException ioe) {
                      // get the file name in the logs
                      IOException ioe2 = new IOException("Error writing " + tf.RAFfile.getAbsolutePath());
                      ioe2.initCause(ioe);
                      throw ioe2;
                  }
              }
              written += len;
              if (need - len > 0) {
                  i++;
                  raflen = _torrentFiles.get(i).length;
                  start = 0;
              }
          }
      } finally {
          pp.release();
      }

    changed = true;

    // do this after the write, so we know it succeeded, and we don't set the
    // needed count to zero, which would cause checkRAF() to open the file readonly.
    boolean complete = false;
    synchronized(bitfield)
      {
        if (!bitfield.get(piece))
          {
            bitfield.set(piece);
            needed--;
            complete = needed == 0;
          }
      }
    // tell listener after counts are updated
    if (listener != null)
        listener.storageChecked(this, piece, true);

    if (complete) {
      // do we also need to close all of the files and reopen
      // them readonly?

      // Do a complete check to be sure.
      // Temporarily resets the 'needed' variable and 'bitfield', then call
      // checkCreateFiles() which will set 'needed' and 'bitfield'
      // and also call listener.storageCompleted() if the double-check
      // was successful.
      checkCreateFiles(true);
      if (needed > 0) {
        if (listener != null)
            listener.setWantedPieces(this);
        if (_log.shouldLog(Log.WARN))
            _log.warn("WARNING: Not really done, missing " + needed
                    + " pieces");
      }
    }

    return true;
 }

  /**
   *  This is a dup of MetaInfo.getPieceLength() but we need it
   *  before the MetaInfo is created in our second constructor.
   *  @since 0.8.5
   */
  private int getPieceLength(int piece) {
    if (piece >= 0 && piece < pieces -1)
      return piece_size;
    else if (piece == pieces -1)
      return (int)(total_length - ((long)piece * piece_size));
    else
      throw new IndexOutOfBoundsException("no piece: " + piece);
  }

  private int getUncheckedPiece(int piece, byte[] bs)
    throws IOException
  {
      return getUncheckedPiece(piece, bs, 0, getPieceLength(piece));
  }

  private int getUncheckedPiece(int piece, byte[] bs, int off, int length)
    throws IOException
  {
    // XXX - copy/paste code from putPiece().

    // Early typecast, avoid possibly overflowing a temp integer
    long start = ((long) piece * (long) piece_size) + off;

    int i = 0;
    long raflen = _torrentFiles.get(i).length;
    while (start > raflen)
      {
        i++;
        start -= raflen;
        raflen = _torrentFiles.get(i).length;
      }

    int read = 0;
    while (read < length)
      {
        int need = length - read;
        int len = (start + need < raflen) ? need : (int)(raflen - start);
        TorrentFile tf = _torrentFiles.get(i);
        synchronized(tf) {
            try {
                RandomAccessFile raf = tf.checkRAF();
                raf.seek(start);
                raf.readFully(bs, read, len);
            } catch (IOException ioe) {
                // get the file name in the logs
                IOException ioe2 = new IOException("Error reading " + tf.RAFfile.getAbsolutePath());
                ioe2.initCause(ioe);
                throw ioe2;
            }
        }
        read += len;
        if (need - len > 0)
          {
            i++;
            raflen = _torrentFiles.get(i).length;
            start = 0;
          }
      }

    return length;
  }

  private static final long RAF_CLOSE_DELAY = 4*60*1000;

  /**
   * Close unused RAFs - call periodically
   */
  public void cleanRAFs() {
    long cutoff = System.currentTimeMillis() - RAF_CLOSE_DELAY;
    for (TorrentFile tf : _torrentFiles) {
         tf.closeRAF(cutoff);
    }
  }

  /**
   *  A single file in a torrent.
   *  @since 0.9.9
   */
  private class TorrentFile implements Comparable<TorrentFile> {
      public final long length;
      public final String name;
      public final File RAFfile;
      /**
       * when was RAF last accessed, or 0 if closed
       * locking: this
       */
      private long RAFtime;
      /**
       * null when closed
       * locking: this
       */
      private RandomAccessFile raf;
      /**
       * is the file empty and sparse?
       * locking: this
       */
      public boolean isSparse;
      /** priority by file; default 0 */
      public volatile int priority;

      /**
       * For new metainfo from files;
       * use base == f for single-file torrent
       */
      public TorrentFile(File base, File f) {
          this(base, f, f.length());
      }

      /**
       * For existing metainfo with specified file length;
       * use base == f for single-file torrent
       */
      public TorrentFile(File base, File f, long len) {
          String n = f.getPath();
          if (base.isDirectory() && n.startsWith(base.getPath()))
              n = n.substring(base.getPath().length() + 1);
          name = n;
          length = len;
          RAFfile = f;
      }

      /*
       * For each of the following,
       * caller must synchronize on RAFlock[i]
       * ... except at the beginning if you're careful
       */

      /**
       * This must be called before using the RAF to ensure it is open
       * locking: this
       */
      public synchronized RandomAccessFile checkRAF() throws IOException {
          if (raf != null)
            RAFtime = System.currentTimeMillis();
          else
            openRAF();
          return raf;
      }

      /**
       * locking: this
       */
      private synchronized void openRAF() throws IOException {
          openRAF(_probablyComplete);
      }

      /**
       * locking: this
       */
      private synchronized void openRAF(boolean readonly) throws IOException {
          raf = new RandomAccessFile(RAFfile, (readonly || !RAFfile.canWrite()) ? "r" : "rw");
          RAFtime = System.currentTimeMillis();
      }

      /**
       * Close if last used time older than cutoff.
       * locking: this
       */
      public synchronized void closeRAF(long cutoff) {
          if (RAFtime > 0 && RAFtime < cutoff) {
              try {
                  closeRAF();
              } catch (IOException ioe) {}
          }
      }

      /**
       * Can be called even if not open
       * locking: this
       */
      public synchronized void closeRAF() throws IOException {
          RAFtime = 0;
          if (raf == null)
              return;
          raf.close();
          raf = null;
      }


      /**
       *  This creates a (presumably) sparse file so that reads won't fail with IOE.
       *  Sets isSparse[nr] = true. balloonFile(nr) should be called later to
       *  defrag the file.
       *
       *  This calls openRAF(); caller must synchronize and call closeRAF().
       */
      public synchronized void allocateFile() throws IOException {
          // caller synchronized
          openRAF(false);  // RW
          raf.setLength(length);
          // don't bother ballooning later on Windows since there is no sparse file support
          // until JDK7 using the JSR-203 interface.
          // RAF seeks/writes do not create sparse files.
          // Windows will zero-fill up to the point of the write, which
          // will make the file fairly unfragmented, on average, at least until
          // near the end where it will get exponentially more fragmented.
          // Also don't ballon on ARM, as a proxy for solid state disk, where fragmentation doesn't matter too much.
          // Actual detection of SSD is almost impossible.
          if (!_isWindows && !_isARM)
              isSparse = true;
      }

      /**
       *  This "balloons" the file with zeros to eliminate disk fragmentation.,
       *  Overwrites the entire file with zeros. Sets isSparse[nr] = false.
       *
       *  Caller must synchronize and call checkRAF() or openRAF().
       *  @since 0.9.1
       */
      public synchronized void balloonFile() throws IOException
      {
          long remaining = length;
          final int ZEROBLOCKSIZE = (int) Math.min(remaining, 32*1024);
          byte[] zeros = new byte[ZEROBLOCKSIZE];
          raf.seek(0);
          // don't bother setting flag for small files
          if (remaining > 20*1024*1024)
              _allocateCount.incrementAndGet();
          try {
              while (remaining > 0) {
                  int size = (int) Math.min(remaining, ZEROBLOCKSIZE);
                  raf.write(zeros, 0, size);
                  remaining -= size;
              }
          } finally {
              remaining = length;
              if (remaining > 20*1024*1024)
                  _allocateCount.decrementAndGet();
          }
          isSparse = false;
      }

      public int compareTo(TorrentFile tf) {
          return name.compareTo(tf.name);
      }

      @Override
      public int hashCode() { return RAFfile.getAbsolutePath().hashCode(); }

      @Override
      public boolean equals(Object o) {
          return (o instanceof TorrentFile) &&
                 RAFfile.getAbsolutePath().equals(((TorrentFile)o).RAFfile.getAbsolutePath());
      }

      @Override
      public String toString() { return name; }
  }

  /**
   *  Create a metainfo.
   *  Used in the installer build process; do not comment out.
   *  @since 0.9.4
   */
  public static void main(String[] args) {
      boolean error = false;
      String created_by = null;
      String announce = null;
      Getopt g = new Getopt("Storage", args, "a:c:");
      try {
          int c;
          while ((c = g.getopt()) != -1) {
            switch (c) {
              case 'a':
                  announce = g.getOptarg();
                  break;

              case 'c':
                  created_by = g.getOptarg();
                  break;

              case '?':
              case ':':
              default:
                  error = true;
                  break;
            }  // switch
          } // while
      } catch (RuntimeException e) {
          e.printStackTrace();
          error = true;
      }
      if (error || args.length - g.getOptind() != 1) {
          System.err.println("Usage: Storage [-a announceURL] [-c created-by] file-or-dir");
          System.exit(1);
      }
      File base = new File(args[g.getOptind()]);
      I2PAppContext ctx = I2PAppContext.getGlobalContext();
      I2PSnarkUtil util = new I2PSnarkUtil(ctx);
      File file = null;
      FileOutputStream out = null;
      try {
          Storage storage = new Storage(util, base, announce, null, created_by, false, null);
          MetaInfo meta = storage.getMetaInfo();
          file = new File(storage.getBaseName() + ".torrent");
          out = new FileOutputStream(file);
          out.write(meta.getTorrentData());
          String hex = DataHelper.toString(meta.getInfoHash());
          System.out.println("Created:     " + file);
          System.out.println("InfoHash:    " + hex);
          String basename = base.getName().replace(" ", "%20");
          String magnet = MagnetURI.MAGNET_FULL + hex + "&dn=" + basename;
          if (announce != null)
              magnet += "&tr=" + announce;
          System.out.println("Magnet:      " + magnet);
      } catch (IOException ioe) {
          if (file != null)
              file.delete();
          ioe.printStackTrace();
          System.exit(1);
      } finally {
          try { if (out != null) out.close(); } catch (IOException ioe) {}
      }
  }
}
