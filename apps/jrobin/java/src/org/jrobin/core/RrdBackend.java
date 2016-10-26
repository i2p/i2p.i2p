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

package org.jrobin.core;

import java.io.IOException;

/**
 * Base implementation class for all backend classes. Each Round Robin Database object
 * ({@link RrdDb} object) is backed with a single RrdBackend object which performs
 * actual I/O operations on the underlying storage. JRobin supports
 * three different bakcends out of the box:
 * <p>
 * <ul>
 * <li>{@link RrdFileBackend}: objects of this class are created from the
 * {@link RrdFileBackendFactory} class. This was the default backend used in all
 * JRobin releases prior to 1.4.0. It uses java.io.* package and
 * RandomAccessFile class to store RRD data in files on the disk.
 * <p>
 * <li>{@link RrdNioBackend}: objects of this class are created from the
 * {@link RrdNioBackendFactory} class. The backend uses java.io.* and java.nio.*
 * classes (mapped ByteBuffer) to store RRD data in files on the disk. This backend is fast, very fast,
 * but consumes a lot of memory (borrowed not from the JVM but from the underlying operating system
 * directly). <b>This is the default backend used in JRobin since 1.4.0 release.</b>
 * <p>
 * <li>{@link RrdMemoryBackend}: objects of this class are created from the
 * {@link RrdMemoryBackendFactory} class. This backend stores all data in memory. Once
 * JVM exits, all data gets lost. The backend is extremely fast and memory hungry.
 * </ul>
 * <p>
 * To create your own backend in order to provide some custom type of RRD storage,
 * you should do the following:
 * <p>
 * <ul>
 * <li>Create your custom RrdBackend class (RrdCustomBackend, for example)
 * by extending RrdBackend class. You have to implement all abstract methods defined
 * in the base class.
 * <p>
 * <li>Create your custom RrdBackendFactory class (RrdCustomBackendFactory,
 * for example) by extending RrdBackendFactory class. You have to implement all
 * abstract methods defined in the base class. Your custom factory class will actually
 * create custom backend objects when necessary.
 * <p>
 * <li>Create instance of your custom RrdBackendFactory and register it as a regular
 * factory available to JRobin framework. See javadoc for {@link RrdBackendFactory} to
 * find out how to do this
 * </ul>
 */
public abstract class RrdBackend {
	private static boolean s_instanceCreated = false;
	private String m_path = null;
	private boolean m_readOnly = false;

	/**
	 * Creates backend for a RRD storage with the given path.
	 *
	 * @param path String identifying RRD storage. For files on the disk, this
	 *             argument should represent file path. Other storage types might interpret
	 *             this argument differently.
	 */
	protected RrdBackend(final String path) {
	    this(path, false);
	}

	protected RrdBackend(final String path, final boolean readOnly) {
        m_path = path;
	    m_readOnly = readOnly;
	    RrdBackend.setInstanceCreated();
	}

	/**
	 * Returns path to the storage.
	 *
	 * @return Storage path
	 */
	public String getPath() {
		return m_path;
	}

	/**
	 * Is the RRD ReadOnly?
	 *
	 * @return True if the RRD is read only, false if not.
	 */
	public boolean isReadOnly() {
	    return m_readOnly;
	}

	/**
	 * Writes an array of bytes to the underlying storage starting from the given
	 * storage offset.
	 *
	 * @param offset Storage offset.
	 * @param b	  Array of bytes that should be copied to the underlying storage
	 * @throws IOException Thrown in case of I/O error
	 */
	protected abstract void write(long offset, byte[] b) throws IOException;

	/**
	 * Reads an array of bytes from the underlying storage starting from the given
	 * storage offset.
	 *
	 * @param offset Storage offset.
	 * @param b	  Array which receives bytes from the underlying storage
	 * @throws IOException Thrown in case of I/O error
	 */
	protected abstract void read(long offset, byte[] b) throws IOException;

	/**
	 * Returns the number of RRD bytes in the underlying storage.
	 *
	 * @return Number of RRD bytes in the storage.
	 * @throws IOException Thrown in case of I/O error.
	 */
	public abstract long getLength() throws IOException;

	/**
	 * Sets the number of bytes in the underlying RRD storage.
	 * This method is called only once, immediately after a new RRD storage gets created.
	 *
	 * @param length Length of the underlying RRD storage in bytes.
	 * @throws IOException Thrown in case of I/O error.
	 */
	protected abstract void setLength(long length) throws IOException;

	/**
	 * Closes the underlying backend.
	 * @throws IOException Thrown in case of I/O error.
	 */
	public void close() throws IOException {
	}

	/**
	 * This method suggests the caching policy to the JRobin frontend (high-level) classes. If <code>true</code>
	 * is returned, frontent classes will cache frequently used parts of a RRD file in memory to improve
	 * performance. If <code>false</code> is returned, high level classes will never cache RRD file sections
	 * in memory.
	 *
	 * @return <code>true</code> if file caching is enabled, <code>false</code> otherwise. By default, the
	 *         method returns <code>true</code> but it can be overriden in subclasses.
	 */
	protected boolean isCachingAllowed() {
		return true;
	}

	/**
	 * Reads all RRD bytes from the underlying storage
	 *
	 * @return RRD bytes
	 * @throws IOException Thrown in case of I/O error
	 */
	public final byte[] readAll() throws IOException {
	    final byte[] b = new byte[(int) getLength()];
		read(0, b);
		return b;
	}

	final void writeInt(final long offset, final int value) throws IOException {
		write(offset, getIntBytes(value));
	}

	final void writeLong(final long offset, final long value) throws IOException {
		write(offset, getLongBytes(value));
	}

