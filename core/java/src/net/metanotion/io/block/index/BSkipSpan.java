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
package net.metanotion.io.block.index;

import java.io.IOException;

import net.metanotion.io.RandomAccessInterface;
import net.metanotion.io.Serializer;
import net.metanotion.io.block.BlockFile;
import net.metanotion.util.skiplist.SkipList;
import net.metanotion.util.skiplist.SkipSpan;

import net.i2p.util.Log;

/**
 * On-disk format:
 *
 *<pre>
 *   First Page:
 *     Magic number (int)
 *     overflow page (unsigned int)
 *     previous page (unsigned int)
 *     next page (unsigned int)
 *     max keys (unsigned short)
 *     number of keys (unsigned short)
 *     for each key:
 *         key length (unsigned short)
 *         value length (unsigned short)
 *         key data
 *         value data
 *
 *   Overflow pages:
 *     Magic number (int)
 *     next overflow page (unsigned int)
 *</pre>
 */
public class BSkipSpan extends SkipSpan {
	protected static final int MAGIC = 0x5370616e;  // "Span"
	protected static final int HEADER_LEN = 20;
	public static final int CONT_HEADER_LEN = 8;
	protected final BlockFile bf;
	private final BSkipList bsl;
	protected int page;
	protected int overflowPage;

	protected int prevPage;
	protected int nextPage;
	protected Serializer keySer;
	protected Serializer valSer;

	// I2P
	protected int spanSize;
	protected boolean isKilled;

	public static void init(BlockFile bf, int page, int spanSize) throws IOException {
		BlockFile.pageSeek(bf.file, page);
		bf.file.writeInt(MAGIC);
		bf.file.writeInt(0);
		bf.file.writeInt(0);
		bf.file.writeInt(0);
		bf.file.writeShort((short) spanSize);
		bf.file.writeShort(0);
	}

	@Override
	public SkipSpan newInstance(SkipList sl) {
		try {
			int newPage = bf.allocPage();
			init(bf, newPage, bf.spanSize);
			return new BSkipSpan(bf, (BSkipList) sl, newPage, keySer, valSer);
		} catch (IOException ioe) { throw new RuntimeException("Error creating database page", ioe); }
	}

	@Override
	public void killInstance() {
		if (isKilled) {
			BlockFile.log.error("Already killed!! " + this, new Exception());
			return;
		}
		BlockFile.log.info("Killing " + this);
		isKilled = true;
		try {
			int curPage = overflowPage;
			bf.freePage(page);
			freeContinuationPages(curPage);
		} catch (IOException ioe) {
			BlockFile.log.error("Error freeing " + this, ioe);
		}
		bsl.spanHash.remove(Integer.valueOf(this.page));
	}

	/**
	 *  Free a chain of continuation pages
	 *  @param curPage the first page to be freed, if 0 this does nothing.
	 *  @return number freed
	 */
	private int freeContinuationPages(int curPage) throws IOException {
		int rv = 0;
		while(curPage > 0) {
			BlockFile.pageSeek(bf.file, curPage);
			int magic = bf.file.readInt();
			if (magic != BlockFile.MAGIC_CONT)
				throw new IOException("Bad SkipSpan magic number 0x" + Integer.toHexString(magic) + " on page " + curPage);
			int next = bf.file.readUnsignedInt();
			bf.freePage(curPage);
			curPage = next;
			rv++;
		}
		return rv;
	}

	@Override
	public void flush() {
		fflush();
	}

