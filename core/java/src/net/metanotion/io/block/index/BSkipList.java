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
import java.util.HashMap;
import java.util.Random;

import net.metanotion.io.RandomAccessInterface;
import net.metanotion.io.Serializer;
import net.metanotion.io.block.BlockFile;
import net.metanotion.util.skiplist.*;

import net.i2p.util.Log;

/**
 * On-disk format:
 *<pre>
 *    Magic number (long)
 *    first span page (unsigned int)
 *    first level page (unsigned int)
 *    size (unsigned int)
 *    spans (unsigned int)
 *    levels (unsigned int)
 *</pre>
 *
 * Always fits on one page.
 */
public class BSkipList extends SkipList {
	private static final long MAGIC = 0x536b69704c697374l;  // "SkipList"
	public int firstSpanPage = 0;
	public int firstLevelPage = 0;
	public int skipPage = 0;
	public final BlockFile bf;
	private boolean isClosed;

	final HashMap<Integer, BSkipSpan> spanHash = new HashMap();
	final HashMap<Integer, SkipLevels> levelHash = new HashMap();

	private final boolean fileOnly;

	public BSkipList(int spanSize, BlockFile bf, int skipPage, Serializer key, Serializer val) throws IOException {
		this(spanSize, bf, skipPage, key, val, false);
	}

	public BSkipList(int spanSize, BlockFile bf, int skipPage, Serializer key, Serializer val, boolean fileOnly) throws IOException {
		if(spanSize < 1) { throw new RuntimeException("Span size too small"); }

		this.skipPage = skipPage;
		this.bf = bf;

		BlockFile.pageSeek(bf.file, skipPage);
		long magic = bf.file.readLong();
		if (magic != MAGIC)
			throw new IOException("Bad SkipList magic number 0x" + Long.toHexString(magic) + " on page " + skipPage);
		firstSpanPage = bf.file.readUnsignedInt();
		firstLevelPage = bf.file.readUnsignedInt();
		size = bf.file.readUnsignedInt();
		int spans = bf.file.readInt();
		int levelCount = bf.file.readInt();
		//System.out.println(size + " " + spans); 

		this.fileOnly = fileOnly;
		if (fileOnly)
			first = new IBSkipSpan(bf, this, firstSpanPage, key, val);
		else
			first = new BSkipSpan(bf, this, firstSpanPage, key, val);
		stack = new BSkipLevels(bf, firstLevelPage, this);
		int total = 0;
		for (BSkipSpan ss : spanHash.values()) {
			total += ss.nKeys;
		}
		if (BlockFile.log.shouldLog(Log.DEBUG))
			BlockFile.log.debug("Loaded " + this + " cached " + levelHash.size() + " levels and " + spanHash.size() + " spans with " + total + " entries");
		if (bf.file.canWrite() &&
		    (levelCount != levelHash.size() || spans != spanHash.size() || size != total)) {
			if (BlockFile.log.shouldLog(Log.WARN))
				BlockFile.log.warn("On-disk counts were " + levelCount + " / " + spans + " / " +  size + ", correcting");
			size = total;
			flush();
		}
		//rng = new Random(System.currentTimeMillis());
	}

	public void close() {
		//System.out.println("Closing index " + size + " and " + spans);
		flush();
		spanHash.clear();
		levelHash.clear();
		isClosed = true;
	}

	@Override
	public void flush() {
                if (!bf.file.canWrite())
                    return;
		if (isClosed) {
			BlockFile.log.error("Already closed!! " + this, new Exception());
			return;
		}
		try {
			BlockFile.pageSeek(bf.file, skipPage);
			bf.file.writeLong(MAGIC);
			bf.file.writeInt(firstSpanPage);
			bf.file.writeInt(firstLevelPage);
			bf.file.writeInt(Math.max(0, size));
			bf.file.writeInt(spanHash.size());
			bf.file.writeInt(levelHash.size());
			
		} catch (IOException ioe) { throw new RuntimeException("Error writing to database", ioe); }
	}

