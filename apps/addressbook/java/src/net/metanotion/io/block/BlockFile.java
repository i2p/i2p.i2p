/*
Copyright (c) 2006, Matthew Estes
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

	* Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
	* Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
	* Neither the name of Metanotion Software nor the names of its
contributors may be used to endorse or promote products derived from this
software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package net.metanotion.io.block;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import net.metanotion.io.RAIFile;
import net.metanotion.io.RandomAccessInterface;
import net.metanotion.io.Serializer;
import net.metanotion.io.data.IdentityBytes;
import net.metanotion.io.data.IntBytes;
import net.metanotion.io.data.StringBytes;
import net.metanotion.io.data.UTF8StringBytes;
import net.metanotion.io.block.index.BSkipList;
import net.metanotion.io.block.index.BSkipSpan;
import net.metanotion.util.skiplist.SkipIterator;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * On-disk format:
 *    Magic number (6 bytes)
 *    Version major/minor (2 bytes)
 *    file length (long)
 *    free list start (unsigned int)
 *    is mounted (unsigned short) 0 = no, 1 = yes
 *    span size (unsigned short)
 *    block size (unsigned int)
 *
 * Metaindex skiplist is on page 2
 *
 * Pages are 1 KB and are numbered starting from 1.
 * e.g. the Metaindex skiplist is at offset 1024 bytes
 */
public class BlockFile implements Closeable {
	public static final int PAGESIZE = 1024;
	public static final long OFFSET_MOUNTED = 20;
	public final Log log = I2PAppContext.getGlobalContext().logManager().getLog(BlockFile.class);

	public final RandomAccessInterface file;

	private static final int MAJOR = 0x01;
	private static final int MINOR = 0x02;
	private static final int MIN_MAJOR = 0x01;
	private static final int MIN_MINOR = 0x01;
	// I2P changed magic number, format changed, magic numbers now on all pages
	private static final long MAGIC_BASE = 0x3141de4932500000L;   // 0x3141de I 2 P 00 00
	private static final long MAGIC = MAGIC_BASE | (MAJOR << 8) | MINOR;
	private long magicBytes = MAGIC;
	public static final int MAGIC_CONT = 0x434f4e54;   // "CONT"
	public static final int METAINDEX_PAGE = 2;
	/** 2**32 pages of 1024 bytes each, more or less */
	private static final long MAX_LEN = (2l << (32 + 10)) - 1;

	/** new BlockFile length, containing a superblock page and a metaindex page. */
	private long fileLen = PAGESIZE * 2;
	private int freeListStart = 0;
	private int mounted = 0;
	public int spanSize = 16;

	/** I2P was the file locked when we opened it? */
	private final boolean _wasMounted;

	private final BSkipList<String, Integer> metaIndex;
	private boolean _isClosed;
	/** cached list of free pages, only valid if freListStart > 0 */
	private FreeListBlock flb;
	private final HashMap<String, BSkipList> openIndices = new HashMap<String, BSkipList>();

	private void mount() throws IOException {
		file.seek(BlockFile.OFFSET_MOUNTED);
		mounted = 1;
		file.writeShort(mounted);
	}

	private void writeSuperBlock() throws IOException {
		file.seek(0);
		file.writeLong(	magicBytes);
		file.writeLong(	fileLen);
		file.writeInt(	freeListStart);
		file.writeShort(mounted);
		file.writeShort(spanSize);
		// added in version 1.2
		file.writeInt(PAGESIZE);
	}

	private void readSuperBlock() throws IOException {
		file.seek(0);
		magicBytes		= file.readLong();
		fileLen			= file.readLong();
		freeListStart	= file.readUnsignedInt();
		mounted			= file.readUnsignedShort();
		spanSize		= file.readUnsignedShort();
		// assume 1024 page size
	}

	/**
	 *  Run an integrity check on the blockfile and all the skiplists in it.
	 *
	 *  WARNING:
	 *  This only works on skiplists using UTF8StringBytes as a key
	 *  serializer, unless the exception has been coded in bfck below.
	 *  Will CORRUPT other skiplists.
	 */
	public static void main(String args[]) {
		if (args.length != 1) {
			System.err.println("Usage: BlockFile file");
			return;
		}
		boolean init = !(new File(args[0])).exists();
		RAIFile raif = null;
		BlockFile bf = null;
		try {
			raif = new RAIFile(new File(args[0]), true, true);
			bf = new BlockFile(raif, init);
			bf.bfck(true);
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (bf != null) try { bf.close(); } catch (IOException ioe) {}
			if (raif != null) try { raif.close(); } catch (IOException ioe) {}
		}
	}

