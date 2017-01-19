/* ============================================================
 * JRobin : Pure java implementation of RRDTool's functionality
 * ============================================================
 *
 * Project Info:  http://www.jrobin.org
 * Project Lead:  Sasa Markovic (saxon@jrobin.org);
 *
 * (C) Copyright 2003, by Sasa Markovic.
 *
 * Developers:    Sasa Markovic (saxon@jrobin.org)
 *                Arne Vandamme (cobralord@jrobin.org)
 *
 * This library is free software; you can redistribute it and/or modify it under the terms
 * of the GNU Lesser General Public License as published by the Free Software Foundation;
 * either version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this
 * library; if not, write to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 * Boston, MA 02111-1307, USA.
 */

package org.jrobin.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.HashSet;
import java.util.Set;

/**
 * JRobin backend which is used to store RRD data to ordinary files on the disk. This was the
 * default factory before 1.4.0 version.
 * <p>
 * This backend is based on the RandomAccessFile class (java.io.* package).
 */
public class RrdJRobin14FileBackend extends RrdBackend {
	private static final long LOCK_DELAY = 100; // 0.1sec

	private static Set<String> m_openFiles = new HashSet<String>();

    public static enum LockMode {
        EXCEPTION_IF_LOCKED,
        WAIT_IF_LOCKED,
        NO_LOCKS
    };

	/** locking mode */
	protected LockMode m_lockMode;

	/** random access file handle */
	protected RandomAccessFile m_file;
	/** file lock */
	protected FileLock m_fileLock;

	/**
	 * Creates RrdFileBackend object for the given file path, backed by RandomAccessFile object.
	 * @param path Path to a file
	 * @param readOnly True, if file should be open in a read-only mode. False otherwise
	 * @param lockMode Locking mode: Exception if locked, Wait if locked, or no locks.
	 * @throws IOException Thrown in case of I/O error
	 */
	protected RrdJRobin14FileBackend(String path, boolean readOnly, LockMode lockMode) throws IOException {
	    super(path, readOnly);
		m_lockMode = lockMode;
		m_file = new RandomAccessFile(path, readOnly ? "r" : "rw");
		try {
			lockFile();
			registerWriter();
		} catch(final IOException ioe) {
			close();
			throw ioe;
		}
		System.err.println(String.format("backend initialized with path=%s, readOnly=%s, lockMode=%s", path, Boolean.valueOf(readOnly), lockMode));
	}

	private void lockFile() throws IOException {
		switch (m_lockMode) {
			case EXCEPTION_IF_LOCKED:
				m_fileLock = m_file.getChannel().tryLock();
				if (m_fileLock == null) {
					// could not obtain lock
					throw new IOException("Access denied. " + "File [" + getPath() + "] already locked");
				}
				break;
			case WAIT_IF_LOCKED:
				while (m_fileLock == null) {
					m_fileLock = m_file.getChannel().tryLock();
					if (m_fileLock == null) {
						// could not obtain lock, wait a little, than try again
						try {
							Thread.sleep(LOCK_DELAY);
						}
						catch (final InterruptedException e) {
						    Thread.currentThread().interrupt();
						}
					}
				}
				break;
			case NO_LOCKS:
				break;
		}
	}

	private void registerWriter() throws IOException {
		if (!isReadOnly()) {
		    final String canonicalPath = Util.getCanonicalPath(getPath());
			synchronized (m_openFiles) {
				if (m_openFiles.contains(canonicalPath)) {
					throw new IOException("File \"" + getPath() + "\" already open for R/W access. " +
							"You cannot open the same file for R/W access twice");
				}
				else {
					m_openFiles.add(canonicalPath);
				}
			}
		}
	}

	/**
	 * Closes the underlying RRD file.
	 *
	 * @throws IOException Thrown in case of I/O error
	 */
	public void close() throws IOException {
		unregisterWriter();
		try {
			unlockFile();
		} finally {
			m_file.close();
		}
	}

	private void unlockFile() throws IOException {
		if (m_fileLock != null) {
			m_fileLock.release();
		}
	}

	private void unregisterWriter() throws IOException {
		if (!isReadOnly()) {
			synchronized (m_openFiles) {
	            m_openFiles.remove(Util.getCanonicalPath(getPath()));
			}
		}
	}

	/**
	 * Returns canonical path to the file on the disk.
	 *
	 * @return Canonical file path
	 * @throws IOException Thrown in case of I/O error
	 */
	public String getCanonicalPath() throws IOException {
		return Util.getCanonicalPath(getPath());
	}

	/**
	 * Writes bytes to the underlying RRD file on the disk
	 *
	 * @param offset Starting file offset
	 * @param b      Bytes to be written.
	 * @throws IOException Thrown in case of I/O error
	 */
	protected void write(final long offset, final byte[] b) throws IOException {
		m_file.seek(offset);
		m_file.write(b);
	}

	/**
	 * Reads a number of bytes from the RRD file on the disk
	 *
	 * @param offset Starting file offset
	 * @param b      Buffer which receives bytes read from the file.
	 * @throws IOException Thrown in case of I/O error.
	 */
	protected void read(final long offset, final byte[] b) throws IOException {
		m_file.seek(offset);
		if (m_file.read(b) != b.length) {
			throw new IOException("Not enough bytes available in file " + getPath());
		}
	}

	/**
	 * Returns RRD file length.
	 *
	 * @return File length.
	 * @throws IOException Thrown in case of I/O error.
	 */
	public long getLength() throws IOException {
		return m_file.length();
	}

	/**
	 * Sets length of the underlying RRD file. This method is called only once, immediately
	 * after a new RRD file gets created.
	 *
	 * @param length Length of the RRD file
	 * @throws IOException Thrown in case of I/O error.
	 */
	protected void setLength(final long length) throws IOException {
		m_file.setLength(length);
	}
}
