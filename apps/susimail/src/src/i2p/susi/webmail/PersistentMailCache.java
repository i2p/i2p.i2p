package i2p.susi.webmail;

import i2p.susi.debug.Debug;
import i2p.susi.webmail.Messages;
import i2p.susi.util.Buffer;
import i2p.susi.util.FileBuffer;
import i2p.susi.util.FixCRLFOutputStream;
import i2p.susi.util.GzipFileBuffer;
import i2p.susi.util.ReadBuffer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.PasswordManager;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;


/**
 * Manage the on-disk cache.
 *
 * This is a custom format with subdirectories, gzipped files,
 * and the encoded UIDL in the file name.
 * We store either the headers or the full message.
 * No, it is not Maildir format but we could add Maildir-style
 * status suffixes (e.g. ":2.SR") later.
 *
 * Exporting to a Maildir format would be just ungzipping
 * each file to a flat directory.
 *
 * TODO draft and sent folders, cached server caps and config.
 *
 * @since 0.9.14
 */
class PersistentMailCache {
	
	/**
	 *  One lock for each user in the whole JVM, to protect against multiple sessions.
	 *  One big lock for the whole cache dir, not one for each file or subdir.
	 *  Never expired.
	 *  Sure, if we did a maildir format we wouldn't need this.
	 */
	private static final ConcurrentHashMap<String, Object> _locks = new ConcurrentHashMap<String, Object>();

	private final Object _lock;
	private final File _cacheDir;
	private final I2PAppContext _context;

	private static final String DIR_SUSI = "susimail";
	private static final String DIR_CACHE = "cache";
	private static final String CACHE_PREFIX = "cache-";
	public static final String DIR_FOLDER = "cur"; // MailDir-like
	public static final String DIR_DRAFTS = "Drafts"; // MailDir-like
	public static final String DIR_SENT = "Sent"; // MailDir-like
	public static final String DIR_TRASH = "Trash"; // MailDir-like
	public static final String DIR_SPAM = "Bulk Mail"; // MailDir-like
	public static final String DIR_IMPORT = "import"; // Flat with .eml files, debug only for now
	private static final String DIR_PREFIX = "s";
	private static final String FILE_PREFIX = "mail-";
	private static final String HDR_SUFFIX = ".hdr.txt.gz";
	private static final String FULL_SUFFIX = ".full.txt.gz";
	private static final String B64 = Base64.ALPHABET_I2P;

	/**
	 *  Use the params to generate a unique directory name.
	 *  @param pass ignored
	 *  @param folder use DIR_FOLDER
	 */
	public PersistentMailCache(I2PAppContext ctx, String host, int port, String user, String pass, String folder) throws IOException {
		_context = ctx;
		_lock = getLock(host, port, user, pass);
		synchronized(_lock) {
			_cacheDir = makeCacheDirs(host, port, user, pass, folder);
			// Debugging only for now.
			if (folder.equals(DIR_FOLDER))
				importMail();
		}
	}

	/**
	 * Fetch all mails from disk.
	 * 
	 * @return a new collection
	 */
	public Collection<Mail> getMails() {
		synchronized(_lock) {
			return locked_getMails();
		}
	}

	private Collection<Mail> locked_getMails() {
		List<Mail> rv = new ArrayList<Mail>();
		for (int j = 0; j < B64.length(); j++) {
			File subdir = new File(_cacheDir, DIR_PREFIX + B64.charAt(j));
			File[] files = subdir.listFiles();
			if (files == null)
				continue;
			for (int i = 0; i < files.length; i++) {
				File f = files[i];
				if (!f.isFile())
					continue;
				Mail mail = load(f);
				if (mail != null)
			               rv.add(mail);
			}
		}
		return rv;
	}

	/**
	 * Fetch any needed data from disk.
	 * 
	 * @return success
	 */
	public boolean getMail(Mail mail, boolean headerOnly) {
		synchronized(_lock) {
			return locked_getMail(mail, headerOnly);
		}
	}

