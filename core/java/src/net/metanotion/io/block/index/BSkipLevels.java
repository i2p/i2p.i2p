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
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

import net.metanotion.io.RandomAccessInterface;
import net.metanotion.io.block.BlockFile;
import net.metanotion.util.skiplist.SkipList;
import net.metanotion.util.skiplist.SkipLevels;
import net.metanotion.util.skiplist.SkipSpan;

import net.i2p.util.Log;

/**
 * On-disk format:
 *<pre>
 *    Magic number (long)
 *    max height (unsigned short)
 *    non-null height (unsigned short)
 *    span page (unsigned int)
 *    height number of level pages (unsigned ints)
 *</pre>
 *
 * Always fits on one page.
 */
public class BSkipLevels extends SkipLevels {
	private static final long MAGIC = 0x42534c6576656c73l;  // "BSLevels"
	static final int HEADER_LEN = 16;
	public final int levelPage;
	public final int spanPage;
	public final BlockFile bf;
	private final BSkipList bsl;
	private boolean isKilled;

	public BSkipLevels(BlockFile bf, int levelPage, BSkipList bsl) throws IOException {
		this.levelPage = levelPage;
		this.bf = bf;
		this.bsl = bsl;

		BlockFile.pageSeek(bf.file, levelPage);
		long magic = bf.file.readLong();
		if (magic != MAGIC)
			throw new IOException("Bad SkipLevels magic number 0x" + Long.toHexString(magic) + " on page " + levelPage);

		bsl.levelHash.put(Integer.valueOf(this.levelPage), this);

		int maxLen = bf.file.readUnsignedShort();
		int nonNull = bf.file.readUnsignedShort();
		if(maxLen < 1 || maxLen > MAX_SIZE || nonNull > maxLen)
			throw new IOException("Invalid Level Skip size " + nonNull + " / " + maxLen);
		spanPage = bf.file.readUnsignedInt();
		bottom = bsl.spanHash.get(Integer.valueOf(spanPage));
		if (bottom == null) {
			// FIXME recover better?
			bf.log.error("No span found in cache???");
			throw new IOException("No span found in cache???");
		}

		this.levels = new BSkipLevels[maxLen];
		if (bf.log.shouldLog(Log.DEBUG))
			bf.log.debug("Reading New BSkipLevels with " + nonNull + " / " + maxLen + " valid levels page " + levelPage);
		// We have to read now because new BSkipLevels() will move the file pointer
		int[] lps = new int[nonNull];
		for(int i = 0; i < nonNull; i++) {
			lps[i] = bf.file.readUnsignedInt();
		}

		boolean fail = false;
		for(int i = 0; i < nonNull; i++) {
			int lp = lps[i];
			if(lp != 0) {
				levels[i] = bsl.levelHash.get(Integer.valueOf(lp));
				if(levels[i] == null) {
					try {
						// FIXME this will explode the stack if too big
						// Redo this without recursion?
						// Lots of recursion in super to be fixed also...
						levels[i] = new BSkipLevels(bf, lp, bsl);
						bsl.levelHash.put(Integer.valueOf(lp), levels[i]);
					} catch (IOException ioe) {
						bf.log.error("Corrupt database, bad level " + i +
						                    " at page " + lp, ioe);
						levels[i] = null;
						fail = true;
						continue;
					}
				}
				Comparable ourKey = key();
				Comparable nextKey = levels[i].key();
				if (ourKey != null && nextKey != null &&
				    ourKey.compareTo(nextKey) >= 0) {
					bf.log.warn("Corrupt database, level out of order " + this +
					                    ' ' + print() +
					                    " i = " + i + ' ' + levels[i]);
					// This will be fixed in blvlfix() via BlockFile.getIndex()
					//levels[i] = null;
					//fail = true;
				}
				// TODO also check that the level[] array is not out-of-order
			} else {
				if (bf.log.shouldLog(Log.WARN))
					bf.log.warn("WTF " + this + " i = " + i + " of " + nonNull + " / " + maxLen + " valid levels but page is zero");
				levels[i] = null;
				fail = true;
			}
		}
		if (fail && bf.file.canWrite()) {
			// corruption is actually fixed in blvlfix() via BlockFile.getIndex()
			// after instantiation is complete
			bf.log.error("Repairing corruption of " + this +
			                    ' ' + print());
			flush();
			// if the removed levels have no other links to them, they and their data
			// are lost forever, but the alternative is infinite loops / stack overflows
			// in SkipSpan.
		}
	}