	/**
	 *  Write bytes
	 *  This will allocate additional continuation pages as necessary.
	 *
	 *  @param data data to write
	 *  @param page current page
	 *  @param curPageOff in (current) and out (new) parameter at index 0
	 *  @param nextPage in (current) and out (new) parameter at index 0
	 *  @return current page
	 */
	public int writeMultiPageData(byte[] data, int page, int[] curPageOff, int[] nextPage) throws IOException {
		int pageCounter = curPageOff[0];
		int curNextPage = nextPage[0];
		int curPage = page;
		int dct = 0;
		while(dct < data.length) {
			int len = PAGESIZE - pageCounter;
			if(len <= 0) {
				if(curNextPage==0) {
					curNextPage = this.allocPage();
					BlockFile.pageSeek(this.file, curNextPage);
					this.file.writeInt(MAGIC_CONT);
					this.file.writeInt(0);
					BlockFile.pageSeek(this.file, curPage);
					this.file.skipBytes(4);   // skip magic
					this.file.writeInt(curNextPage);
				}
				BlockFile.pageSeek(this.file, curNextPage);
				curPage = curNextPage;
				int magic = this.file.readInt();
				if (magic != MAGIC_CONT)
					throw new IOException("Bad SkipSpan continuation magic number 0x" + Integer.toHexString(magic) + " on page " + curNextPage);
				curNextPage = this.file.readUnsignedInt();
				pageCounter = BSkipSpan.CONT_HEADER_LEN;
				len = PAGESIZE - pageCounter;
			}
			this.file.write(data, dct, Math.min(len, data.length - dct));
			pageCounter += Math.min(len, data.length - dct);
			dct += Math.min(len, data.length - dct);
		}
		nextPage[0] = curNextPage;
		curPageOff[0] = pageCounter;
		return curPage;
	}

	/**
	 *  Read bytes
	 *
	 *  @param arr fill this array fully with data
	 *  @param page current page
	 *  @param curPageOff in (current) and out (new) parameter at index 0
	 *  @param nextPage in (current) and out (new) parameter at index 0
	 *  @return current page
	 */
	public int readMultiPageData(byte[] arr, int page, int[] curPageOff, int[] nextPage) throws IOException {
		int pageCounter = curPageOff[0];
		int curNextPage = nextPage[0];
		int curPage = page;
		int dct = 0;
		while(dct < arr.length) {
			int len = PAGESIZE - pageCounter;
			if(len <= 0) {
				if (curNextPage <= 0)
					throw new IOException("not enough pages to read data still need " + (arr.length - dct));
				BlockFile.pageSeek(this.file, curNextPage);
				int magic = this.file.readInt();
				if (magic != MAGIC_CONT)
					throw new IOException("Bad SkipSpan continuation magic number 0x" + Integer.toHexString(magic) + " on page " + curNextPage);
				curPage = curNextPage;
				curNextPage = this.file.readUnsignedInt();
				pageCounter = BSkipSpan.CONT_HEADER_LEN;
				len = PAGESIZE - pageCounter;
			}
			int res = this.file.read(arr, dct, Math.min(len, arr.length - dct));
			if(res == -1) { throw new IOException(); }
			pageCounter += Math.min(len, arr.length - dct);
			dct += res;
		}
		nextPage[0] = curNextPage;
		curPageOff[0] = pageCounter;
		return curPage;
	}

	/**
	 *  Skip length bytes
	 *  The same as readMultiPageData() without returning a result
	 *
	 *  @param length number of bytes to skip
	 *  @param page current page
	 *  @param curPageOff in (current) and out (new) parameter at index 0
	 *  @param nextPage in (current) and out (new) parameter at index 0
	 *  @return current page
	 */
	public int skipMultiPageBytes(int length, int page, int[] curPageOff, int[] nextPage) throws IOException {
		int pageCounter = curPageOff[0];
		int curNextPage = nextPage[0];
		int curPage = page;
		int dct = 0;
		while(dct < length) {
			int len = PAGESIZE - pageCounter;
			if(len <= 0) {
				if (curNextPage <= 0)
					throw new IOException("not enough pages to skip");
				BlockFile.pageSeek(this.file, curNextPage);
				int magic = this.file.readInt();
				if (magic != MAGIC_CONT)
					throw new IOException("Bad SkipSpan continuation magic number 0x" + Integer.toHexString(magic) + " on page " + curNextPage);
				curPage = curNextPage;
				curNextPage = this.file.readUnsignedInt();
				pageCounter = BSkipSpan.CONT_HEADER_LEN;
				len = PAGESIZE - pageCounter;
			}
			int res = Math.min(len, length - dct);
			this.file.skipBytes(res);
			pageCounter += res;
			dct += res;
		}
		nextPage[0] = curNextPage;
		curPageOff[0] = pageCounter;
		return curPage;
	}