	private boolean locked_getMail(Mail mail, boolean headerOnly) {
		File f = getFullFile(mail.uidl);
		if (f.exists()) {
			Buffer rb = read(f);
			if (rb != null) {
				mail.setBody(rb);
				return true;
			}
		}
		f = getHeaderFile(mail.uidl);
		if (f.exists()) {
			Buffer rb = read(f);
			if (rb != null) {
				mail.setHeader(rb);
				return true;
			}
		}
		return false;
	}

	/**
	 * Save data to disk.
	 * 
	 * @return success
	 */
	public boolean saveMail(Mail mail) {
		synchronized(_lock) {
			return locked_saveMail(mail);
		}
	}

	private boolean locked_saveMail(Mail mail) {
		Buffer rb = mail.getBody();
		if (rb != null) {
			File f = getFullFile(mail.uidl);
			if (f.exists())
				return true;  // already there, all good
			boolean rv = write(rb, f);
			if (rv)
				getHeaderFile(mail.uidl).delete();
			return rv;
		}
		rb = mail.getHeader();
		if (rb != null) {
			File f = getHeaderFile(mail.uidl);
			if (f.exists())
				return true;  // already there, all good
			boolean rv = write(rb, f);
			return rv;
		}
		return false;
	}

	/**
	 * 
	 * Delete data from disk.
	 */
	public void deleteMail(Mail mail) {
		deleteMail(mail.uidl);
	}

	/**
	 * 
	 * Delete data from disk.
	 */
	public void deleteMail(String uidl) {
		synchronized(_lock) {
			getFullFile(uidl).delete();
			getHeaderFile(uidl).delete();
		}
	}

	private static Object getLock(String host, int port, String user, String pass) {
		Object lock = new Object();
		Object old = _locks.putIfAbsent(user + host + port, lock);
		return (old != null) ? old : lock;
	}

	/**
	 *   ~/.i2p/susimail/cache/cache-xxxxx/cur/s[b64char]/mail-xxxxx.full.txt.gz
	 *   folder1 is the base.
	 */
	private File makeCacheDirs(String host, int port, String user, String pass, String folder) throws IOException {
		File f = new SecureDirectory(_context.getConfigDir(), DIR_SUSI);
		if (!f.exists() && !f.mkdir())
			throw new IOException("Cannot create " + f);
		f = new SecureDirectory(f, DIR_CACHE);
		if (!f.exists() && !f.mkdir())
			throw new IOException("Cannot create " + f);
		f = new SecureDirectory(f, CACHE_PREFIX + Base64.encode(user + host + port));
		if (!f.exists() && !f.mkdir())
			throw new IOException("Cannot create " + f);
		File base = new SecureDirectory(f, folder);
		if (!base.exists() && !base.mkdir())
			throw new IOException("Cannot create " + base);
		for (int i = 0; i < B64.length(); i++) {
			f = new SecureDirectory(base, DIR_PREFIX + B64.charAt(i));
			if (!f.exists() && !f.mkdir())
				throw new IOException("Cannot create " + f);
		}
		return base;
	}

	private File getHeaderFile(String uidl) {
		return getFile(uidl, HDR_SUFFIX);
	}

	private File getFullFile(String uidl) {
		return getFile(uidl, FULL_SUFFIX);
	}

	private File getFile(String uidl, String suffix) {
		byte[] raw = DataHelper.getASCII(uidl);
		byte[] md5 = PasswordManager.md5Sum(raw);
		String db64 = Base64.encode(md5);
		File dir = new File(_cacheDir, DIR_PREFIX + db64.charAt(0));
		String b64 = Base64.encode(uidl);
		return new SecureFile(dir, FILE_PREFIX + b64 + suffix);
	}

