/* MetaInfo - Holds all information gotten from a torrent file.
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

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA1;
import net.i2p.data.Base64;
import net.i2p.util.Log;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.BEncoder;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 * Note: this class is buggy, as it doesn't propogate custom meta fields into the bencoded
 * info data, and from there to the info_hash.  At the moment, though, it seems to work with
 * torrents created by I2P-BT, I2PRufus and Azureus.
 *
 */
public class MetaInfo
{  
  private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(MetaInfo.class);
  private final String announce;
  private final byte[] info_hash;
  private final String name;
  private final String name_utf8;
  private final List<List<String>> files;
  private final List<List<String>> files_utf8;
  private final List<Long> lengths;
  private final int piece_length;
  private final byte[] piece_hashes;
  private final long length;
  private Map<String, BEValue> infoMap;

  /**
   *  Called by Storage when creating a new torrent from local data
   *
   *  @param announce may be null
   *  @param files null for single-file torrent
   *  @param lengths null for single-file torrent
   */
  MetaInfo(String announce, String name, String name_utf8, List<List<String>> files, List<Long> lengths,
           int piece_length, byte[] piece_hashes, long length)
  {
    this.announce = announce;
    this.name = name;
    this.name_utf8 = name_utf8;
    this.files = files == null ? null : Collections.unmodifiableList(files);
    this.files_utf8 = null;
    this.lengths = lengths == null ? null : Collections.unmodifiableList(lengths);
    this.piece_length = piece_length;
    this.piece_hashes = piece_hashes;
    this.length = length;

    this.info_hash = calculateInfoHash();
    //infoMap = null;
  }

  /**
   * Creates a new MetaInfo from the given InputStream.  The
   * InputStream must start with a correctly bencoded dictonary
   * describing the torrent.
   * Caller must close the stream.
   */
  public MetaInfo(InputStream in) throws IOException
  {
    this(new BDecoder(in));
  }
  
  /**
   * Creates a new MetaInfo from the given BDecoder.  The BDecoder
   * must have a complete dictionary describing the torrent.
   */
  public MetaInfo(BDecoder be) throws IOException
  {
    // Note that evaluation order matters here...
    this(be.bdecodeMap().getMap());
  }

  /**
   * Creates a new MetaInfo from a Map of BEValues and the SHA1 over
   * the original bencoded info dictonary (this is a hack, we could
   * reconstruct the bencoded stream and recalculate the hash). Will
   * NOT throw a InvalidBEncodingException if the given map does not
   * contain a valid announce string.
   * WILL throw a InvalidBEncodingException if the given map does not
   * contain a valid info dictionary.
   */
  public MetaInfo(Map m) throws InvalidBEncodingException
  {
    if (_log.shouldLog(Log.DEBUG))
        _log.debug("Creating a metaInfo: " + m, new Exception("source"));
    BEValue val = (BEValue)m.get("announce");
    // Disabled check, we can get info from a magnet now
    if (val == null) {
        //throw new InvalidBEncodingException("Missing announce string");
        this.announce = null;
    } else {
        this.announce = val.getString();
    }

    val = (BEValue)m.get("info");
    if (val == null)
        throw new InvalidBEncodingException("Missing info map");
    Map info = val.getMap();
    infoMap = Collections.unmodifiableMap(info);

    val = (BEValue)info.get("name");
    if (val == null)
        throw new InvalidBEncodingException("Missing name string");
    name = val.getString();

    val = (BEValue)info.get("name.utf-8");
    if (val != null)
        name_utf8 = val.getString();
    else
        name_utf8 = null;

    val = (BEValue)info.get("piece length");
    if (val == null)
        throw new InvalidBEncodingException("Missing piece length number");
    piece_length = val.getInt();

    val = (BEValue)info.get("pieces");
    if (val == null)
        throw new InvalidBEncodingException("Missing piece bytes");
    piece_hashes = val.getBytes();

    val = (BEValue)info.get("length");
    if (val != null)
      {
        // Single file case.
        length = val.getLong();
        files = null;
        files_utf8 = null;
        lengths = null;
      }
    else
      {
        // Multi file case.
        val = (BEValue)info.get("files");
        if (val == null)
          throw new InvalidBEncodingException
            ("Missing length number and/or files list");

        List<BEValue> list = val.getList();
        int size = list.size();
        if (size == 0)
          throw new InvalidBEncodingException("zero size files list");

        List<List<String>> m_files = new ArrayList(size);
        List<List<String>> m_files_utf8 = new ArrayList(size);
        List<Long> m_lengths = new ArrayList(size);
        long l = 0;
        for (int i = 0; i < list.size(); i++)
          {
            Map<String, BEValue> desc = list.get(i).getMap();
            val = desc.get("length");
            if (val == null)
              throw new InvalidBEncodingException("Missing length number");
            long len = val.getLong();
            m_lengths.add(new Long(len));
            l += len;

            val = (BEValue)desc.get("path");
            if (val == null)
              throw new InvalidBEncodingException("Missing path list");
            List<BEValue> path_list = val.getList();
            int path_length = path_list.size();
            if (path_length == 0)
              throw new InvalidBEncodingException("zero size file path list");

            List<String> file = new ArrayList(path_length);
            Iterator<BEValue> it = path_list.iterator();
            while (it.hasNext())
              file.add(it.next().getString());

            m_files.add(Collections.unmodifiableList(file));
            
            val = (BEValue)desc.get("path.utf-8");
            if (val != null) {
                path_list = val.getList();
                path_length = path_list.size();
                if (path_length > 0) {
                    file = new ArrayList(path_length);
                    it = path_list.iterator();
                    while (it.hasNext())
                        file.add(it.next().getString());
                    m_files_utf8.add(Collections.unmodifiableList(file));
                }
            }
          }
        files = Collections.unmodifiableList(m_files);
        files_utf8 = Collections.unmodifiableList(m_files_utf8);
        lengths = Collections.unmodifiableList(m_lengths);
        length = l;
      }

    info_hash = calculateInfoHash();
  }

