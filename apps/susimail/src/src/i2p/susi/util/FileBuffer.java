package i2p.susi.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

import net.i2p.data.DataHelper;
import net.i2p.util.SecureFileOutputStream;

/**
 * File implementation of Buffer.
 *
 * @since 0.9.34
 */
public class FileBuffer implements Buffer {

	protected final File _file;
	protected final int _offset;
	protected final int _sublen;
	private InputStream _is;
	private OutputStream _os;
	
	public FileBuffer(File file) {
		this(file, 0, 0);
	}
	
	public FileBuffer(File file, int offset, int sublen) {
		_file = file;
		_offset = offset;
		_sublen = sublen;
	}

	/**
	 * @return the underlying file
	 */
	public File getFile() {
		return _file;
	}

	/**
         * Caller must call readComplete()
         *
	 * @return new FileInputStream
	 */
	public synchronized InputStream getInputStream() throws IOException {
		if (_is != null && _offset <= 0)
			return _is;
		_is = new FileInputStream(_file);
		if (_offset > 0)
			DataHelper.skip(_is, _offset);
		// TODO if _sublen > 0, wrap with a read limiter
		return _is;
	}

	/**
         * Caller must call writeComplete()
         *
	 * @return new FileOutputStream
	 */
	public synchronized OutputStream getOutputStream() throws IOException {
		if (_os != null)
			throw new IllegalStateException();
		_os = new SecureFileOutputStream(_file);
		return _os;
	}

	public synchronized void readComplete(boolean success) {
		if (_is != null) {
			try { _is.close(); } catch (IOException ioe) {}
			_is = null;
		}
	}

	/**
	 * Deletes the file if success is false
	 */
	public synchronized void writeComplete(boolean success) {
		if (_os != null) {
			try { _os.close(); } catch (IOException ioe) {}
			_os = null;
		}
		if (!success)
			_file.delete();
	}

	/**
	 * Always valid if file exists
	 */
	public int getLength() {
		if (_sublen > 0)
			return _sublen;
		return (int) _file.length();
	}

	/**
	 * Always valid
	 */
	public int getOffset() {
		return _offset;
	}

	@Override
	public String toString() {
		return "FB " + _file;
	}
}