	/**
	 * Save data to disk.
	 * 
	 * @return success
	 */
	private static boolean write(Buffer rb, File f) {
		InputStream in = null;
		OutputStream out = null;
		try {
			in = rb.getInputStream();
			GzipFileBuffer gb = new GzipFileBuffer(f);
			out = gb.getOutputStream();
			DataHelper.copy(in, out);
			return true;
		} catch (IOException ioe) {
			Debug.debug(Debug.ERROR, "Error writing: " + f + ": " + ioe);
			return false;
		} finally {
			if (in != null) 
				try { in.close(); } catch (IOException ioe) {}
			if (out != null) 
				try { out.close(); } catch (IOException ioe) {}
		}
	}

	/**
	 *  @return null on failure
	 */
	private static Buffer read(File f) {
		return new GzipFileBuffer(f);
	}

	/**
	 *  @return null on failure
	 */
	private static Mail load(File f) {
		String name = f.getName();
		String uidl;
		boolean headerOnly;
		if (name.endsWith(FULL_SUFFIX)) {
			uidl= Base64.decodeToString(name.substring(FILE_PREFIX.length(), name.length() - FULL_SUFFIX.length()));
			headerOnly = false;
		} else if (name.endsWith(HDR_SUFFIX)) {
			uidl= Base64.decodeToString(name.substring(FILE_PREFIX.length(), name.length() - HDR_SUFFIX.length()));
			headerOnly = true;
		} else {
			return null;
		}
		if (uidl == null)
			return null;
		Buffer rb = read(f);
		if (rb == null)
			return null;
		Mail mail = new Mail(uidl);
		if (headerOnly)
			mail.setHeader(rb);
		else
			mail.setBody(rb);
		return mail;
	}

	/**
	 *  For debugging. Import .eml files from the import/ directory
	 *  @since 0.9.34
	 */
	private void importMail() {
		File importDir = new File(_cacheDir.getParentFile(), DIR_IMPORT);
		if (importDir.exists() && importDir.isDirectory()) {
			File[] files = importDir.listFiles();
			if (files == null)
				return;
			for (int i = 0; i < files.length; i++) {
				File f = files[i];
				if (!f.isFile())
					continue;
				if (!f.getName().toLowerCase(Locale.US).endsWith(".eml"))
					continue;
				// Read in the headers to get the X-UIDL that Thunderbird stuck in there
				String uidl = Long.toString(_context.random().nextLong());
				InputStream in = null;
				try {
					in = new FileInputStream(f);
					for (int j = 0; j < 20; j++) {
						String line = DataHelper.readLine(in);
						if (line.length() < 2)
							break;
						if (line.startsWith("X-UIDL:")) {
							uidl = line.substring(7).trim();
							break;
						}
					}
				} catch (IOException ioe) {
					Debug.debug(Debug.ERROR, "Import failed " + f, ioe);
					continue;
				} finally {
					if (in != null) 
						try { in.close(); } catch (IOException ioe) {}
				}
				if (uidl == null)
					uidl = Long.toString(_context.random().nextLong());
				File to = getFullFile(uidl);
				if (to.exists()) {
					Debug.debug(Debug.DEBUG, "Already have " + f + " as UIDL " + uidl);
					f.delete();
					continue;
				}
				in = null;
				OutputStream out = null;
				try {
					in = new FileInputStream(f);
					GzipFileBuffer gb = new GzipFileBuffer(to);
					// Thunderbird exports aren't CRLF terminated
					out = new FixCRLFOutputStream(gb.getOutputStream());
					DataHelper.copy(in, out);
				} catch (IOException ioe) {
					Debug.debug(Debug.ERROR, "Import failed " + f, ioe);
					continue;
				} finally {
					if (in != null) 
						try { in.close(); } catch (IOException ioe) {}
					if (out != null) 
						try { out.close(); } catch (IOException ioe) {}
				}
				f.delete();
				Debug.debug(Debug.DEBUG, "Imported " + f + " as UIDL " + uidl);
	       		}
		}
	}
}