  /**
   * Returns the string representing the URL of the tracker for this torrent.
   * @return may be null!
   */
  public String getAnnounce()
  {
    return announce;
  }

  /**
   * Returns the original 20 byte SHA1 hash over the bencoded info map.
   */
  public byte[] getInfoHash()
  {
    // XXX - Should we return a clone, just to be sure?
    return info_hash;
  }

  /**
   * Returns the piece hashes. Only used by storage so package local.
   */
  byte[] getPieceHashes()
  {
    return piece_hashes;
  }

  /**
   * Returns the requested name for the file or toplevel directory.
   * If it is a toplevel directory name getFiles() will return a
   * non-null List of file name hierarchy name.
   */
  public String getName()
  {
    return name;
  }

  /**
   * Returns a list of lists of file name hierarchies or null if it is
   * a single name. It has the same size as the list returned by
   * getLengths().
   */
  public List<List<String>> getFiles()
  {
    return files;
  }

  /**
   * Returns a list of Longs indication the size of the individual
   * files, or null if it is a single file. It has the same size as
   * the list returned by getFiles().
   */
  public List<Long> getLengths()
  {
    return lengths;
  }

  /**
   * Returns the number of pieces.
   */
  public int getPieces()
  {
    return piece_hashes.length/20;
  }

  /**
   * Return the length of a piece. All pieces are of equal length
   * except for the last one (<code>getPieces()-1</code>).
   *
   * @exception IndexOutOfBoundsException when piece is equal to or
   * greater then the number of pieces in the torrent.
   */
  public int getPieceLength(int piece)
  {
    int pieces = getPieces();
    if (piece >= 0 && piece < pieces -1)
      return piece_length;
    else if (piece == pieces -1)
      return (int)(length - ((long)piece * piece_length));
    else
      throw new IndexOutOfBoundsException("no piece: " + piece);
  }
        
  /**
   * Checks that the given piece has the same SHA1 hash as the given
   * byte array. Returns random results or IndexOutOfBoundsExceptions
   * when the piece number is unknown.
   */
  public boolean checkPiece(int piece, byte[] bs, int off, int length)
  {
    if (true)
        return fast_checkPiece(piece, bs, off, length);
    else
        return orig_checkPiece(piece, bs, off, length);
  }
  private boolean orig_checkPiece(int piece, byte[] bs, int off, int length) {
    // Check digest
    MessageDigest sha1;
    try
      {
        sha1 = MessageDigest.getInstance("SHA");
      }
    catch (NoSuchAlgorithmException nsae)
      {
        throw new InternalError("No SHA digest available: " + nsae);
      }

    sha1.update(bs, off, length);
    byte[] hash = sha1.digest();
    for (int i = 0; i < 20; i++)
      if (hash[i] != piece_hashes[20 * piece + i])
        return false;
    return true;
  }
  
