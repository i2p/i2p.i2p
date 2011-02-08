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

public class BSkipList extends SkipList {
	public int firstSpanPage = 0;
	public int firstLevelPage = 0;
	public int skipPage = 0;
	public BlockFile bf;

	public HashMap spanHash = new HashMap();
	public HashMap levelHash = new HashMap();

	protected BSkipList() { }

	public BSkipList(int spanSize, BlockFile bf, int skipPage, Serializer key, Serializer val) throws IOException {
		this(spanSize, bf, skipPage, key, val, false);
	}

	public BSkipList(int spanSize, BlockFile bf, int skipPage, Serializer key, Serializer val, boolean fileOnly) throws IOException {
		if(spanSize < 1) { throw new Error("Span size too small"); }

		this.skipPage = skipPage;
		this.bf = bf;

		BlockFile.pageSeek(bf.file, skipPage);
		firstSpanPage = bf.file.readInt();
		firstLevelPage = bf.file.readInt();
		size = bf.file.readInt();
		spans = bf.file.readInt();
		//System.out.println(size + " " + spans); 

		if (fileOnly)
			first = new IBSkipSpan(bf, this, firstSpanPage, key, val);
		else
			first = new BSkipSpan(bf, this, firstSpanPage, key, val);
		stack = new BSkipLevels(bf, firstLevelPage, this);
		//rng = new Random(System.currentTimeMillis());
	}

	public void close() {
		//System.out.println("Closing index " + size + " and " + spans);
		flush();
		first = null;
		stack = null;
	}

	public void flush() {
		try {
			BlockFile.pageSeek(bf.file, skipPage);
			bf.file.writeInt(firstSpanPage);
			bf.file.writeInt(firstLevelPage);
			bf.file.writeInt(size);
			bf.file.writeInt(spans);
			
		} catch (IOException ioe) { throw new Error(); }
	}

	public void delete() throws IOException {
		SkipLevels curLevel = stack, nextLevel;
		while(curLevel != null) {
			nextLevel = curLevel.levels[0];
			curLevel.killInstance();
			curLevel = nextLevel;
		}

		SkipSpan curSpan = first, nextSpan;
		while(curSpan != null) {
			nextSpan = curSpan.next;
			curSpan.killInstance();
			curSpan = nextSpan;
		}

		bf.freePage(skipPage);
	}

	public static void init(BlockFile bf, int page, int spanSize) throws IOException {
		int firstSpan = bf.allocPage();
		int firstLevel = bf.allocPage();
		BlockFile.pageSeek(bf.file, page);
		bf.file.writeInt(firstSpan);
		bf.file.writeInt(firstLevel);
		bf.file.writeInt(0);
		bf.file.writeInt(1);
		BSkipSpan.init(bf, firstSpan, spanSize);
		BSkipLevels.init(bf, firstLevel, firstSpan, 4);
	}

	public int maxLevels() {
		int max = super.maxLevels();
		int cells = (int) ((BlockFile.PAGESIZE - 8) / 4);
		return (max > cells) ? cells : max;
	}

}
