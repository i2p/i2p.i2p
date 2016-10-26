/*******************************************************************************
 * Copyright (c) 2001-2005 Sasa Markovic and Ciaran Treanor.
 * Copyright (c) 2011 The OpenNMS Group, Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *******************************************************************************/
package org.jrobin.core.jrrd;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import org.jrobin.core.RrdException;

/**
 * This class is a quick hack to read information from an RRD file. Writing
 * to RRD files is not currently supported. As I said, this is a quick hack.
 * Some thought should be put into the overall design of the file IO.
 * <p/>
 * Currently this can read RRD files that were generated on Solaris (Sparc)
 * and Linux (x86).
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision$
 */
public class RRDFile implements Constants {

	boolean bigEndian;
	boolean debug;
	int alignment;
	RandomAccessFile ras;
	byte[] buffer;

	RRDFile(String name) throws IOException, RrdException {
		this(new File(name));
	}

	RRDFile(File file) throws IOException, RrdException {

		ras = new RandomAccessFile(file, "r");
		buffer = new byte[128];

		this.debug = false;
		initDataLayout(file);
	}

	private void initDataLayout(File file) throws IOException, RrdException {

		if (file.exists()) {	// Load the data formats from the file
			int bytes = ras.read(buffer, 0, 24);
			if (bytes < 24) {
				throw new RrdException("Invalid RRD file");
			}

			int index;

			if ((index = indexOf(FLOAT_COOKIE_BIG_ENDIAN, buffer)) != -1) {
				bigEndian = true;
			}
			else if ((index = indexOf(FLOAT_COOKIE_LITTLE_ENDIAN, buffer))
					!= -1) {
				bigEndian = false;
			}
			else {
				throw new RrdException("Invalid RRD file");
			}

			switch (index) {

				case 12:
					alignment = 4;
					break;

				case 16:
					alignment = 8;
					break;

				default :
					throw new RuntimeException("Unsupported architecture - neither 32-bit nor 64-bit, or maybe the file is corrupt");
			}
		}
		else {				// Default to data formats for this hardware architecture
		}

		ras.seek(0);	// Reset file pointer to start of file
	}

	private int indexOf(byte[] pattern, byte[] array) {
		return (new String(array)).indexOf(new String(pattern));
	}

	boolean isBigEndian() {
		return bigEndian;
	}

	int getAlignment() {
		return alignment;
	}

	double readDouble() throws IOException, RrdException {
		if(debug) {
			System.out.print("Read 8 bytes (Double) from offset "+ras.getFilePointer()+":");
		}

		//double value;
		byte[] tx = new byte[8];

		if(ras.read(buffer, 0, 8) != 8) {
			throw new RrdException("Invalid RRD file");
		}

		if (bigEndian) {
			tx = buffer;
		}
		else {
			for (int i = 0; i < 8; i++) {
				tx[7 - i] = buffer[i];
			}
		}

		DataInputStream reverseDis =
				new DataInputStream(new ByteArrayInputStream(tx));

		Double result = reverseDis.readDouble();
		if(this.debug) {
			System.out.println(result);
		}
		return result;
	}

	int readInt() throws IOException, RrdException {
		return readInt(false);
	}

	/**
	 * Reads the next integer (4 or 8 bytes depending on alignment), advancing the file pointer
	 *  and returns it
	 *  If the alignment is 8-bytes (64-bit), then 8 bytes are read, but only the lower 4-bytes (32-bits) are
	 *  returned.  The upper 4 bytes are ignored.
	 *
	 * @return the 32-bit integer read from the file
	 * @throws IOException - A file access error
	 * @throws RrdException - Not enough bytes were left in the file to read the integer.
	 */
	int readInt(boolean dump) throws IOException, RrdException {
		//An integer is "alignment" bytes long - 4 bytes on 32-bit, 8 on 64-bit.
		if(this.debug) {
			System.out.print("Read "+alignment+" bytes (int) from offset "+ras.getFilePointer()+":");
		}

		if(ras.read(buffer, 0, alignment) != alignment) {
			throw new RrdException("Invalid RRD file");
		}

		int value;

		if (bigEndian) {
			if(alignment == 8) {
				//For big-endian, the low 4-bytes of the 64-bit integer are the last 4 bytes
				value = (0xFF & buffer[7]) | ((0xFF & buffer[6]) << 8)
						| ((0xFF & buffer[5]) << 16) | ((0xFF & buffer[4]) << 24);
			} else {
				value = (0xFF & buffer[3]) | ((0xFF & buffer[2]) << 8)
						| ((0xFF & buffer[1]) << 16) | ((0xFF & buffer[0]) << 24);
			}
		}
		else {
			//For little-endian, there's no difference between 4 and 8 byte alignment.
			// The first 4 bytes are the low end of a 64-bit number
			value = (0xFF & buffer[0]) | ((0xFF & buffer[1]) << 8)
				| ((0xFF & buffer[2]) << 16) | ((0xFF & buffer[3]) << 24);
		}

		if(this.debug) {
			System.out.println(value);
		}
		return (int)value;
	}

	String readString(int maxLength) throws IOException, RrdException {
		if(this.debug) {
			System.out.print("Read "+maxLength+" bytes (string) from offset "+ras.getFilePointer()+":");
		}
		maxLength = ras.read(buffer, 0, maxLength);
		if(maxLength == -1) {
			throw new RrdException("Invalid RRD file");
		}

		String result = new String(buffer, 0, maxLength).trim();
		if(this.debug) {
			System.out.println( result +":");
		}
		return result;
	}

	void skipBytes(final int n) throws IOException {
		int bytesSkipped = ras.skipBytes(n);
		if(this.debug) {
			System.out.println("Skipping "+bytesSkipped+" bytes");
		}
	}

	int align(int boundary) throws IOException {

		int skip = (int) (boundary - (ras.getFilePointer() % boundary)) % boundary;

		if (skip != 0) {
			skip = ras.skipBytes(skip);
		}
		if(this.debug) {
			System.out.println("Aligning to boundary "+ boundary +".  Offset is now "+ras.getFilePointer());
		}
		return skip;
	}

	int align() throws IOException {
		return align(alignment);
	}

	long info() throws IOException {
		return ras.getFilePointer();
	}

	long getFilePointer() throws IOException {
		return ras.getFilePointer();
	}

	void close() throws IOException {
		ras.close();
	}
}