	/**
	 * I2P - avoid super.flush()
	 */
	private void fflush() {
		if (isKilled) {
			BlockFile.log.error("Already killed!! " + this, new Exception());
			return;
		}
		try {
			BlockFile.pageSeek(bf.file, page);
			bf.file.writeInt(MAGIC);
			bf.file.writeInt(overflowPage);
			prevPage = (prev != null) ? ((BSkipSpan) prev).page : 0;
			nextPage = (next != null) ? ((BSkipSpan) next).page : 0;
			bf.file.writeInt(prevPage);
			bf.file.writeInt(nextPage);
			// if keys is null, we are (hopefully) just updating the prev/next pages on an unloaded span
			if (keys == null)
				return;
			bf.file.writeShort((short) keys.length);
			bf.file.writeShort((short) nKeys);

			int ksz, vsz;
			int curPage = this.page;
			int[] curNextPage = new int[1];
			curNextPage[0] = this.overflowPage;
			int[] pageCounter = new int[1];
			pageCounter[0] = HEADER_LEN;
			byte[] keyData;
			byte[] valData;

			for(int i=0;i<nKeys;i++) {
				if((pageCounter[0] + 4) > BlockFile.PAGESIZE) {
					if(curNextPage[0] == 0) {
						curNextPage[0] = bf.allocPage();
						BlockFile.pageSeek(bf.file, curNextPage[0]);
						bf.file.writeInt(BlockFile.MAGIC_CONT);
						bf.file.writeInt(0);
						BlockFile.pageSeek(bf.file, curPage);
						bf.file.skipBytes(4);  // skip magic
						bf.file.writeInt(curNextPage[0]);
					}
					BlockFile.pageSeek(bf.file, curNextPage[0]);
					curPage = curNextPage[0];
					bf.file.skipBytes(4);  // skip magic
					curNextPage[0] = bf.file.readUnsignedInt();
					pageCounter[0] = CONT_HEADER_LEN;
				}
				// Drop bad entry without throwing exception
				if (keys[i] == null || vals[i] == null) {
					BlockFile.log.error("Dropping null data in entry " + i + " page " + curPage +
					                    " key=" + this.keys[i] + " val=" + this.vals[i]);
					nKeys--;
					i--;
					continue;
				}
				keyData = this.keySer.getBytes(keys[i]);
				valData = this.valSer.getBytes(vals[i]);
				// Drop bad entry without throwing exception
				if (keyData.length > 65535 || valData.length > 65535) {
					BlockFile.log.error("Dropping huge data in entry " + i + " page " + curPage +
					                    " keylen=" + keyData.length + " vallen=" + valData.length);
					nKeys--;
					i--;
					continue;
				}
				pageCounter[0] += 4;
				bf.file.writeShort(keyData.length);
				bf.file.writeShort(valData.length);
				curPage = bf.writeMultiPageData(keyData, curPage, pageCounter, curNextPage);
				curPage = bf.writeMultiPageData(valData, curPage, pageCounter, curNextPage);
			}
			BlockFile.pageSeek(bf.file, this.page);
			bf.file.skipBytes(4);  // skip magic
			this.overflowPage = bf.file.readUnsignedInt();
			if (curNextPage[0] != 0) {
				// free extra continuation pages
				BlockFile.pageSeek(bf.file, curPage);
				bf.file.skipBytes(4);  // skip magic
				bf.file.writeInt(0);
				if (curPage == this.page)
					this.overflowPage = 0;
				try {
					int freed = freeContinuationPages(curNextPage[0]);
					if (BlockFile.log.shouldLog(Log.INFO))
						BlockFile.log.info("Freed " + freed + " continuation pages");
				} catch (IOException ioe) {
					BlockFile.log.error("Error freeing " + this, ioe);
				}
			}
		} catch (IOException ioe) { throw new RuntimeException("Error writing to database", ioe); }
		// FIXME can't get there from here
		//bsl.size -= fail;
		//bsl.flush();
	}

	private static void load(BSkipSpan bss, BlockFile bf, BSkipList bsl, int spanPage, Serializer key, Serializer val) throws IOException {
		loadInit(bss, bf, bsl, spanPage, key, val);
		bss.loadData();
	}

	/**
	 * I2P - first half of load()
	 * Only read the span headers
	 */
	protected static void loadInit(BSkipSpan bss, BlockFile bf, BSkipList bsl, int spanPage, Serializer key, Serializer val) throws IOException {
		if (bss.isKilled)
			throw new IOException("Already killed!! " + bss);
		bss.page = spanPage;
		bss.keySer = key;
		bss.valSer = val;

		bsl.spanHash.put(Integer.valueOf(spanPage), bss);

		BlockFile.pageSeek(bf.file, spanPage);

		int magic = bf.file.readInt();
		if (magic != MAGIC)
			throw new IOException("Bad SkipSpan magic number 0x" + Integer.toHexString(magic) + " on page " + spanPage);
		bss.overflowPage = bf.file.readUnsignedInt();
		bss.prevPage = bf.file.readUnsignedInt();
		bss.nextPage = bf.file.readUnsignedInt();
		bss.spanSize = bf.file.readUnsignedShort();
		bss.nKeys = bf.file.readUnsignedShort();
		if(bss.spanSize < 1 || bss.spanSize > SkipSpan.MAX_SIZE || bss.nKeys > bss.spanSize)
			throw new IOException("Invalid span size " + bss.nKeys + " / "+  bss.spanSize);
	}