	final void writeDouble(final long offset, final double value) throws IOException {
		write(offset, getDoubleBytes(value));
	}

	final void writeDouble(final long offset, final double value, final int count) throws IOException {
	    final byte[] b = getDoubleBytes(value);
		final byte[] image = new byte[8 * count];
		for (int i = 0, k = 0; i < count; i++) {
			image[k++] = b[0];
			image[k++] = b[1];
			image[k++] = b[2];
			image[k++] = b[3];
			image[k++] = b[4];
			image[k++] = b[5];
			image[k++] = b[6];
			image[k++] = b[7];
		}
		write(offset, image);
	}

	final void writeDouble(final long offset, final double[] values) throws IOException {
		final int count = values.length;
		final byte[] image = new byte[8 * count];
		for (int i = 0, k = 0; i < count; i++) {
			final byte[] b = getDoubleBytes(values[i]);
			image[k++] = b[0];
			image[k++] = b[1];
			image[k++] = b[2];
			image[k++] = b[3];
			image[k++] = b[4];
			image[k++] = b[5];
			image[k++] = b[6];
			image[k++] = b[7];
		}
		write(offset, image);
	}

	final void writeString(final long offset, final String rawValue) throws IOException {
	    final String value = rawValue.trim();
	    final byte[] b = new byte[RrdPrimitive.STRING_LENGTH * 2];
		for (int i = 0, k = 0; i < RrdPrimitive.STRING_LENGTH; i++) {
			final char c = (i < value.length()) ? value.charAt(i) : ' ';
			final byte[] cb = getCharBytes(c);
			b[k++] = cb[0];
			b[k++] = cb[1];
		}
		write(offset, b);
	}

	final int readInt(final long offset) throws IOException {
	    final byte[] b = new byte[4];
		read(offset, b);
		return getInt(b);
	}

	final long readLong(final long offset) throws IOException {
	    final byte[] b = new byte[8];
		read(offset, b);
		return getLong(b);
	}

	final double readDouble(final long offset) throws IOException {
	    final byte[] b = new byte[8];
		read(offset, b);
		return getDouble(b);
	}

	final double[] readDouble(final long offset, final int count) throws IOException {
	    final int byteCount = 8 * count;
		final byte[] image = new byte[byteCount];
		read(offset, image);
		final double[] values = new double[count];
		for (int i = 0, k = -1; i < count; i++) {
			final byte[] b = new byte[] {
					image[++k], image[++k], image[++k], image[++k],
					image[++k], image[++k], image[++k], image[++k]
			};
			values[i] = getDouble(b);
		}
		return values;
	}

	final String readString(final long offset) throws IOException {
	    final byte[] b = new byte[RrdPrimitive.STRING_LENGTH * 2];
		final char[] c = new char[RrdPrimitive.STRING_LENGTH];
		read(offset, b);
		for (int i = 0, k = -1; i < RrdPrimitive.STRING_LENGTH; i++) {
			final byte[] cb = new byte[] {b[++k], b[++k]};
			c[i] = getChar(cb);
		}
		return new String(c).trim();
	}

	// static helper methods

	private static byte[] getIntBytes(final int value) {
	    final byte[] b = new byte[4];
		b[0] = (byte) ((value >>> 24) & 0xFF);
		b[1] = (byte) ((value >>> 16) & 0xFF);
		b[2] = (byte) ((value >>> 8) & 0xFF);
		b[3] = (byte) ((value) & 0xFF);
		return b;
	}

	private static byte[] getLongBytes(final long value) {
	    final byte[] b = new byte[8];
		b[0] = (byte) ((int) (value >>> 56) & 0xFF);
		b[1] = (byte) ((int) (value >>> 48) & 0xFF);
		b[2] = (byte) ((int) (value >>> 40) & 0xFF);
		b[3] = (byte) ((int) (value >>> 32) & 0xFF);
		b[4] = (byte) ((int) (value >>> 24) & 0xFF);
		b[5] = (byte) ((int) (value >>> 16) & 0xFF);
		b[6] = (byte) ((int) (value >>> 8) & 0xFF);
		b[7] = (byte) ((int) (value) & 0xFF);
		return b;
	}

	private static byte[] getCharBytes(final char value) {
	    final byte[] b = new byte[2];
		b[0] = (byte) ((value >>> 8) & 0xFF);
		b[1] = (byte) ((value) & 0xFF);
		return b;
	}

	private static byte[] getDoubleBytes(final double value) {
		return getLongBytes(Double.doubleToLongBits(value));
	}

	private static int getInt(final byte[] b) {
		assert b.length == 4: "Invalid number of bytes for integer conversion";
		return ((b[0] << 24) & 0xFF000000) + ((b[1] << 16) & 0x00FF0000) +
				((b[2] << 8) & 0x0000FF00) + (b[3] & 0x000000FF);
	}

	private static long getLong(final byte[] b) {
		assert b.length == 8: "Invalid number of bytes for long conversion";
		int high = getInt(new byte[] {b[0], b[1], b[2], b[3]});
		int low = getInt(new byte[] {b[4], b[5], b[6], b[7]});
		return ((long) (high) << 32) + (low & 0xFFFFFFFFL);
	}

	private static char getChar(final byte[] b) {
		assert b.length == 2: "Invalid number of bytes for char conversion";
		return (char) (((b[0] << 8) & 0x0000FF00)
				+ (b[1] & 0x000000FF));
	}

	private static double getDouble(final byte[] b) {
		assert b.length == 8: "Invalid number of bytes for double conversion";
		return Double.longBitsToDouble(getLong(b));
	}

	private static void setInstanceCreated() {
		s_instanceCreated = true;
	}

	static boolean isInstanceCreated() {
		return s_instanceCreated;
	}
}
