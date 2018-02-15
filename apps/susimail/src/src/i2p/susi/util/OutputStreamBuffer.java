package i2p.susi.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Output only. Input unsupported.
 *
 * @since 0.9.34
 */
public class OutputStreamBuffer implements Buffer {

	private final OutputStream _out;

	public OutputStreamBuffer(OutputStream out) {
		_out = out;
	}

	/**
	 * @throws UnsupportedOperationException
	 */
	public InputStream getInputStream() {
		throw new UnsupportedOperationException();
	}

	/**
	 * @return new OutputStreamOutputStream
	 */
	public OutputStream getOutputStream() {
		return _out;
	}

	/**
	 * Does nothing
	 */
	public void readComplete(boolean success) {}

	/**
	 * Closes the output stream
	 */
	public void writeComplete(boolean success) {
		try { _out.close(); } catch (IOException ioe) {}
	}

	/**
	 * @return 0 always
	 */
	public int getLength() {
		return 0;
	}

	/**
	 * @return 0 always
	 */
	public int getOffset() {
		return 0;
	}

	@Override
	public String toString() {
		return "OSB";
	}
}
