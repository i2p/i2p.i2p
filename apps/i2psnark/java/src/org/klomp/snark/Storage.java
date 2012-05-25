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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.crypto.SHA1;

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

  private final StorageListener listener;
  private I2PSnarkUtil _util;

  private BitField bitfield; // BitField to represent the pieces
  private int needed; // Number of pieces needed
  private boolean _probablyComplete;  // use this to decide whether to open files RO

  // XXX - Not always set correctly
  int piece_size;
  int pieces;
  boolean changed;

  /** The default piece size. */
  private static final int MIN_PIECE_SIZE = 256*1024;
  public static final int MAX_PIECE_SIZE = 1024*1024;
  /** The maximum number of pieces in a torrent. */
  public static final int MAX_PIECES = 10*1024;
  public static final long MAX_TOTAL_SIZE = MAX_PIECE_SIZE * (long) MAX_PIECES;

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
    this.metainfo = metainfo;
    this.listener = listener;
    needed = metainfo.getPieces();
    _probablyComplete = false;
    bitfield = new BitField(needed);
  }

  /**
   * Creates a storage from the existing file or directory together
   * with an appropriate MetaInfo file as can be announced on the
   * given announce String location.
   */
  public Storage(I2PSnarkUtil util, File baseFile, String announce, StorageListener listener)
    throws IOException
  {
    _util = util;
    this.listener = listener;
    // Create names, rafs and lengths arrays.
    getFiles(baseFile);
    
    long total = 0;
    ArrayList lengthsList = new ArrayList();
    for (int i = 0; i < lengths.length; i++)
      {
        long length = lengths[i];
        total += length;
        lengthsList.add(new Long(length));
      }

    piece_size = MIN_PIECE_SIZE;
    pieces = (int) ((total - 1)/piece_size) + 1;
    while (pieces > MAX_PIECES && piece_size < MAX_PIECE_SIZE)
      {
        piece_size = piece_size*2;
        pieces = (int) ((total - 1)/piece_size) +1;
      }

    // Note that piece_hashes and the bitfield will be filled after
    // the MetaInfo is created.
    byte[] piece_hashes = new byte[20*pieces];
    bitfield = new BitField(pieces);
    needed = 0;

    List files = new ArrayList();
    for (int i = 0; i < names.length; i++)
      {
        List file = new ArrayList();
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

    // Note that the piece_hashes are not correctly setup yet.
    metainfo = new MetaInfo(announce, baseFile.getName(), null, files,
                            lengthsList, piece_size, piece_hashes, total);

  }

  // Creates piece hashes for a new storage.
  // This does NOT create the files, just the hashes
  public void create() throws IOException
  {
//    if (true) {
        fast_digestCreate();
//    } else {
//        orig_digestCreate();
//    }
  }
  
/*
  private void orig_digestCreate() throws IOException {
    // Calculate piece_hashes
    MessageDigest digest = null;
    try
      {
        digest = MessageDigest.getInstance("SHA");
      }
    catch(NoSuchAlgorithmException nsa)
      {
        throw new InternalError(nsa.toString());
      }

    byte[] piece_hashes = metainfo.getPieceHashes();

    byte[] piece = new byte[piece_size];
    for (int i = 0; i < pieces; i++)
      {
        int length = getUncheckedPiece(i, piece);
        digest.update(piece, 0, length);
        byte[] hash = digest.digest();
        for (int j = 0; j < 20; j++)
          piece_hashes[20 * i + j] = hash[j];

        bitfield.set(i);

        if (listener != null)
          listener.storageChecked(this, i, true);
      }

    if (listener != null)
      listener.storageAllChecked(this);

    // Reannounce to force recalculating the info_hash.
    metainfo = metainfo.reannounce(metainfo.getAnnounce());
  }
*/

  /** FIXME we can run out of fd's doing this,
   *  maybe some sort of global close-RAF-right-away flag
   *  would do the trick */
  private void fast_digestCreate() throws IOException {
    // Calculate piece_hashes
    SHA1 digest = new SHA1();

    byte[] piece_hashes = metainfo.getPieceHashes();

    byte[] piece = new byte[piece_size];
    for (int i = 0; i < pieces; i++)
      {
        int length = getUncheckedPiece(i, piece);
        digest.update(piece, 0, length);
        byte[] hash = digest.digest();
        for (int j = 0; j < 20; j++)
          piece_hashes[20 * i + j] = hash[j];

        bitfield.set(i);
      }

    // Reannounce to force recalculating the info_hash.
    metainfo = metainfo.reannounce(metainfo.getAnnounce());
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
            _util.debug("WARNING: Skipping '" + f 
                        + "' not a normal file.", Snark.WARNING);
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
   * The BitField that tells which pieces this storage contains.
   * Do not change this since this is the current state of the storage.
   */
  public BitField getBitField()
  {
    return bitfield;
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
    File base = new File(rootDir, filterName(metainfo.getName()));
    boolean useSavedBitField = savedTime > 0 && savedBitField != null;

    List files = metainfo.getFiles();
    if (files == null)
      {
        // Create base as file.
        _util.debug("Creating/Checking file: " + base, Snark.NOTICE);
        if (!base.createNewFile() && !base.exists())
          throw new IOException("Could not create file " + base);

        lengths = new long[1];
        rafs = new RandomAccessFile[1];
        names = new String[1];
        RAFlock = new Object[1];
        RAFtime = new long[1];
        RAFfile = new File[1];
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
        _util.debug("Creating/Checking directory: " + base, Snark.NOTICE);
        if (!base.mkdir() && !base.isDirectory())
          throw new IOException("Could not create directory " + base);

        List  ls = metainfo.getLengths();
        int size = files.size();
        long total = 0;
        lengths = new long[size];
        rafs = new RandomAccessFile[size];
        names = new String[size];
        RAFlock = new Object[size];
        RAFtime = new long[size];
        RAFfile = new File[size];
        for (int i = 0; i < size; i++)
          {
            File f = createFileFromNames(base, (List)files.get(i));
            lengths[i] = ((Long)ls.get(i)).longValue();
            RAFlock[i] = new Object();
            RAFfile[i] = f;
            total += lengths[i];
            if (useSavedBitField) {
                long lm = base.lastModified();
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
      _util.debug("Found saved state and files unchanged, skipping check", Snark.NOTICE);
    } else {
      // the following sets the needed variable
      changed = true;
      checkCreateFiles();
    }
    if (complete())
        _util.debug("Torrent is complete", Snark.NOTICE);
    else
        _util.debug("Still need " + needed + " out of " + metainfo.getPieces() + " pieces", Snark.NOTICE);
  }

  /**
   * Reopen the file descriptors for a restart
   * Do existence check but no length check or data reverification
   */
  public void reopen(String rootDir) throws IOException
  {
    File base = new File(rootDir, filterName(metainfo.getName()));

    List files = metainfo.getFiles();
    if (files == null)
      {
        // Reopen base as file.
        _util.debug("Reopening file: " + base, Snark.NOTICE);
        if (!base.exists())
          throw new IOException("Could not reopen file " + base);
      }
    else
      {
        // Reopen base as dir.
        _util.debug("Reopening directory: " + base, Snark.NOTICE);
        if (!base.isDirectory())
          throw new IOException("Could not reopen directory " + base);

        int size = files.size();
        for (int i = 0; i < size; i++)
          {
            File f = getFileFromNames(base, (List)files.get(i));
            if (!f.exists())
                throw new IOException("Could not reopen file " + f);
          }

      }
  }

  /**
   * Removes 'suspicious' characters from the give file name.
   */
  private static String filterName(String name)
  {
    // XXX - Is this enough?
    return name.replace(File.separatorChar, '_');
  }

  private File createFileFromNames(File base, List names) throws IOException
  {
    File f = null;
    Iterator it = names.iterator();
    while (it.hasNext())
      {
        String name = filterName((String)it.next());
        if (it.hasNext())
          {
            // Another dir in the hierarchy.
            f = new File(base, name);
            if (!f.mkdir() && !f.isDirectory())
              throw new IOException("Could not create directory " + f);
            base = f;
          }
        else
          {
            // The final element (file) in the hierarchy.
            f = new File(base, name);
            if (!f.createNewFile() && !f.exists())
              throw new IOException("Could not create file " + f);
          }
      }
    return f;
  }

  public static File getFileFromNames(File base, List names)
  {
    Iterator it = names.iterator();
    while (it.hasNext())
      {
        String name = filterName((String)it.next());
        base = new File(base, name);
      }
    return base;
  }

  /**
   * This is called at the beginning, and at presumed completion,
   * so we have to be careful about locking.
   */
  private void checkCreateFiles() throws IOException
  {
    // Whether we are resuming or not,
    // if any of the files already exists we assume we are resuming.
    boolean resume = false;

    _probablyComplete = true;
    needed = metainfo.getPieces();

    // Make sure all files are available and of correct length
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
          }
        } else {
          _util.debug("File '" + names[i] + "' exists, but has wrong length - repairing corruption", Snark.ERROR);
          changed = true;
          _probablyComplete = false; // to force RW
          synchronized(RAFlock[i]) {
              checkRAF(i);
              rafs[i].setLength(lengths[i]);
          }
          // will be closed below
        }
      }

    // Check which pieces match and which don't
    if (resume)
      {
        pieces = metainfo.getPieces();
        byte[] piece = new byte[metainfo.getPieceLength(0)];
        for (int i = 0; i < pieces; i++)
          {
            int length = getUncheckedPiece(i, piece);
            boolean correctHash = metainfo.checkPiece(i, piece, 0, length);
            if (correctHash)
              {
                bitfield.set(i);
                needed--;
              }

            if (listener != null)
              listener.storageChecked(this, i, correctHash);
          }
      }

    _probablyComplete = complete();
    // close all the files so we don't end up with a zillion open ones;
    // we will reopen as needed
    for (int i = 0; i < rafs.length; i++) {
      synchronized(RAFlock[i]) {
        try {
          closeRAF(i);
        } catch (IOException ioe) {}
      }
    }

    if (listener != null) {
      listener.storageAllChecked(this);
      if (needed <= 0)
        listener.storageCompleted(this);
    }
  }

  private void allocateFile(int nr) throws IOException
  {
    // caller synchronized
    openRAF(nr, false);  // RW
    // XXX - Is this the best way to make sure we have enough space for
    // the whole file?
    listener.storageCreateFile(this, names[nr], lengths[nr]);
    final int ZEROBLOCKSIZE = metainfo.getPieceLength(0);
    byte[] zeros = new byte[ZEROBLOCKSIZE];
    int i;
    for (i = 0; i < lengths[nr]/ZEROBLOCKSIZE; i++)
      {
        rafs[nr].write(zeros);
        if (listener != null)
          listener.storageAllocated(this, ZEROBLOCKSIZE);
      }
    int size = (int)(lengths[nr] - i*ZEROBLOCKSIZE);
    rafs[nr].write(zeros, 0, size);
    // caller will close rafs[nr]
    if (listener != null)
      listener.storageAllocated(this, size);
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
        try {
          synchronized(RAFlock[i]) {
            closeRAF(i);
          }
        } catch (IOException ioe) {
            _util.debug("Error closing " + RAFfile[i], Snark.ERROR, ioe);
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
      _util.debug("Out of memory, can't honor request for piece " + piece, Snark.WARNING, oom);
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
  public boolean putPiece(int piece, byte[] ba) throws IOException
  {
    // First check if the piece is correct.
    // Copy the array first to be paranoid.
    byte[] bs = (byte[]) ba.clone();
    int length = bs.length;
    boolean correctHash = metainfo.checkPiece(piece, bs, 0, length);
    if (listener != null)
      listener.storageChecked(this, piece, correctHash);
    if (!correctHash)
      return false;

    synchronized(bitfield)
      {
        if (bitfield.get(piece))
          return true; // No need to store twice.
      }

    // Early typecast, avoid possibly overflowing a temp integer
    long start = (long) piece * (long) metainfo.getPieceLength(0);
    int i = 0;
    long raflen = lengths[i];
    while (start > raflen)
      {
        i++;
        start -= raflen;
        raflen = lengths[i];
      }
    
    int written = 0;
    int off = 0;
    while (written < length)
      {
        int need = length - written;
        int len = (start + need < raflen) ? need : (int)(raflen - start);
        synchronized(RAFlock[i])
          {
            checkRAF(i);
            rafs[i].seek(start);
            rafs[i].write(bs, off + written, len);
          }
        written += len;
        if (need - len > 0)
          {
            i++;
            raflen = lengths[i];
            start = 0;
          }
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
      // Todo: set a listener variable so the web shows "checking" and don't
      // have the user panic when completed amount goes to zero temporarily?
      needed = metainfo.getPieces();
      bitfield = new BitField(needed);
      checkCreateFiles();
      if (needed > 0) {
        if (listener != null)
            listener.setWantedPieces(this);
        _util.debug("WARNING: Not really done, missing " + needed
                    + " pieces", Snark.WARNING);
      }
    }

    return true;
  }

  private int getUncheckedPiece(int piece, byte[] bs)
    throws IOException
  {
      return getUncheckedPiece(piece, bs, 0, metainfo.getPieceLength(piece));
  }

  private int getUncheckedPiece(int piece, byte[] bs, int off, int length)
    throws IOException
  {
    // XXX - copy/paste code from putPiece().

    // Early typecast, avoid possibly overflowing a temp integer
    long start = ((long) piece * (long) metainfo.getPieceLength(0)) + off;

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
  private static final long RAFCloseDelay = 7*60*1000;
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
