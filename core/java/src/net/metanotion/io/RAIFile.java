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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

public class RAIFile implements RandomAccessInterface, DataInput, DataOutput {
	private File f;
	private RandomAccessFile delegate;
	private final boolean r, w;

	public RAIFile(RandomAccessFile file) throws FileNotFoundException {
		this.f = null;
		this.delegate = file;
		this.r = true;
		// fake, we don't really know
		this.w = true;
	}

	/** @param read must be true */
	public RAIFile(File file, boolean read, boolean write) throws FileNotFoundException {
		this.f = file;
		this.r = read;
		this.w = write;
		String mode = "";
		if(this.r) { mode += "r"; }
		if(this.w) { mode += "w"; }
		this.delegate = new RandomAccessFile(file, mode);
	}

	/**
	 *  I2P is the file writable?
	 *  Only valid if the File constructor was used, not the RAF constructor
	 *  @since 0.8.8
	 */
	public boolean canWrite() {
		return this.w;
	}

	/**
	 *  @since 0.8.8
	 */
	@Override
	public String toString() {
		if (this.f != null)
			return this.f.getAbsolutePath();
		return this.delegate.toString();
	}

	public long getFilePointer()		throws IOException { return delegate.getFilePointer(); }
	public long length()				throws IOException { return delegate.length(); }
	public int read()					throws IOException { return delegate.read(); }
	public int read(byte[] b)			throws IOException { return delegate.read(b); }
	public int read(byte[] b, int off, int len) throws IOException { return delegate.read(b,off,len); }
	public void seek(long pos)			throws IOException { delegate.seek(pos); }
	public void setLength(long newLength) throws IOException { delegate.setLength(newLength); }

	// Closeable Methods
	// TODO May need to change.
	public void close()					throws IOException { delegate.close(); }

	// DataInput Methods
	public boolean readBoolean()		throws IOException { return delegate.readBoolean(); }
	public byte readByte()				throws IOException { return delegate.readByte(); }
	public char readChar()				throws IOException { return delegate.readChar(); }
	public double readDouble()			throws IOException { return delegate.readDouble(); }
	public float readFloat()			throws IOException { return delegate.readFloat(); }
	public void readFully(byte[] b)		throws IOException { delegate.readFully(b); }
	public void readFully(byte[] b, int off, int len) throws IOException { delegate.readFully(b,off,len); }
	public int readInt()				throws IOException { return delegate.readInt(); }
	public String readLine()			throws IOException { return delegate.readLine(); }
	public long readLong()				throws IOException { return delegate.readLong(); }
	public short readShort()			throws IOException { return delegate.readShort(); }
	public int readUnsignedByte()		throws IOException { return delegate.readUnsignedByte(); }
	public int readUnsignedShort()		throws IOException { return delegate.readUnsignedShort(); }

	/**
	 *  I2P
	 *  @throws IOException if the read value is negative
	 */
	public int readUnsignedInt()  throws IOException {
		int rv = readInt();
		if (rv < 0)
			throw new IOException("Negative value for unsigned int: " + rv);
		return rv;
	}

	/** Read a UTF encoded string
	 	I would delegate here. But Java's read/writeUTF combo suck.
	 	A signed 2 byte length is not enough.
	 	This reads a 4 byte length.
	 	The upper byte MUST be zero, if its not, then its not this method and has used an
	 	extensible length encoding.
	 	This is followed by the bytes of the UTF encoded string, as
	 	returned by String.getBytes("UTF-8");
	*/
	public String readUTF()				throws IOException {
		int len = delegate.readInt();
		if((len < 0) || (len >= 16777216)) { throw new IOException("Bad Length Encoding"); }
		byte[] bytes = new byte[len];
		int l = delegate.read(bytes);
		if(l==-1) { throw new IOException("EOF while reading String"); }
		String s = new String(bytes, "UTF-8");
		return s;
	}

	public int skipBytes(int n)			throws IOException { return delegate.skipBytes(n); }

	// DataOutput Methods
	public void write(int b)			throws IOException { delegate.write(b); }
	public void write(byte[] b)			throws IOException { delegate.write(b); }
	public void write(byte[] b, int off, int len) throws IOException { delegate.write(b,off,len); }
	public void writeBoolean(boolean v)	throws IOException { delegate.writeBoolean(v); }
	public void writeByte(int v)		throws IOException { delegate.writeByte(v); }
	public void writeShort(int v)		throws IOException { delegate.writeShort(v); }
	public void writeChar(int v)		throws IOException { delegate.writeChar(v); }
	public void writeInt(int v)			throws IOException {  delegate.writeInt(v); }
	public void writeLong(long v)		throws IOException {  delegate.writeLong(v); }
	public void writeFloat(float v)		throws IOException { delegate.writeFloat(v); }
	public void writeDouble(double v)	throws IOException { delegate.writeDouble(v); }
	public void writeBytes(String s)	throws IOException { delegate.writeBytes(s); }
	public void writeChars(String s)	throws IOException { delegate.writeChars(s); }

	/** Write a UTF encoded string
	 	I would delegate here. But Java's read/writeUTF combo suck.
	 	A signed 2 byte length is not enough.
	 	This writes a 4 byte length.
	 	The upper byte MUST be zero, if its not, then its not this method and has used an
	 	extensible length encoding.
	 	This is followed by the bytes of the UTF encoded string, as
	 	returned by String.getBytes("UTF-8");
	*/
	public void writeUTF(String str)	throws IOException {
		byte[] string = str.getBytes("UTF-8");
		if(string.length >= 16777216) { throw new IOException("String to long for encoding type"); }
		delegate.writeInt(string.length);
		delegate.write(string);
	}
}
