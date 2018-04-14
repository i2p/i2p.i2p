package i2p.susi.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Buffer backed by a byte array.
 * Use for small amounts of data only.
 *
 * @since 0.9.34
 */
public class MemoryBuffer implements Buffer {

	private ByteArrayOutputStream _baos;
	private byte content[];
	private final int _size;
	
	public MemoryBuffer() {
		this(4096);
	}

	public MemoryBuffer(int size) {
		_size = size;
	}

	/**
	 * @return new ByteArrayInputStream
	 */
	public synchronized InputStream getInputStream() throws IOException {
		if (content == null)
			throw new IOException("no data");
		return new ByteArrayInputStream(content);
	}

	/**
	 * @return new or existing ByteArrayOutputStream
	 */
	public synchronized OutputStream getOutputStream() {
		if (_baos == null)
			_baos = new ByteArrayOutputStream(_size);
		return _baos;
	}

	public void readComplete(boolean success) {}

	/**
	 * Deletes the data if success is false
	 */
	public synchronized void writeComplete(boolean success) {
		if (success) {
			if (content == null)
				content = _baos.toByteArray();
		} else {
			content = null;
		}
		_baos = null;
	}

	/**
	 * Current size.
	 */
	public synchronized int getLength() {
		if (content != null)
			return content.length;
		if (_baos != null)
			return _baos.size();
		return 0;
	}

	/**
	 * @return 0 always
	 */
	public int getOffset() {
		return 0;
	}

	/**
	 * @return content if writeComplete(true) was called, otherwise null
	 */
	public byte[] getContent() {
		return content;
	}

	@Override
	public String toString() {
		return "MB " + (content == null ? "empty" : content.length + " bytes");
	}
}