	/** must be open (do not call close() first) */
	public void delete() throws IOException {
		if (isClosed) {
			BlockFile.log.error("Already closed!! " + this, new Exception());
			return;
		}
		SkipLevels curLevel = stack;
		while(curLevel != null) {
			SkipLevels nextLevel = curLevel.levels[0];
			curLevel.killInstance();
			curLevel = nextLevel;
		}
		stack.killInstance();

		SkipSpan curSpan = first;
		while(curSpan != null) {
			SkipSpan nextSpan = curSpan.next;
			curSpan.killInstance();
			curSpan = nextSpan;
		}

		bf.freePage(skipPage);
		spanHash.clear();
		levelHash.clear();
		isClosed = true;
	}

	public static void init(BlockFile bf, int page, int spanSize) throws IOException {
		int firstSpan = bf.allocPage();
		int firstLevel = bf.allocPage();
		BlockFile.pageSeek(bf.file, page);
		bf.file.writeLong(MAGIC);
		bf.file.writeInt(firstSpan);
		bf.file.writeInt(firstLevel);
		bf.file.writeInt(0);
		bf.file.writeInt(1);
		bf.file.writeInt(1);
		BSkipSpan.init(bf, firstSpan, spanSize);
		BSkipLevels.init(bf, firstLevel, firstSpan, 4);
	}

	/**
	 *  @return log2(span count), minimum 4
	 */
	@Override
	public int maxLevels() {
		int hob = 0;
		int s = spanHash.size();
		while(s > 0) {
			hob++;
			s /= P;
		}
		int max = Math.max(hob, super.maxLevels());
		// 252
		//int cells = (BlockFile.PAGESIZE - BSkipLevels.HEADER_LEN) / 4;
		return Math.min(BSkipLevels.MAX_SIZE, max);
	}

	@Override
	public SkipIterator iterator() {
		if (!this.fileOnly)
			return super.iterator();
		return new IBSkipIterator(first, 0);
	}

	@Override
	public SkipIterator min() {
		return iterator();
	}

	@Override
	public SkipIterator max() {
		if (!this.fileOnly)
			return super.max();
		SkipSpan ss = stack.getEnd();
		return new IBSkipIterator(ss, ss.nKeys - 1);
	}

	@Override
	public SkipIterator find(Comparable key) {
		if (!this.fileOnly)
			return super.find(key);
		int[] search = new int[1];
		SkipSpan ss = stack.getSpan(stack.levels.length - 1, key, search);
		if(search[0] < 0) { search[0] = -1 * (search[0] + 1); }
		return new IBSkipIterator(ss, search[0]);
	}

	/**
	 *  Run an integrity check on the skiplist and all the levels in it
	 *  @return true if the levels were modified.
	 */
	public boolean bslck(boolean fix, boolean isMeta) {
		BlockFile.log.info("    size " + this.size);
		BlockFile.log.info("    spans " + this.spanHash.size());
		BlockFile.log.info("    levels " + this.levelHash.size());
		BlockFile.log.info("    skipPage " + this.skipPage);
		BlockFile.log.info("    firstSpanPage " + this.firstSpanPage);
		BlockFile.log.info("    firstLevelPage " + this.firstLevelPage);
		BlockFile.log.info("    maxLevels " + this.maxLevels());
		//printSL();
		//print();
		//BlockFile.log.info("*** Lvlck() ***");
		boolean rv = stack.blvlck(fix);
	     /****
		int items = 0;
		for (SkipIterator iter = this.iterator(); iter.hasNext(); ) {
			String key = (String) iter.nextKey();
			if (isMeta) {
				int sz = ((Integer) iter.next()).intValue();
				BlockFile.log.info("        Item " + key.toString() + " page " + sz);
			} else {
				String cls= iter.next().getClass().getSimpleName();
				BlockFile.log.info("        Item " + key.toString() + " class " + cls);
			}
			items++;
		}
		BlockFile.log.warn("    actual size " + items);
		if (items != this.size)
			BlockFile.log.warn("****** size mismatch, header = " + this.size + " actual = " + items);
              ****/
		return rv;
	}

	@Override
	public String toString() {
		String rv = getClass().getSimpleName() + " page " + skipPage;
		if (isClosed)
			rv += " CLOSED";
		return rv;
	}
}