	/** Use this constructor with a readonly RAI for a readonly blockfile */
	public BlockFile(RandomAccessInterface rai) throws IOException { this(rai, false); }

	/** RAF must be writable */
	public BlockFile(RandomAccessFile raf) throws IOException { this(new RAIFile(raf), false); }

	/** RAF must be writable */
	public BlockFile(RandomAccessFile raf, boolean init) throws IOException { this(new RAIFile(raf), init); }

	/** File must be writable */
	public BlockFile(File f, boolean init) throws IOException { this(new RAIFile(f, true, true), init); }

	/** Use this constructor with a readonly RAI and init = false for a readonly blockfile */
	public BlockFile(RandomAccessInterface rai, boolean init) throws IOException {
		if(rai==null) { throw new NullPointerException(); }
		
		file = rai;

		if(init) {
			file.setLength(fileLen);
			writeSuperBlock();
			BSkipList.init(this, METAINDEX_PAGE, spanSize);
		}

		readSuperBlock();
		if(magicBytes != MAGIC) {
			if ((magicBytes & MAGIC_BASE) == MAGIC_BASE) {
				long major = (magicBytes >> 8) & 0xff;
				long minor = magicBytes & 0xff;
				if (major < MIN_MAJOR ||
				    (major == MIN_MAJOR && minor < MIN_MINOR))
				    throw new IOException("Expected " + MAJOR + '.' + MINOR +
				                          " but got " + major + '.' + minor);
			} else {
				throw new IOException("Bad magic number");
			}
		}
		_wasMounted = mounted != 0;
		if (_wasMounted)
			log.warn("Warning - file was not previously closed");
		if(fileLen != file.length())
			throw new IOException("Expected file length " + fileLen +
		                              " but actually " + file.length());
		if (rai.canWrite())
			mount();

		metaIndex = new BSkipList<String, Integer>(spanSize, this, METAINDEX_PAGE, new StringBytes(), new IntBytes());
	}

	/**
	 *  I2P was the file locked when we opened it?
	 *  @since 0.8.8
	 */
	public boolean wasMounted() {
		return _wasMounted;
	}

	/**
	 *  Go to any page but the superblock.
	 *  Page 1 is the superblock, must use file.seek(0) to get there.
	 *  @param page &gt;= 2
	 */
	public static void pageSeek(RandomAccessInterface file, int page) throws IOException {
		if (page < METAINDEX_PAGE)
			throw new IOException("Negative page or superblock access attempt: " + page);
		file.seek((page - 1L) * PAGESIZE );
	}

	public int allocPage() throws IOException {
		if(freeListStart != 0) {
			try {
				if (flb == null)
					flb = new FreeListBlock(file, freeListStart);
				if(!flb.isEmpty()) {
					if (log.shouldLog(Log.DEBUG))
						log.debug("Alloc from " + flb);
					return flb.takePage();
				} else {
					if (log.shouldLog(Log.DEBUG))
						log.debug("Alloc returning empty " + flb);
					freeListStart = flb.getNextPage();
					writeSuperBlock();
					int rv = flb.page;
					flb = null;
					return rv;
				}
			} catch (IOException ioe) {
				log.error("Discarding corrupt free list block page " + freeListStart, ioe);
				freeListStart = 0;
			}
		}
		long offset = file.length();
		fileLen = offset + PAGESIZE;
		file.setLength(fileLen);
		writeSuperBlock();
		return (int) ((offset / PAGESIZE) + 1);
	}

