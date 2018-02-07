/*
 * Created on Nov 15, 2004
 * 
 *  This file is part of susimail project, see http://susi.i2p/
 *  
 *  Copyright (C) 2004-2005  <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *  
 * $Revision: 1.2 $
 */
package i2p.susi.util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.data.DataHelper;

/**
 * Input only for constant data, initialized from a byte array.
 * See MemoryBuffer for read/write.
 *
 * @author susi
 */
public class ReadBuffer implements Buffer {

	public final byte content[];
	public final int length, offset;
	
	public ReadBuffer(byte[] content, int offset, int length) {
		this.content = content;
		this.offset = offset;
		this.length = length;
	}

	/**
	 * @return new ByteArrayInputStream over the content
	 * @since 0.9.34
	 */
	public InputStream getInputStream() {
		return new ByteArrayInputStream(content, offset, length);
	}

	/**
	 * @throws IllegalStateException always
	 * @since 0.9.34
	 */
	public OutputStream getOutputStream() {
		throw new IllegalStateException();
	}

	/**
	 * Does nothing
	 * @since 0.9.34
	 */
	public void readComplete(boolean success) {}

	/**
	 * Does nothing
	 * @since 0.9.34
	 */
	public void writeComplete(boolean success) {}

	/**
	 * Always valid
	 */
	public int getLength() {
		return length;
	}

	/**
	 * Always valid
	 */
	public int getOffset() {
		return offset;
	}

	@Override
	public String toString()
	{
		return content != null ? DataHelper.getUTF8(content, offset, length) : "";
	}
}