	/**
	 * I2P - second half of load()
	 * Load the whole span's keys and values into memory
	 */
	protected void loadData() throws IOException {
		loadData(true);
	}

	/**
	 * I2P - second half of load()
	 * Load the whole span's keys and values into memory
	 * @param flushOnError set to false if you are going to flush anyway
	 */
	protected void loadData(boolean flushOnError) throws IOException {
		if (isKilled)
			throw new IOException("Already killed!! " + this);
		this.keys = new Comparable[this.spanSize];
		this.vals = new Object[this.spanSize];

		int ksz, vsz;
		int curPage = this.page;
		int[] curNextPage = new int[1];
		curNextPage[0] = this.overflowPage;
		int[] pageCounter = new int[1];
		pageCounter[0] = HEADER_LEN;
//		System.out.println("Span Load " + sz + " nKeys " + nKeys + " page " + curPage);
		int fail = 0;
		for(int i=0;i<this.nKeys;i++) {
			if((pageCounter[0] + 4) > BlockFile.PAGESIZE) {
				BlockFile.pageSeek(this.bf.file, curNextPage[0]);
				curPage = curNextPage[0];
				int magic = bf.file.readInt();
				if (magic != BlockFile.MAGIC_CONT)
					throw new IOException("Bad SkipSpan magic number 0x" + Integer.toHexString(magic) + " on page " + curPage);
				curNextPage[0] = this.bf.file.readUnsignedInt();
				pageCounter[0] = CONT_HEADER_LEN;
			}
			ksz = this.bf.file.readUnsignedShort();
			vsz = this.bf.file.readUnsignedShort();
			pageCounter[0] +=4;
			byte[] k = new byte[ksz];
			byte[] v = new byte[vsz];
			curPage = this.bf.readMultiPageData(k, curPage, pageCounter, curNextPage);
			curPage = this.bf.readMultiPageData(v, curPage, pageCounter, curNextPage);
//			System.out.println("i=" + i + ", Page " + curPage + ", offset " + pageCounter[0] + " ksz " + ksz + " vsz " + vsz);
			this.keys[i] = (Comparable) this.keySer.construct(k);
			this.vals[i] = this.valSer.construct(v);
			// Drop bad entry without throwing exception
			if (this.keys[i] == null || this.vals[i] == null) {
				BlockFile.log.error("Null deserialized data in entry " + i + " page " + curPage +
				                    " key=" + this.keys[i] + " val=" + this.vals[i]);
				fail++;
				nKeys--;
				i--;
				continue;
			}
		}
		// free any excess overflow pages?
		if (fail > 0) {
			BlockFile.log.error("Repairing corruption of " + fail + " entries");
			if (flushOnError)
				fflush();
			// FIXME can't get there from here
			//bsl.size -= fail;
			//bsl.flush();
		}
	}

	protected BSkipSpan(BlockFile bf, BSkipList bsl) {
		this.bf = bf;
		this.bsl = bsl;
	}

	public BSkipSpan(BlockFile bf, BSkipList bsl, int spanPage, Serializer key, Serializer val) throws IOException {
		this.bf = bf;
		this.bsl = bsl;
		BSkipSpan.load(this, bf, bsl, spanPage, key, val);
		this.next = null;
		this.prev = null;

		BSkipSpan bss = this;
		int np = nextPage;
		while(np != 0) {
			BSkipSpan temp = bsl.spanHash.get(Integer.valueOf(np));
			if(temp != null) {
				bss.next = temp;
				break;
			}
			bss.next = new BSkipSpan(bf, bsl);
			bss.next.next = null;
			bss.next.prev = bss;
			bss = (BSkipSpan) bss.next;
			
			BSkipSpan.load(bss, bf, bsl, np, key, val);
			np = bss.nextPage;
		}

		bss = this;
		np = prevPage;
		while(np != 0) {
			BSkipSpan temp = bsl.spanHash.get(Integer.valueOf(np));
			if(temp != null) {
				bss.next = temp;
				break;
			}
			bss.prev = new BSkipSpan(bf, bsl);
			bss.prev.next = bss;
			bss.prev.prev = null;
			bss = (BSkipSpan) bss.prev;
			
			BSkipSpan.load(bss, bf, bsl, np, key, val);
			np = bss.prevPage;
		}
	}

	@Override
	public String toString() {
		String rv = "BSS page: " + page + " key: \"" + firstKey() + '"';
		if (isKilled)
			rv += " KILLED";
		return rv;
	}
}
