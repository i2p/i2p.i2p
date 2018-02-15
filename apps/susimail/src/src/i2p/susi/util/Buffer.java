package i2p.susi.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Base interface for all Buffers.
 * Data may only be read or written via streams,
 * unless implemented via additional methods in subclasses.
 *
 * @since 0.9.34
 */
public interface Buffer {

	public InputStream getInputStream() throws IOException;

	public OutputStream getOutputStream() throws IOException;

	/**
	 *  Top-level reader MUST call this to close the input stream.
	 */
	public void readComplete(boolean success);

	/**
	 *  Writer MUST call this when done.
	 *  @param success if false, deletes any resources
	 */
	public void writeComplete(boolean success);

	public int getLength();

	public int getOffset();
}