  private boolean fast_checkPiece(int piece, byte[] bs, int off, int length) {
    SHA1 sha1 = new SHA1();

    sha1.update(bs, off, length);
    byte[] hash = sha1.digest();
    for (int i = 0; i < 20; i++)
      if (hash[i] != piece_hashes[20 * piece + i])
        return false;
    return true;
  }

  /**
   * Returns the total length of the torrent in bytes.
   */
  public long getTotalLength()
  {
    return length;
  }

    @Override
  public String toString()
  {
    return "MetaInfo[info_hash='" + hexencode(info_hash)
      + "', announce='" + announce
      + "', name='" + name
      + "', files=" + files
      + ", #pieces='" + piece_hashes.length/20
      + "', piece_length='" + piece_length
      + "', length='" + length
      + "']";
  }

  /**
   * Encode a byte array as a hex encoded string.
   */
  private static String hexencode(byte[] bs)
  {
    StringBuilder sb = new StringBuilder(bs.length*2);
    for (int i = 0; i < bs.length; i++)
      {
        int c = bs[i] & 0xFF;
        if (c < 16)
          sb.append('0');
        sb.append(Integer.toHexString(c));
      }

    return sb.toString();
  }

  /**
   * Creates a copy of this MetaInfo that shares everything except the
   * announce URL.
   */
  public MetaInfo reannounce(String announce)
  {
    return new MetaInfo(announce, name, name_utf8, files,
                        lengths, piece_length,
                        piece_hashes, length);
  }

  /**
   *  Called by servlet to save a new torrent file generated from local data
   */
  public synchronized byte[] getTorrentData()
  {
        Map m = new HashMap();
        if (announce != null)
            m.put("announce", announce);
        Map info = createInfoMap();
        m.put("info", info);
        // don't save this locally, we should only do this once
        return BEncoder.bencode(m);
  }

  /** @since 0.8.4 */
  public synchronized byte[] getInfoBytes() {
    if (infoMap == null)
        createInfoMap();
    return BEncoder.bencode(infoMap);
  }

  /** @return an unmodifiable view of the Map */
  private Map<String, BEValue> createInfoMap()
  {
    // if we loaded this metainfo from a file, we have the map
    if (infoMap != null)
        return Collections.unmodifiableMap(infoMap);
    // otherwise we must create it
    Map info = new HashMap();
    info.put("name", name);
    if (name_utf8 != null)
        info.put("name.utf-8", name_utf8);
    info.put("piece length", Integer.valueOf(piece_length));
    info.put("pieces", piece_hashes);
    if (files == null)
      info.put("length", new Long(length));
    else
      {
        List l = new ArrayList();
        for (int i = 0; i < files.size(); i++)
          {
            Map file = new HashMap();
            file.put("path", files.get(i));
            if ( (files_utf8 != null) && (files_utf8.size() > i) )
                file.put("path.utf-8", files_utf8.get(i));
            file.put("length", lengths.get(i));
            l.add(file);
          }
        info.put("files", l);
      }
    infoMap = info;
    return Collections.unmodifiableMap(infoMap);
  }

  private byte[] calculateInfoHash()
  {
    Map info = createInfoMap();
    StringBuilder buf = new StringBuilder(128);
    buf.append("info: ");
    for (Iterator iter = info.entrySet().iterator(); iter.hasNext(); ) {
        Map.Entry entry = (Map.Entry)iter.next();
        String key = (String)entry.getKey();
        Object val = entry.getValue();
        buf.append(key).append('=');
        if (val instanceof byte[])
            buf.append(Base64.encode((byte[])val, true));
        else
            buf.append(val.toString());
    }
    if (_log.shouldLog(Log.DEBUG))
        _log.debug(buf.toString());
    byte[] infoBytes = BEncoder.bencode(info);
    //_log.debug("info bencoded: [" + Base64.encode(infoBytes, true) + "]");
    try
      {
        MessageDigest digest = MessageDigest.getInstance("SHA");
        byte hash[] = digest.digest(infoBytes);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("info hash: [" + net.i2p.data.Base64.encode(hash) + "]");
        return hash;
      }
    catch(NoSuchAlgorithmException nsa)
      {
        throw new InternalError(nsa.toString());
      }
  }

  
}
