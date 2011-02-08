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

public class FreeListBlock {
	public int page;
	public int nextPage;
	public int len;
	public int[] branches = null;
	public RandomAccessInterface file;

	public FreeListBlock(RandomAccessInterface file, int startPage) throws IOException {
		this.file = file;
		this.page = startPage;
		BlockFile.pageSeek(file, startPage);
		nextPage = file.readInt();
		len = file.readInt();
		if(len > 0) {
			branches = new int[len];
			for(int i=0;i<len;i++) {
				branches[i] = file.readInt();
			}
		}
	}

	public void writeBlock() throws IOException {
		BlockFile.pageSeek(file, page);
		file.writeInt(nextPage);
		if(len > 0) {
			file.writeInt(len);
			for(int i=0;i<len;i++) { file.writeInt(branches[i]); }
		} else {
			file.writeInt(0);
		}
	}

	public boolean isFull() {
		int cells = (int) ((BlockFile.PAGESIZE - 8) / 4);
		if(cells - len > 0) { return false; }
		return true;
	}

	public void addPage(int page) {
		int[] t = new int[len + 1];
		if(len > 0) {
			for(int i=0;i<len;i++) { t[i] = branches[i]; }
		}
		t[len] = page;
		len++;
		branches = t;
	}

	public static void initPage(RandomAccessInterface file, int page) throws IOException {
		BlockFile.pageSeek(file, page);
		file.writeInt(0);
		file.writeInt(0);
	}
}

