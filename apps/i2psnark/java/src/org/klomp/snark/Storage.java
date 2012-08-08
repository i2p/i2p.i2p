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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.crypto.SHA1;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;

/**
 * Maintains pieces on disk. Can be used to store and retrieve pieces.
 */
public class Storage
{
  private MetaInfo metainfo;
  private long[] lengths;
  private RandomAccessFile[] rafs;
  private String[] names;
  private Object[] RAFlock;  // lock on RAF access
  private long[] RAFtime;    // when was RAF last accessed, or 0 if closed
  private File[] RAFfile;    // File to make it easier to reopen
  /** priorities by file; default 0; may be null. @since 0.8.1 */
  private int[] priorities;
  /** is the file empty and sparse? */
  private boolean[] isSparse;

  private final StorageListener listener;
  private final I2PSnarkUtil _util;
  private final Log _log;

  private /* FIXME final FIXME */ BitField bitfield; // BitField to represent the pieces
  private int needed; // Number of pieces needed
  private boolean _probablyComplete;  // use this to decide whether to open files RO

  private final int piece_size;
  private final int pieces;
  private final long total_length;
  private boolean changed;

  /** The default piece size. */
  private static final int MIN_PIECE_SIZE = 256*1024;
  /** note that we start reducing max number of peer connections above 1MB */
  public static final int MAX_PIECE_SIZE = 2*1024*1024;
  /** The maximum number of pieces in a torrent. */
  public static final int MAX_PIECES = 10*1024;
  public static final long MAX_TOTAL_SIZE = MAX_PIECE_SIZE * (long) MAX_PIECES;

  private static final Map<String, String> _filterNameCache = new ConcurrentHashMap();

  private static final boolean _isWindows = System.getProperty("os.name").startsWith("Win");

  /**
   * Creates a new storage based on the supplied MetaInfo.  This will
   * try to create and/or check all needed files in the MetaInfo.
   *
   * @exception IOException when creating and/or checking files fails.
   */
  public Storage(I2PSnarkUtil util, MetaInfo metainfo, StorageListener listener)
    throws IOException
  {
    _util = util;
    _log = util.getContext().logManager().getLog(Storage.class);
    this.metainfo = metainfo;
    this.listener = listener;
    needed = metainfo.getPieces();
    _probablyComplete = false;
    bitfield = new BitField(needed);
    piece_size = metainfo.getPieceLength(0);
    pieces = needed;
    total_length = metainfo.getTotalLength();
  }

