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
import net.metanotion.io.block.BlockFile;
import net.metanotion.util.skiplist.SkipList;
import net.metanotion.util.skiplist.SkipLevels;
import net.metanotion.util.skiplist.SkipSpan;

import net.i2p.util.Log;

/**
 * On-disk format:
 *    Magic number (long)
 *    max height (unsigned short)
 *    non-null height (unsigned short)
 *    span page (unsigned int)
 *    height number of level pages (unsigned ints)
 *
 * Always fits on one page.
 */
public class BSkipLevels extends SkipLevels {
	private static final long MAGIC = 0x42534c6576656c73l;  // "BSLevels"
	static final int HEADER_LEN = 16;
	public final int levelPage;
	public final int spanPage;
	public final BlockFile bf;
	private boolean isKilled;

	public BSkipLevels(BlockFile bf, int levelPage, BSkipList bsl) throws IOException {
		this.levelPage = levelPage;
		this.bf = bf;

		BlockFile.pageSeek(bf.file, levelPage);
		long magic = bf.file.readLong();
		if (magic != MAGIC)
			throw new IOException("Bad SkipLevels magic number 0x" + Long.toHexString(magic) + " on page " + levelPage);

		bsl.levelHash.put(new Integer(this.levelPage), this);

		int maxLen = bf.file.readUnsignedShort();
		int nonNull = bf.file.readUnsignedShort();
		if(maxLen < 1 || maxLen > MAX_SIZE || nonNull > maxLen)
			throw new IOException("Invalid Level Skip size " + nonNull + " / " + maxLen);
		spanPage = bf.file.readUnsignedInt();
		bottom = (BSkipSpan) bsl.spanHash.get(new Integer(spanPage));

		this.levels = new BSkipLevels[maxLen];
		if (BlockFile.log.shouldLog(Log.DEBUG))
			BlockFile.log.debug("Reading New BSkipLevels with " + nonNull + " / " + maxLen + " valid levels page " + levelPage);
		// We have to read now because new BSkipLevels() will move the file pointer
		int[] lps = new int[nonNull];
		for(int i = 0; i < nonNull; i++) {
			lps[i] = bf.file.readUnsignedInt();
		}

		for(int i = 0; i < nonNull; i++) {
			int lp = lps[i];
			if(lp != 0) {
				levels[i] = (BSkipLevels) bsl.levelHash.get(new Integer(lp));
				if(levels[i] == null) {
					levels[i] = new BSkipLevels(bf, lp, bsl);
					bsl.levelHash.put(new Integer(lp), levels[i]);
				} else {
				}
			} else {
				if (BlockFile.log.shouldLog(Log.WARN))
					BlockFile.log.warn("WTF " + this + " i = " + i + " of " + nonNull + " / " + maxLen + " valid levels but page is zero");
				levels[i] = null;
			}
		}
	}

	public static void init(BlockFile bf, int page, int spanPage, int maxHeight) throws IOException {
		BlockFile.pageSeek(bf.file, page);
		bf.file.writeLong(MAGIC);
		bf.file.writeShort((short) maxHeight);
		bf.file.writeShort(0);
		bf.file.writeInt(spanPage);
	}

	public void flush() {
		if (isKilled) {
			BlockFile.log.error("Already killed!! " + this, new Exception());
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

	public void killInstance() {
		if (isKilled) {
			BlockFile.log.error("Already killed!! " + this, new Exception());
			return;
		}
		if (BlockFile.log.shouldLog(Log.INFO))
			BlockFile.log.info("Killing " + this);
		isKilled = true;
		bf.freePage(levelPage);
	}

	public SkipLevels newInstance(int levels, SkipSpan ss, SkipList sl) {
		try {
			BSkipSpan bss = (BSkipSpan) ss;
			BSkipList bsl = (BSkipList) sl;
			int page = bf.allocPage();
			BSkipLevels.init(bf, page, bss.page, levels);
			BlockFile.log.info("New BSkipLevels height " + levels + " page " + page);
			return new BSkipLevels(bf, page, bsl);
		} catch (IOException ioe) { throw new RuntimeException("Error creating database page", ioe); }
	}

	@Override
	public void blvlck(boolean fix, int width) {
		BlockFile.log.warn("    Skip level at width " + width);
		BlockFile.log.warn("        levels " + this.levels.length);
		BlockFile.log.warn("        first key " + this.key());
		BlockFile.log.warn("        spanPage " + this.spanPage);
		BlockFile.log.warn("        levelPage " + this.levelPage);
		for (int i = levels.length - 1; i >= 0; i--) {
			if (levels[i] != null) {
				BlockFile.log.warn("                level " + i + " -> " + levels[i].key() + " ");
			} else {
				BlockFile.log.warn("                level " + i + " empty");
			}
		}
		if (levels[0] != null)
			levels[0].blvlck(fix, width + 1);
	}

	@Override
	public String toString() {
		String rv = "BSL height: " + levels.length + " page: " + levelPage + " span: " + bottom;
		if (isKilled)
			rv += " KILLED";
		return rv;
	}
}
