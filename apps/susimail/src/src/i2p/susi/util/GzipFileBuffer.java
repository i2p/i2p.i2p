package i2p.susi.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.i2p.data.DataHelper;
import net.i2p.util.SecureFileOutputStream;

/**
 * Gzip File implementation of Buffer.
 *
 * @since 0.9.34
 */
public class GzipFileBuffer extends FileBuffer {

	private long _actualLength;
	private CountingInputStream _cis;
	private CountingOutputStream _cos;

	public GzipFileBuffer(File file) {
		super(file);
	}
	
	public GzipFileBuffer(File file, int offset, int sublen) {
		super(file, offset, sublen);
	}

	/**
	 * @return new FileInputStream
	 */
	@Override
	public synchronized InputStream getInputStream() throws IOException {
		if (_cis != null && (_offset <= 0 || _offset == _cis.getRead()))
			return _cis;
		if (_cis != null && _offset > _cis.getRead()) {
			DataHelper.skip(_cis, _offset - _cis.getRead());
			return _cis;
		}
		_cis = new CountingInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(_file))));
		if (_offset > 0)
			DataHelper.skip(_cis, _offset);
		// TODO if _sublen > 0, wrap with a read limiter
		return _cis;
	}

	/**
	 * @return new FileOutputStream
	 */
	@Override
	public synchronized OutputStream getOutputStream() throws IOException {
		if (_offset > 0)
			throw new IllegalStateException();
		if (_cos != null)
			throw new IllegalStateException();
		_cos = new CountingOutputStream(new BufferedOutputStream(new GZIPOutputStream(new SecureFileOutputStream(_file))));
		return _cos;
	}

	@Override
	public synchronized void readComplete(boolean success) {
		if (_cis != null) {
			if (success)
				_actualLength = _cis.getRead();
			try { _cis.close(); } catch (IOException ioe) {}
			_cis = null;
		}
	}

	/**
	 * Sets the length if success is true
	 */
	@Override
	public synchronized void writeComplete(boolean success) {
		if (_cos != null) {
			if (success)
				_actualLength = _cos.getWritten();
			try { _cos.close(); } catch (IOException ioe) {}
			_cos = null;
		}
	}

	/**
         * Returns the actual uncompressed size.
         *
	 * Only known after reading and calling readComplete(true),
	 * or after writing and calling writeComplete(true),
	 * oherwise returns 0.
	 */
	@Override
	public int getLength() {
		return (int) _actualLength;
	}

	@Override
	public String toString() {
		return "GZFB " + _file;
	}
}
