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

import java.io.IOException;

import net.metanotion.io.RandomAccessInterface;

/**
 * On-disk format:
 *<pre>
 *    Magic number (long)
 *    next freelist block page (unsigned int)
 *    size (unsigned int)
 *    that many free pages (unsigned ints)
 *</pre>
 *
 * Always fits on one page.
 *
 * Free page format:
 *<pre>
 *    Magic number (long)
 *</pre>
 */
class FreeListBlock {
	private static final long MAGIC = 0x2366724c69737423l;  // "#frList#"
	private static final long MAGIC_FREE = 0x7e2146524545217el;  // "~!FREE!~"
	private static final int HEADER_LEN = 16;
	private static final int MAX_SIZE = (BlockFile.PAGESIZE - HEADER_LEN) / 4;

	public final int page;
	private int nextPage;
	private int len;
	private final int[] branches;
	private final RandomAccessInterface file;

	public FreeListBlock(RandomAccessInterface file, int startPage) throws IOException {
		this.file = file;
		this.page = startPage;
		BlockFile.pageSeek(file, startPage);
		long magic = file.readLong();
		if (magic != MAGIC)
			throw new IOException("Bad freelist magic number 0x" + Long.toHexString(magic) + " on page " + startPage);
		nextPage = file.readUnsignedInt();
		len = file.readUnsignedInt();
		if (len > MAX_SIZE)
			throw new IOException("Bad freelist size " + len);
		branches = new int[MAX_SIZE];
		if(len > 0) {
			int good = 0;
			for(int i=0;i<len;i++) {
				int fpg = file.readInt();
				if (fpg > BlockFile.METAINDEX_PAGE)
					branches[good++] = fpg;
			}
			if (good != len) {
				BlockFile.log.error((len - good) + " bad pages in " + this);
				len = good;
				writeBlock();
			}
		}
	}

	public void writeBlock() throws IOException {
		BlockFile.pageSeek(file, page);
		file.writeLong(MAGIC);
		file.writeInt(nextPage);
		file.writeInt(len);
		for(int i=0;i<len;i++) { file.writeInt(branches[i]); }
	}

	/**
	 *  Write the length only
	 */
	private void writeLen() throws IOException {
		BlockFile.pageSeek(file, page);
		file.skipBytes(12);
		file.writeInt(len);
	}

	public int getNextPage() {
		return nextPage;
	}

	/**
	 *  Set and write the next page only
	 */
	public void setNextPage(int nxt) throws IOException {
		nextPage = nxt;
		BlockFile.pageSeek(file, page);
		file.skipBytes(8);
		file.writeInt(nxt);
	}

	/**
	 *  Write the length and new page only
	 */
	private void writeFreePage() throws IOException {
		BlockFile.pageSeek(file, page);
		file.skipBytes(12);
		file.writeInt(len);
		if (len > 1)
			file.skipBytes((len - 1) * 4);
		file.writeInt(branches[len - 1]);
	}

	public boolean isEmpty() {
		return len <= 0;
	}

	public boolean isFull() {
		return len >= MAX_SIZE;
	}

	/**
	 *  Adds free page and writes new len to disk
	 *  @throws IllegalStateException if full
	 */
	public void addPage(int freePage) throws IOException {
		if (len >= MAX_SIZE)
			throw new IllegalStateException("full");
		if (getMagic(freePage) == MAGIC_FREE) {
			BlockFile.log.error("Double free page " + freePage, new Exception());
			return;
		}
		branches[len++] = freePage;
		markFree(freePage);
		writeFreePage();
	}

	/**
	 *  Takes next page and writes new len to disk
	 *  @throws IllegalStateException if empty
	 */
	public int takePage() throws IOException {
		if (len <= 0)
			throw new IllegalStateException("empty");
		len--;
		writeLen();
		int rv = branches[len];
		if (rv <= BlockFile.METAINDEX_PAGE)
			// shouldn't happen
			throw new IOException("Bad free page " + rv);
		long magic = getMagic(rv);
		if (magic != MAGIC_FREE)
			// TODO keep trying until empty
			throw new IOException("Bad free page magic number 0x" + Long.toHexString(magic) + " on page " + rv);
		return rv;
	}

	private void markFree(int freePage) throws IOException {
		BlockFile.pageSeek(file, freePage);
		file.writeLong(MAGIC_FREE);
	}

	private long getMagic(int freePage) throws IOException {
		BlockFile.pageSeek(file, freePage);
		long magic = file.readLong();
		return magic;
	}

	public static void initPage(RandomAccessInterface file, int page) throws IOException {
		BlockFile.pageSeek(file, page);
		file.writeLong(MAGIC);
		file.writeInt(0);
		file.writeInt(0);
	}

	@Override
	public String toString() {
		return "FLB with " + len + " / " + MAX_SIZE + " page " + page + " next page " + nextPage;
	}
}