  /**
   * Creates a storage from the existing file or directory together
   * with an appropriate MetaInfo file as can be announced on the
   * given announce String location.
   *
   * @param announce may be null
   * @param listener may be null
   */
  public Storage(I2PSnarkUtil util, File baseFile, String announce,
                 boolean privateTorrent, StorageListener listener)
    throws IOException
  {
    _util = util;
    _log = util.getContext().logManager().getLog(Storage.class);
    this.listener = listener;
    // Create names, rafs and lengths arrays.
    getFiles(baseFile);
    
    long total = 0;
    ArrayList<Long> lengthsList = new ArrayList();
    for (int i = 0; i < lengths.length; i++)
      {
        long length = lengths[i];
        total += length;
        lengthsList.add(Long.valueOf(length));
      }

    if (total <= 0)
        throw new IOException("Torrent contains no data");

    int pc_size = MIN_PIECE_SIZE;
    int pcs = (int) ((total - 1)/pc_size) + 1;
    while (pcs > MAX_PIECES && pc_size < MAX_PIECE_SIZE)
      {
        pc_size *= 2;
        pcs = (int) ((total - 1)/pc_size) +1;
      }
    piece_size = pc_size;
    pieces = pcs;
    total_length = total;

    bitfield = new BitField(pieces);
    needed = 0;

    List<List<String>> files = new ArrayList();
    for (int i = 0; i < names.length; i++)
      {
        List<String> file = new ArrayList();
        StringTokenizer st = new StringTokenizer(names[i], File.separator);
        while (st.hasMoreTokens())
          {
            String part = st.nextToken();
            file.add(part);
          }
        files.add(file);
      }

    if (files.size() == 1) // FIXME: ...and if base file not a directory or should this be the only check?
                           // this makes a bad metainfo if the directory has only one file in it
      {
        files = null;
        lengthsList = null;
      }

    byte[] piece_hashes = fast_digestCreate();
    metainfo = new MetaInfo(announce, baseFile.getName(), null, files,
                            lengthsList, piece_size, piece_hashes, total, privateTorrent);

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

  private void getFiles(File base) throws IOException
  {
    ArrayList files = new ArrayList();
    addFiles(files, base);

    int size = files.size();
    names = new String[size];
    lengths = new long[size];
    rafs = new RandomAccessFile[size];
    RAFlock = new Object[size];
    RAFtime = new long[size];
    RAFfile = new File[size];
    priorities = new int[size];
    isSparse = new boolean[size];

    int i = 0;
    Iterator it = files.iterator();
    while (it.hasNext())
      {
        File f = (File)it.next();
        names[i] = f.getPath();
	if (base.isDirectory() && names[i].startsWith(base.getPath()))
          names[i] = names[i].substring(base.getPath().length() + 1);
        lengths[i] = f.length();
        RAFlock[i] = new Object();
        RAFfile[i] = f;
        i++;
      }
  }

  private void addFiles(List l, File f)
  {
    if (!f.isDirectory())
      l.add(f);
    else
      {
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
   *  @param file canonical path (non-directory)
   *  @return number of bytes remaining; -1 if unknown file
   *  @since 0.7.14
   */
  public long remaining(String file) {
      long bytes = 0;
      for (int i = 0; i < rafs.length; i++) {
          File f = RAFfile[i];
          // use canonical in case snark dir or sub dirs are symlinked
          String canonical = null;
          if (f != null) {
              try {
                  canonical = f.getCanonicalPath();
              } catch (IOException ioe) {
                  f = null;
              }
          }
          if (f != null && canonical.equals(file)) {
              if (complete())
                  return 0;
              int psz = piece_size;
              long start = bytes;
              long end = start + lengths[i];
              int pc = (int) (bytes / psz);
              long rv = 0;
              if (!bitfield.get(pc))
                  rv = Math.min(psz - (start % psz), lengths[i]);
              for (int j = pc + 1; (((long)j) * psz) < end && j < pieces; j++) {
                  if (!bitfield.get(j)) {
                      if (((long)(j+1))*psz < end)
                          rv += psz;
                      else
                          rv += end - (((long)j) * psz);
                  }
              }
              return rv;
          }
          bytes += lengths[i];
      }
      return -1;
  }

  /**
   *  @param file canonical path (non-directory)
   *  @since 0.8.1
   */
  public int getPriority(String file) {
      if (complete() || metainfo.getFiles() == null || priorities == null)
          return 0;
      for (int i = 0; i < rafs.length; i++) {
          File f = RAFfile[i];
          // use canonical in case snark dir or sub dirs are symlinked
          if (f != null) {
              try {
                  String canonical = f.getCanonicalPath();
                  if (canonical.equals(file))
                      return priorities[i];
              } catch (IOException ioe) {}
          }
      }
      return 0;
  }

  /**
   *  Must call Snark.updatePiecePriorities()
   *  (which calls getPiecePriorities()) after calling this.
   *  @param file canonical path (non-directory)
   *  @param pri default 0; <0 to disable
   *  @since 0.8.1
   */
  public void setPriority(String file, int pri) {
      if (complete() || metainfo.getFiles() == null || priorities == null)
          return;
      for (int i = 0; i < rafs.length; i++) {
          File f = RAFfile[i];
          // use canonical in case snark dir or sub dirs are symlinked
          if (f != null) {
              try {
                  String canonical = f.getCanonicalPath();
                  if (canonical.equals(file)) {
                      priorities[i] = pri;
                      return;
                  }
              } catch (IOException ioe) {}
          }
      }
  }

  /**
   *  Get the file priorities array.
   *  @return null on error, if complete, or if only one file
   *  @since 0.8.1
   */
  public int[] getFilePriorities() {
      return priorities;
  }

  /**
   *  Set the file priorities array.
   *  Only call this when stopped, but after check()
   *  @param p may be null
   *  @since 0.8.1
   */
  void setFilePriorities(int[] p) {
      priorities = p;
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
      if (complete() || metainfo.getFiles() == null || priorities == null)
          return null;
      int[] rv = new int[metainfo.getPieces()];
      int file = 0;
      long pcEnd = -1;
      long fileEnd = lengths[0] - 1;
      int psz = piece_size;
      for (int i = 0; i < rv.length; i++) {
          pcEnd += psz;
          int pri = priorities[file];
          while (fileEnd <= pcEnd && file < lengths.length - 1) {
              file++;
              long oldFileEnd = fileEnd;
              fileEnd += lengths[file];
              if (priorities[file] > pri && oldFileEnd < pcEnd)
                  pri = priorities[file];
          }
          rv[i] = pri;
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
      return filterName(metainfo.getName());
  }

  /**
   * Creates (and/or checks) all files from the metainfo file list.
   */
  public void check(String rootDir) throws IOException
  {
    check(rootDir, 0, null);
  }

  /** use a saved bitfield and timestamp from a config file */
  public void check(String rootDir, long savedTime, BitField savedBitField) throws IOException
  {
    File base;
    boolean areFilesPublic = _util.getFilesPublic();
    if (areFilesPublic)
        base = new File(rootDir, filterName(metainfo.getName()));
    else
        base = new SecureFile(rootDir, filterName(metainfo.getName()));
    boolean useSavedBitField = savedTime > 0 && savedBitField != null;

    List<List<String>> files = metainfo.getFiles();
    if (files == null)
      {
        // Create base as file.
        if (_log.shouldLog(Log.INFO))
            _log.info("Creating/Checking file: " + base);
        if (!base.createNewFile() && !base.exists())
          throw new IOException("Could not create file " + base);

        lengths = new long[1];
        rafs = new RandomAccessFile[1];
        names = new String[1];
        RAFlock = new Object[1];
        RAFtime = new long[1];
        RAFfile = new File[1];
        isSparse = new boolean[1];
        lengths[0] = metainfo.getTotalLength();
        RAFlock[0] = new Object();
        RAFfile[0] = base;
        if (useSavedBitField) {
            long lm = base.lastModified();
            if (lm <= 0 || lm > savedTime)
                useSavedBitField = false;
        }
        names[0] = base.getName();
      }
    else
      {
        // Create base as dir.
        if (_log.shouldLog(Log.INFO))
            _log.info("Creating/Checking directory: " + base);
        if (!base.mkdir() && !base.isDirectory())
          throw new IOException("Could not create directory " + base);

        List<Long> ls = metainfo.getLengths();
        int size = files.size();
        long total = 0;
        lengths = new long[size];
        rafs = new RandomAccessFile[size];
        names = new String[size];
        RAFlock = new Object[size];
        RAFtime = new long[size];
        RAFfile = new File[size];
        isSparse = new boolean[size];
        for (int i = 0; i < size; i++)
          {
            List<String> path = files.get(i);
            File f = createFileFromNames(base, path, areFilesPublic);
            // dup file name check after filtering
            for (int j = 0; j < i; j++) {
                if (f.equals(RAFfile[j])) {
                    // Rename and start the check over again
                    // Copy path since metainfo list is unmodifiable
                    path = new ArrayList(path);
                    int last = path.size() - 1;
                    String lastPath = path.get(last);
                    int dot = lastPath.lastIndexOf('.');
                    // foo.mp3 -> foo_.mp3; foo -> _foo
                    if (dot >= 0)
                        lastPath = lastPath.substring(0, dot) + '_' + lastPath.substring(dot);
                    else
                        lastPath = '_' + lastPath;
                    path.set(last, lastPath);
                    f = createFileFromNames(base, path, areFilesPublic);
                    j = 0;
                }
            }
            lengths[i] = ls.get(i).longValue();
            RAFlock[i] = new Object();
            RAFfile[i] = f;
            total += lengths[i];
            if (useSavedBitField) {
                long lm = f.lastModified();
                if (lm <= 0 || lm > savedTime)
                    useSavedBitField = false;
            }
            names[i] = f.getName();
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
      checkCreateFiles(false);
    }
    if (complete()) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Torrent is complete");
    } else {
        // fixme saved priorities
        if (files != null)
            priorities = new int[files.size()];
        if (_log.shouldLog(Log.INFO))
            _log.info("Still need " + needed + " out of " + metainfo.getPieces() + " pieces");
    }
  }

  /**
   * Doesn't really reopen the file descriptors for a restart.
   * Just does an existence check but no length check or data reverification
   *
   * @param rootDir ignored
   * @throws IOE on fail
   */
  public void reopen(String rootDir) throws IOException
  {
      if (RAFfile == null)
          throw new IOException("Storage not checked yet");
      for (int i = 0; i < RAFfile.length; i++) {
          if (!RAFfile[i].exists())
              throw new IOException("File does not exist: " + RAFfile[i]);
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
            } catch (Exception ex) {
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
   */
  private static File createFileFromNames(File base, List<String> names, boolean areFilesPublic) throws IOException
  {
    File f = null;
    Iterator<String> it = names.iterator();
    while (it.hasNext())
      {
        String name = filterName(it.next());
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
            if (!f.createNewFile() && !f.exists())
              throw new IOException("Could not create file " + f);
          }
      }
    return f;
  }

  public static File getFileFromNames(File base, List<String> names)
  {
    Iterator<String> it = names.iterator();
    while (it.hasNext())
      {
        String name = filterName(it.next());
        base = new File(base, name);
      }
    return base;
  }

  /**
   * This is called at the beginning, and at presumed completion,
   * so we have to be careful about locking.
   *
   * @param recheck if true, this is a check after we downloaded the
   *        last piece, and we don't modify the global bitfield unless
   *        the check fails.
   */
  private void checkCreateFiles(boolean recheck) throws IOException
  {
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
    for (int i = 0; i < rafs.length; i++)
      {
        long length = RAFfile[i].length();
        if(RAFfile[i].exists() && length == lengths[i])
          {
            if (listener != null)
              listener.storageAllocated(this, length);
            resume = true; // XXX Could dynamicly check
          }
        else if (length == 0) {
          changed = true;
          synchronized(RAFlock[i]) {
              allocateFile(i);
              // close as we go so we don't run out of file descriptors
              try {
                  closeRAF(i);
              } catch (IOException ioe) {}
          }
        } else {
          String msg = "File '" + names[i] + "' exists, but has wrong length (expected " +
                       lengths[i] + " but found " + length + ") - repairing corruption";
          if (listener != null)
              listener.addMessage(msg);
          _log.error(msg);
          changed = true;
          resume = true;
          _probablyComplete = false; // to force RW
          synchronized(RAFlock[i]) {
              checkRAF(i);
              rafs[i].setLength(lengths[i]);
              try {
                  closeRAF(i);
              } catch (IOException ioe) {}
          }
        }
      }

    // Check which pieces match and which don't
    if (resume)
      {
        byte[] piece = new byte[piece_size];
        int file = 0;
        long fileEnd = lengths[0];
        long pieceEnd = 0;
        for (int i = 0; i < pieces; i++)
          {
            int length = getUncheckedPiece(i, piece);
            boolean correctHash = metainfo.checkPiece(i, piece, 0, length);
            // close as we go so we don't run out of file descriptors
            pieceEnd += length;
            while (fileEnd <= pieceEnd) {
                synchronized(RAFlock[file]) {
                    try {
                        closeRAF(file);
                    } catch (IOException ioe) {}
                }
                if (++file >= rafs.length)
                    break;
                fileEnd += lengths[file];
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
    if (recheck && need > 0) {
        // whoops, recheck failed
        synchronized(bitfield) {
            bitfield = bfield;
        }
    }

    if (listener != null) {
      listener.storageAllChecked(this);
      if (needed <= 0)
        listener.storageCompleted(this);
    }
  }

  /**
   *  This creates a (presumably) sparse file so that reads won't fail with IOE.
   *  Sets isSparse[nr] = true. balloonFile(nr) should be called later to
   *  defrag the file.
   *
   *  This calls openRAF(); caller must synchronize and call closeRAF().
   */
  private void allocateFile(int nr) throws IOException
  {
    // caller synchronized
    openRAF(nr, false);  // RW
    long remaining = lengths[nr];
    if (listener != null)
        listener.storageCreateFile(this, names[nr], remaining);
    rafs[nr].setLength(remaining);
    // don't bother ballooning later on Windows since there is no sparse file support
    // until JDK7 using the JSR-203 interface.
    // RAF seeks/writes do not create sparse files.
    // Windows will zero-fill up to the point of the write, which
    // will make the file fairly unfragmented, on average, at least until
    // near the end where it will get exponentially more fragmented.
    if (!_isWindows)
        isSparse[nr] = true;
    // caller will close rafs[nr]
    if (listener != null)
      listener.storageAllocated(this, lengths[nr]);
  }

  /**
   *  This "balloons" the file with zeros to eliminate disk fragmentation.,
   *  Overwrites the entire file with zeros. Sets isSparse[nr] = false.
   *
   *  Caller must synchronize and call checkRAF() or openRAF().
   *  @since 0.9.1
   */
  private void balloonFile(int nr) throws IOException
  {
    if (_log.shouldLog(Log.INFO))
        _log.info("Ballooning " + nr + ": " + RAFfile[nr]);
    long remaining = lengths[nr];
    final int ZEROBLOCKSIZE = (int) Math.min(remaining, 32*1024);
    byte[] zeros = new byte[ZEROBLOCKSIZE];
    rafs[nr].seek(0);
    while (remaining > 0) {
        int size = (int) Math.min(remaining, ZEROBLOCKSIZE);
        rafs[nr].write(zeros, 0, size);
        remaining -= size;
    }
    isSparse[nr] = false;
  }


  /**
   * Closes the Storage and makes sure that all RandomAccessFiles are
   * closed. The Storage is unusable after this.
   */
  public void close() throws IOException
  {
    if (rafs == null) return;
    for (int i = 0; i < rafs.length; i++)
      {
        // if we had an IOE in check(), the RAFlock may be null
        if (RAFlock[i] == null)
            continue;
        try {
          synchronized(RAFlock[i]) {
            closeRAF(i);
          }
        } catch (IOException ioe) {
            _log.error("Error closing " + RAFfile[i], ioe);
            // gobble gobble
        }
      }
    changed = false;
  }

  /**
   * Returns a byte array containing a portion of the requested piece or null if
   * the storage doesn't contain the piece yet.
   */
  public byte[] getPiece(int piece, int off, int len) throws IOException
  {
    if (!bitfield.get(piece))
      return null;

    //Catch a common place for OOMs esp. on 1MB pieces
    byte[] bs;
    try {
      bs = new byte[len];
    } catch (OutOfMemoryError oom) {
      if (_log.shouldLog(Log.WARN))
          _log.warn("Out of memory, can't honor request for piece " + piece, oom);
      return null;
    }
    getUncheckedPiece(piece, bs, off, len);
    return bs;
  }

  /**
   * Put the piece in the Storage if it is correct.
   *
   * @return true if the piece was correct (sha metainfo hash
   * matches), otherwise false.
   * @exception IOException when some storage related error occurs.
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
          if (listener != null)
            listener.storageChecked(this, piece, correctHash);
          if (!correctHash) {
              return false;
          }

          // Early typecast, avoid possibly overflowing a temp integer
          long start = (long) piece * (long) piece_size;
          int i = 0;
          long raflen = lengths[i];
          while (start > raflen) {
              i++;
              start -= raflen;
              raflen = lengths[i];
          }
    
          int written = 0;
          int off = 0;
          int length = metainfo.getPieceLength(piece);
          while (written < length) {
              int need = length - written;
              int len = (start + need < raflen) ? need : (int)(raflen - start);
              synchronized(RAFlock[i]) {
                  checkRAF(i);
                  if (isSparse[i]) {
                      // If the file is a newly created sparse file,
                      // AND we aren't skipping it, balloon it with all
                      // zeros to un-sparse it by allocating the space.
                      // Obviously this could take a while.
                      // Once we have written to it, it isn't empty/sparse any more.
                      if (priorities == null || priorities[i] >= 0)
                          balloonFile(i);
                      else
                          isSparse[i] = false;
                  }
                  rafs[i].seek(start);
                  //rafs[i].write(bs, off + written, len);
                  pp.write(rafs[i], off + written, len);
              }
              written += len;
              if (need - len > 0) {
                  i++;
                  raflen = lengths[i];
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
    long raflen = lengths[i];
    while (start > raflen)
      {
        i++;
        start -= raflen;
        raflen = lengths[i];
      }

    int read = 0;
    while (read < length)
      {
        int need = length - read;
        int len = (start + need < raflen) ? need : (int)(raflen - start);
        synchronized(RAFlock[i])
          {
            checkRAF(i);
            rafs[i].seek(start);
            rafs[i].readFully(bs, read, len);
          }
        read += len;
        if (need - len > 0)
          {
            i++;
            raflen = lengths[i];
            start = 0;
          }
      }

    return length;
  }

  /**
   * Close unused RAFs - call periodically
   */
  private static final long RAFCloseDelay = 4*60*1000;
  public void cleanRAFs() {
    long cutoff = System.currentTimeMillis() - RAFCloseDelay;
    for (int i = 0; i < RAFlock.length; i++) {
      synchronized(RAFlock[i]) {
        if (RAFtime[i] > 0 && RAFtime[i] < cutoff) {
          try {
             closeRAF(i);
          } catch (IOException ioe) {}
        }
      }
    }
  }

  /*
   * For each of the following,
   * caller must synchronize on RAFlock[i]
   * ... except at the beginning if you're careful
   */

  /**
   * This must be called before using the RAF to ensure it is open
   */
  private void checkRAF(int i) throws IOException {
    if (RAFtime[i] > 0) {
      RAFtime[i] = System.currentTimeMillis();
      return;
    }
    openRAF(i);
  }

  private void openRAF(int i) throws IOException {
    openRAF(i, _probablyComplete);
  }

  private void openRAF(int i, boolean readonly) throws IOException {
    rafs[i] = new RandomAccessFile(RAFfile[i], (readonly || !RAFfile[i].canWrite()) ? "r" : "rw");
    RAFtime[i] = System.currentTimeMillis();
  }

  /**
   * Can be called even if not open
   */
  private void closeRAF(int i) throws IOException {
    RAFtime[i] = 0;
    if (rafs[i] == null)
      return;
    rafs[i].close();
    rafs[i] = null;
  }

}
