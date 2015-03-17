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
package net.metanotion.io;

import java.io.IOException;

public interface RandomAccessInterface {
	public long getFilePointer() throws IOException;
	public long length() throws IOException;
	public int read() throws IOException;
	public int read(byte[] b) throws IOException;
	public int read(byte[] b, int off, int len) throws IOException;
	public void seek(long pos) throws IOException;
	public void setLength(long newLength) throws IOException;

	/**
	 *  I2P is the file writable?
	 *  Only valid if the File constructor was used, not the RAF constructor
	 *  @since 0.8.8
	 */
	public boolean canWrite();

	// Closeable Methods
	public void close() throws IOException;

	// DataInput Methods
	public boolean readBoolean() throws IOException;
	public byte readByte() throws IOException;
	public char readChar() throws IOException;
	public double readDouble() throws IOException;
	public float readFloat() throws IOException;
	public void readFully(byte[] b) throws IOException;
	public void readFully(byte[] b, int off, int len) throws IOException;
	public int readInt() throws IOException;
	public String readLine() throws IOException;
	public long readLong() throws IOException;
	public short readShort() throws IOException;
	public int readUnsignedByte() throws IOException;
	public int readUnsignedShort() throws IOException;
	// I2P
	public int readUnsignedInt() throws IOException;
	public String readUTF() throws IOException;
	public int skipBytes(int n) throws IOException;

	// DataOutput Methods
	public void write(int b) throws IOException;
	public void write(byte[] b) throws IOException;
	public void write(byte[] b, int off, int len) throws IOException;
	public void writeBoolean(boolean v) throws IOException;
	public void writeByte(int v) throws IOException;
	public void writeShort(int v) throws IOException;
	public void writeChar(int v) throws IOException;
	public void writeInt(int v) throws IOException;
	public void writeLong(long v) throws IOException;
	public void writeFloat(float v) throws IOException;
	public void writeDouble(double v) throws IOException;
	public void writeBytes(String s) throws IOException;
	public void writeChars(String s) throws IOException;
	public void writeUTF(String str) throws IOException;
}