	/**
	 *  Add the page to the free list. The file is never shrunk.
	 *  TODO: Reclaim free pages at end of file, or even do a full compaction.
	 *  Does not throw exceptions; logs on failure.
	 */
	public void freePage(int page) {
		if (page <= METAINDEX_PAGE) {
			log.error("Bad page free attempt: " + page);
			return;
		}
		try {
			if(freeListStart == 0) {
				freeListStart = page;
				FreeListBlock.initPage(file, page);
				writeSuperBlock();
				if (log.shouldLog(Log.DEBUG))
					log.debug("Freed page " + page + " as new FLB");
				return;
			}
			try {
				if (flb == null)
					flb = new FreeListBlock(file, freeListStart);
				if(flb.isFull()) {
					// Make the free page a new FLB
					if (log.shouldLog(Log.DEBUG))
						log.debug("Full: " + flb);
					FreeListBlock.initPage(file, page);
					if(flb.getNextPage() == 0) {
						// Put it at the tail.
						// Next free will make a new FLB at the head,
						// so we have one more FLB than we need.
						flb.setNextPage(page);
					} else {
						// Put it at the head
						flb = new FreeListBlock(file, page);
						flb.setNextPage(freeListStart);
						freeListStart = page;
						writeSuperBlock();
					}
					if (log.shouldLog(Log.DEBUG))
						log.debug("Freed page " + page + " to full " + flb);
					return;
				}
				flb.addPage(page);
				if (log.shouldLog(Log.DEBUG))
					log.debug("Freed page " + page + " to " + flb);
			} catch (IOException ioe) {
				log.error("Discarding corrupt free list block page " + freeListStart, ioe);
				freeListStart = page;
				FreeListBlock.initPage(file, page);
				writeSuperBlock();
				flb = null;
			}
		} catch (IOException ioe) {
			log.error("Error freeing page: " + page, ioe);
		}
	}

	/**
	 *  Open a skiplist if it exists.
	 *  Returns null if the skiplist does not exist.
	 *  Empty skiplists are not preserved after close.
	 *
	 *  If the file is writable, this runs an integrity check and repair
	 *  on first open.
	 *
	 *  @return null if not found
	 */
	@SuppressWarnings("unchecked")
	public <K extends Comparable<? super K>, V> BSkipList<K, V> getIndex(String name, Serializer<K> key, Serializer<V> val) throws IOException {
		// added I2P
		BSkipList<K, V> bsl = (BSkipList<K, V>) openIndices.get(name);
		if (bsl != null)
			return bsl;

		Integer page = metaIndex.get(name);
		if (page == null) { return null; }
		bsl = new BSkipList<K, V>(spanSize, this, page.intValue(), key, val, true);
		if (file.canWrite()) {
			log.info("Checking skiplist " + name + " in blockfile " + file);
			if (bsl.bslck(true, false))
				log.logAlways(Log.WARN, "Repaired skiplist " + name + " in blockfile " + file);
			else
				log.info("No errors in skiplist " + name + " in blockfile " + file);
		}
		openIndices.put(name, bsl);
		return bsl;
	}

	/**
	 *  Create and open a new skiplist if it does not exist.
	 *  Throws IOException if it already exists.
	 *
	 *  @throws IOException if already exists or other errors
	 */
	public <K extends Comparable<? super K>, V> BSkipList<K, V> makeIndex(String name, Serializer<K> key, Serializer<V> val) throws IOException {
		if(metaIndex.get(name) != null) { throw new IOException("Index already exists"); }
		int page = allocPage();
		metaIndex.put(name, Integer.valueOf(page));
		BSkipList.init(this, page, spanSize);
		BSkipList<K, V> bsl = new BSkipList<K, V>(spanSize, this, page, key, val, true);
		openIndices.put(name, bsl);
		return bsl;
	}

	/**
	 *  Delete a skiplist if it exists.
	 *  Must be open. Throws IOException if exists but is closed.
	 *  Broken before 0.9.26.
	 *
	 *  @throws IOException if it is closed.
	 */
	public void delIndex(String name) throws IOException {
		if (metaIndex.get(name) == null)
                    return;
		BSkipList bsl = openIndices.get(name);
		if (bsl == null)
			throw new IOException("Cannot delete closed skiplist, open it first: " + name);
		bsl.delete();
		openIndices.remove(name);
		metaIndex.remove(name);
	}

	/**
	 *  Close a skiplist if it is open.
	 *
	 *  Added I2P
	 */
	public void closeIndex(String name) {
		BSkipList bsl = openIndices.remove(name);
		if (bsl != null)
			bsl.flush();
	}