	public static void init(BlockFile bf, int page, int spanPage, int maxHeight) throws IOException {
		BlockFile.pageSeek(bf.file, page);
		bf.file.writeLong(MAGIC);
		bf.file.writeShort((short) maxHeight);
		bf.file.writeShort(0);
		bf.file.writeInt(spanPage);
	}

	@Override
	public void flush() {
		if (isKilled) {
			bf.log.error("Already killed!! " + this, new Exception());
			return;
		}
		try {
			BlockFile.pageSeek(bf.file, levelPage);
			bf.file.writeLong(MAGIC);
			bf.file.writeShort((short) levels.length);
			int i = 0;
			for( ; i < levels.length; i++) {
				 if(levels[i] == null)
					break;
			}
			bf.file.writeShort(i);
			bf.file.writeInt(((BSkipSpan) bottom).page);
			for(int j = 0; j < i; j++) {
				bf.file.writeInt(((BSkipLevels) levels[j]).levelPage);
			}
		} catch (IOException ioe) { throw new RuntimeException("Error writing to database", ioe); }
	}

	@Override
	public void killInstance() {
		if (isKilled) {
			bf.log.error("Already killed!! " + this, new Exception());
			return;
		}
		if (bf.log.shouldLog(Log.DEBUG))
			bf.log.debug("Killing " + this + ' ' + print(), new Exception());
		isKilled = true;
		bsl.levelHash.remove(Integer.valueOf(levelPage));
		bf.freePage(levelPage);
	}

	@Override
	public SkipLevels newInstance(int levels, SkipSpan ss, SkipList sl) {
		try {
			BSkipSpan bss = (BSkipSpan) ss;
			BSkipList bsl = (BSkipList) sl;
			int page = bf.allocPage();
			BSkipLevels.init(bf, page, bss.page, levels);
			if (bf.log.shouldLog(Log.DEBUG))
				bf.log.debug("New BSkipLevels height " + levels + " page " + page);
			return new BSkipLevels(bf, page, bsl);
		} catch (IOException ioe) { throw new RuntimeException("Error creating database page", ioe); }
	}

	/**
	 *  Run an integrity check on the skiplevels from the first,
	 *  or just fix it if fix == true.
	 *  Only call from the first level.
	 *  @return true if the levels were modified.
	 */
	@Override
	public boolean blvlck(boolean fix) {
		if (fix)
			return blvlfix();
		return blvlck(fix, 0, null);
	}

	/**
	 *  Fix the levels.
	 *  Only call from the first level.
	 *  Primarily to fix nulls in levels caused by previous SkipLevels bug.
	 *  This should handle dups and loops and out-of-order levels too,
	 *  but those may cause problems before this in the constructor.
	 *  This is fast enough to call every time the skiplist is opened.
	 *  @return true if the levels were modified.
	 *  @since 0.8.8
	 */
	private boolean blvlfix() {
		TreeSet<SkipLevels> lvls = new TreeSet(new LevelComparator());
		if (bf.log.shouldLog(Log.DEBUG))
			bf.log.debug("Starting level search");
		getAllLevels(this, lvls);
		if (bf.log.shouldLog(Log.DEBUG))
			bf.log.debug("Finished level search, found " + lvls.size() + " levels");
		if (!this.equals(lvls.last())) {
			bf.log.error("First level is out of order! " + print());
			// TODO switch stack and other fields for the skiplist - hard to test
		}
		// traverse the levels, back-to-front
		boolean rv = false;
		SkipLevels after = null;
		for (SkipLevels lv : lvls) {
			boolean modified = false;
			if (bf.log.shouldLog(Log.DEBUG))
				bf.log.debug("Checking " + lv.print());
			if (after != null) {
				int min = Math.min(after.levels.length, lv.levels.length);
				for (int i = 0; i < min; i++) {
					SkipLevels cur = lv.levels[i];
					if (cur != after) {
						if (cur != null)
							bf.log.warn("Level " + i + " was wrong, fixing for " + lv.print());
						else
							bf.log.warn("Level " + i + " was null, fixing for " + lv.print());
						lv.levels[i] = after;
						modified = true;
					}
				}
			} else {
				// last one
				for (int i = 0; i < lv.levels.length; i++) {
					if (lv.levels[i] != null) {
						lv.levels[i] = null;
						bf.log.warn("Last level " + i + " was non-null, fixing for " + lv.print());
						modified = true;
					}
				}
			}
			if (modified) {
				lv.flush();
				rv = true;
			}
			after = lv;
		}
		if (bf.log.shouldLog(Log.INFO))
			bf.log.info("Checked " + lvls.size() + " levels");
		return rv;
	}