	/**
	 *  Reformat a skiplist with new Serializers if it exists.
	 *  The skiplist must be closed.
	 *  Throws IOException if the skiplist is open.
	 *  The skiplist will remain closed after completion.
	 *
	 *  @throws IOException if it is open or on errors
	 *  @since 0.9.26
	 */
	public <K extends Comparable<? super K>, V> void reformatIndex(String name, Serializer<K> oldKey, Serializer<V> oldVal,
	                          Serializer<K> newKey, Serializer<V> newVal) throws IOException {
		if (openIndices.containsKey(name))
			throw new IOException("Cannot reformat open skiplist " + name);
		BSkipList<K, V> old = getIndex(name, oldKey, oldVal);
		if (old == null)
			return;
		long start = System.currentTimeMillis();
		String tmpName = "---tmp---" + name + "---tmp---";
		BSkipList<K, V> tmp = getIndex(tmpName, newKey, newVal);
		if (tmp != null) {
			log.logAlways(Log.WARN, "Continuing on aborted reformat of list " + name);
		} else {
			tmp = makeIndex(tmpName, newKey, newVal);
		}

		// It could be much more efficient to do this at the
		// SkipSpan layer but that's way too hard.
		final int loop = 32;
		List<K> keys = new ArrayList<K>(loop);
		List<V> vals = new ArrayList<V>(loop);
		while (true) {
			SkipIterator<K, V> iter = old.iterator();
			for (int i = 0; iter.hasNext() && i < loop; i++) {
				try {
					keys.add(iter.nextKey());
					vals.add(iter.next());
				} catch (NoSuchElementException nsee) {
					throw new IOException("Unable to reformat corrupt list " + name, nsee);
				}
			}
			// save state, as deleting corrupts the iterator
			boolean done = !iter.hasNext();
			for (int i = 0; i < keys.size(); i++) {
				tmp.put(keys.get(i), vals.get(i));
			}
			for (int i = keys.size() - 1; i >= 0; i--) {
				old.remove(keys.get(i));
			}
			if (done)
				break;
			keys.clear();
			vals.clear();
		}

		delIndex(name);
		closeIndex(name);
		closeIndex(tmpName);
		Integer page = metaIndex.get(tmpName);
		metaIndex.put(name, page);
		metaIndex.remove(tmpName);
		if (log.shouldWarn())
			log.warn("reformatted list: " + name + " in " +
			         (System.currentTimeMillis() - start) + "ms");
	}

	/**
	 *  Closes all open skiplists and then the blockfile itself.
	 *
	 *  Note (I2P)
	 *  Does NOT close the RAF / RAI.
	 */
	public void close() throws IOException {
		// added I2P
		if (_isClosed)
			return;
		_isClosed = true;
		metaIndex.close();

		Set<String> oi = openIndices.keySet();
		Iterator<String> i = oi.iterator();
		Object k;
		while(i.hasNext()) {
			k = i.next();
			BSkipList bsl = openIndices.get(k);
			bsl.close();
		}

		// Unmount.
		if (file.canWrite()) {
			file.seek(BlockFile.OFFSET_MOUNTED);
			file.writeShort(0);
		}
	}

	/**
	 *  Run an integrity check on the blockfile and all the skiplists in it
	 *  @return true if the levels were modified.
	 */
	public boolean bfck(boolean fix) {
		if (log.shouldLog(Log.INFO)) {
			log.info("magic bytes " + magicBytes);
			log.info("fileLen " + fileLen);
			log.info("freeListStart " + freeListStart);
			log.info("mounted " + mounted);
			log.info("spanSize " + spanSize);
			log.info("Metaindex");
			log.info("Checking meta index in blockfile " + file);
		}
		boolean rv = metaIndex.bslck(fix, true);
		if (rv) {
			if (log.shouldLog(Log.WARN))
				log.warn("Repaired meta index in blockfile " + file);
		} else {
			if (log.shouldLog(Log.INFO))
				log.info("No errors in meta index in blockfile " + file);
		}
		int items = 0;
		for (SkipIterator iter = metaIndex.iterator(); iter.hasNext(); ) {
			String slname = (String) iter.nextKey();
			Integer page = (Integer) iter.next();
			if (log.shouldLog(Log.INFO))
				log.info("List " + slname + " page " + page);
			try {
				// This uses IdentityBytes, so the value class won't be right, but at least
				// it won't fail the out-of-order check
				boolean fail;
				if (slname.equals("%%__REVERSE__%%")) {
					Serializer<Integer> keyser = new IntBytes();
					fail = getIndex(slname, keyser, new IdentityBytes()) == null;
				} else {
					Serializer<String> keyser = new UTF8StringBytes();
					fail = getIndex(slname, keyser, new IdentityBytes()) == null;
				}
				if (fail) {
					log.error("Can't find list? " + slname);
					continue;
				}
				// The check is now done in getIndex(), no need to do here...
				// but we can't get the return value of the check here.
				items++;
			} catch (IOException ioe) {
				log.error("Error with list " + slname, ioe);
			}
		}
		log.info("Checked meta index and " + items + " skiplists");
		if(freeListStart != 0) {
			try {
			       if (flb == null)
					flb = new FreeListBlock(file, freeListStart);
				flb.flbck(true);
			} catch (IOException ioe) {
				log.error("Free list error", ioe);
			}
		} else {
			if (log.shouldLog(Log.INFO))
				log.info("No freelist");
		}
		return rv;
	}
}