	/**
	 *  Breadth-first, sortof
	 *  We assume everything is findable from the root level
	 *  @param l non-null
	 *  @param lvlSet out parameter, the result
	 *  @since 0.8.8
	 */
	private void getAllLevels(SkipLevels l, Set lvlSet) {
		if (bf.log.shouldLog(Log.DEBUG))
			bf.log.debug("GAL " + l.print());
		// Do level 0 without recursion, on the assumption everything is findable
		// from the root
		SkipLevels cur = l;
		while (cur != null && lvlSet.add(cur)) {
			if (bf.log.shouldLog(Log.DEBUG))
				bf.log.debug("Adding " + cur.print());
			if (!cur.equals(this) && cur.key() == null && bf.log.shouldLog(Log.WARN))
				bf.log.debug("Null KEY!!! " + cur.print());
			cur = cur.levels[0];
		}
		// If there were no nulls at level 0 in the middle,
		// i.e. there are no problems, this won't find anything
		for (int i = 1; i < l.levels.length; i++) {
			SkipLevels lv = l.levels[i];
			if (lv != null && !lvlSet.contains(lv))
				getAllLevels(lv, lvlSet);
		}
	}

	/**
	 *  For sorting levels in blvlfix()
         *  Sorts in REVERSE order.
	 *  @since 0.8.8
	 */
	private static class LevelComparator implements Comparator<SkipLevels> {
		public int compare(SkipLevels l, SkipLevels r) {
			Comparable lk = l.key();
			Comparable rk = r.key();
			if (lk == null && rk == null)
				return 0;
			if (lk == null)
				return 1;
			if (rk == null)
				return -1;
			// reverse!
			return rk.compareTo(lk);
		}
	}

	/*
	 *  Recursively walk through the levels at level 0
	 *  This needs work.
	 */
	@Override
	public boolean blvlck(boolean fix, int width, SkipLevels[] prevLevels) {
		bf.log.warn("    Skip level at width " + width);
		bf.log.warn("        levels " + this.levels.length);
		bf.log.warn("        first key " + this.key());
		bf.log.warn("        spanPage " + this.spanPage);
		bf.log.warn("        levelPage " + this.levelPage);
		SkipLevels higher = null;
		for (int i = levels.length - 1; i >= 0; i--) {
			if (levels[i] != null) {
				bf.log.info("                level " + i + " -> " + levels[i].key() + " ");
				if (higher != null) {
					if (higher.key().compareTo(key()) < 0)
						bf.log.warn("                Higher level has lower key " + higher.key());
				}
			} else {
				bf.log.info("                level " + i + " empty");
				if (higher != null)
					bf.log.warn("                Higher level is not empty, key is " + higher.key());
			}
		}
		if (prevLevels != null) {
			int min = Math.min(prevLevels.length, levels.length);
			for (int i = 0; i < min; i++) {
				if (prevLevels[i] == this) {
					prevLevels[i] = levels[i];
				} else if (prevLevels[i] != null) {
					// skipping over us
					bf.log.warn("                Previous levels is non-null " + prevLevels[i].key() + " but not pointing to us at level " + i);
					// replace so we only get one error
					prevLevels[i] = levels[i];
				} else {
					// dead end in the middle
					if (levels[i] != null) {
						bf.log.warn("                Previous levels is null but we are non-null " + levels[i].key() + " at level " + i);
						// replace so we only get one error
						prevLevels[i] = levels[i];
					}
				}
			}
		} else {
			prevLevels = new SkipLevels[levels.length];
			System.arraycopy(levels, 0, prevLevels, 0, levels.length);
		}
		if (levels[0] != null)
			levels[0].blvlck(fix, width + 1, prevLevels);
		return false;
	}

	@Override
	public String toString() {
		String rv = "BSL height: " + levels.length + " page: " + levelPage + " span: " + bottom;
		if (isKilled)
			rv += " KILLED";
		return rv;
	}
}
