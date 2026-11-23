package i2p.susi.webmail;

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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.PasswordManager;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;


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
 * This class should only be accessed from MailCache.
 *
 * TODO cached server caps and config.
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
	// non-null only for Drafts
	private final File _attachmentDir;
	private final I2PAppContext _context;
	private final Log _log;
	private final boolean _isDrafts;
	private final boolean _isSent;
	private final MailVault vault;
    private final java.util.concurrent.atomic.AtomicInteger loadFailures = new java.util.concurrent.atomic.AtomicInteger();

	private static final String DIR_SUSI = "susimail";
	private static final String DIR_CACHE = "cache";
	private static final String CACHE_PREFIX = "cache-";
	public static final String DIR_IMPORT = "import"; // Flat with .eml files, debug only for now
	public static final String DIR_ATTACHMENTS = "attachments"; // Flat with draft attachment files
	private static final String DIR_PREFIX = "s";
	private static final String FILE_PREFIX = "mail-";
	private static final String HDR_SUFFIX = ".hdr.txt.gz";
	private static final String FULL_SUFFIX = ".full.txt.gz";
	private static final String B64 = Base64.ALPHABET_I2P;

	/**
	 *  Use the params to generate a unique directory name.
	 *
	 *  Does NOT load the mails in. Caller MUST call getMails().
	 *
	 *  @param pass ignored
	 *  @param folder e.g. DIR_FOLDER
	 */
	public PersistentMailCache(I2PAppContext ctx, String host, int port, String user, String pass, String folder) throws IOException {
		this(ctx, host, port, user, pass, folder, null);
	}

	PersistentMailCache(I2PAppContext ctx, String host, int port, String user, String pass, String folder, MailVault unlocked) throws IOException {
		_context = ctx;
		_log = ctx.logManager().getLog(PersistentMailCache.class);
		_isDrafts = folder.equals(WebMail.DIR_DRAFTS);
		_isSent = folder.equals(WebMail.DIR_SENT);
		_lock = getLock(host, port, user, pass);
		synchronized(_lock) {
			_cacheDir = makeCacheDirs(host, port, user, pass, folder);
			vault = unlocked != null ? unlocked : MailVault.open(ctx, host, port, user, pass, true);
			if (!vault.directory.equals(_cacheDir.getParentFile().toPath().toAbsolutePath().normalize()))
				throw new IOException("The unlocked mailbox does not match this account");
			// Debugging only for now.
			File attach = null;
			if (folder.equals(WebMail.DIR_FOLDER) && Boolean.parseBoolean(i2p.susi.util.Config.getProperty("import.enable", "false"))) {
				importMail();
			} else if (folder.equals(WebMail.DIR_DRAFTS)) {
				attach = new SecureDirectory(_cacheDir, DIR_ATTACHMENTS);
				attach.mkdirs();
			}
			_attachmentDir = attach;
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
		Queue<File> fq = new LinkedBlockingQueue<File>();
		for (int j = 0; j < B64.length(); j++) {
			File subdir = new File(_cacheDir, DIR_PREFIX + B64.charAt(j));
			File[] files = subdir.listFiles();
			if (files == null)
				continue;
			for (int i = 0; i < files.length; i++) {
				File f = files[i];
				if (!f.isFile())
					continue;
				if (f.getName().endsWith(HDR_SUFFIX) && new File(f.getParentFile(), f.getName().replace(HDR_SUFFIX, FULL_SUFFIX)).exists()) continue;
                // Threaded, handle below
				//Mail mail = load(f);
				//if (mail != null)
			        //       rv.add(mail);
				fq.offer(f);
			}
		}
		int sz = fq.size();
		if (sz <= 0)
			return Collections.emptyList();
		
		// thread the read-in
		long begin = _context.clock().now();
		Queue<Mail> rv = new LinkedBlockingQueue<Mail>();
		int tcnt = 1; // authenticated legacy blobs may be large; previews keep subsequent startups small
		List<Thread> threads = new ArrayList<Thread>(tcnt);
		for (int i = 0; i < tcnt; i++) {
			Thread t = new I2PAppThread(new Loader(fq, rv, _isDrafts, _isSent), "Email loader " + i);
			t.start();
			threads.add(t);
		}
		for (int i = 0; i < tcnt; i++) {
			try {
				threads.get(i).join();
			} catch (InterruptedException ie) {
				break;
			}
		}
		long end = _context.clock().now();
		if (_log.shouldDebug()) _log.debug("Loaded " + sz + " emails with " + tcnt + " threads in " + DataHelper.formatDuration(end - begin));
		return rv;
	}

	/**
	 * Load files from in, add mail to out
	 * @since 0.9.34
	 */
	private class Loader implements Runnable {
		private final Queue<File> _in;
		private final Queue<Mail> _out;
		private final boolean _isD;
		private final boolean _isS;

		public Loader(Queue<File> in, Queue<Mail> out, boolean isDrafts, boolean isSent) {
			_in = in; _out = out;
			_isD = isDrafts;
			_isS = isSent;
		}

		public void run() {
			File f;
			while ((f = _in.poll()) != null) {
				Mail mail = load(f, _isD, _isS);
				if (mail != null) {
                    if (!mail.hasHeader()) loadFailures.incrementAndGet();
					_out.offer(mail);
                }
			}
		}
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
		boolean found = false;
        if (headerOnly && !_isDrafts && getHeaderFile(mail.uidl).exists()) {
            mail.setHeader(read(getHeaderFile(mail.uidl)));
            return mail.hasHeader();
        }
		File f = getFullFile(mail.uidl);
		if (f.exists()) {
			found = true;
			Buffer rb = read(f);
			if (rb != null) {
				mail.setBody(rb);
				return mail.hasBody();
			} else {
				if (_log.shouldWarn())
					_log.warn("Unable to read file " + f);
			}
		}
		f = getHeaderFile(mail.uidl);
		if (f.exists()) {
			found = true;
			Buffer rb = read(f);
			if (rb != null) {
				mail.setHeader(rb);
				return true;
			} else {
				if (_log.shouldWarn())
					_log.warn("Unable to read file " + f);
			}
		}
		if (!found && _log.shouldWarn())
			_log.warn("Unable to find file " + f + " for mail " + Base64.encode(mail.uidl));
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
			if (f.exists() && rb instanceof VaultFileBuffer && ((FileBuffer) rb).getFile().equals(f)) {
                if (!getHeaderFile(mail.uidl).exists()) savePreview(rb, getHeaderFile(mail.uidl));
				return true;
            }
			boolean rv = write(rb, f);
			if (rv)
				savePreview(rb, getHeaderFile(mail.uidl));
			return rv;
		}
		rb = mail.getHeader();
		if (rb != null) {
			File f = getHeaderFile(mail.uidl);
			if (f.exists() && rb instanceof VaultFileBuffer && ((FileBuffer) rb).getFile().equals(f))
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

	/**
	 *  @return non-null, sorted, possibly empty
	 *  @since 0.9.62
	 */
	public static List<String> getAllUsers(String host, int port) {
		File f = new SecureDirectory(I2PAppContext.getGlobalContext().getConfigDir(), DIR_SUSI);
		if (!f.exists())
			return Collections.emptyList();
		f = new SecureDirectory(f, DIR_CACHE);
		if (!f.exists())
			return Collections.emptyList();
		File[] files = f.listFiles();
		if (files == null || files.length == 0)
			return Collections.emptyList();
		List<String> rv = new ArrayList<String>(files.length);
		String suff = host + port;
		for (File d : files) {
			if (!d.isDirectory())
				continue;
			String name = d.getName();
			if (!name.startsWith(CACHE_PREFIX))
				continue;
			name = name.substring(CACHE_PREFIX.length());
			String dec = Base64.decodeToString(name);
			if (dec == null)
				continue;
			if (dec.endsWith(suff))
				dec = dec.substring(0, dec.length() - suff.length());
			rv.add(dec);
		}			
		if (rv.size() > 1)
			Collections.sort(rv);
		return rv;
	}

	public File getHeaderFile(String uidl) {
		return getFile(uidl, HDR_SUFFIX);
	}

	public File getFullFile(String uidl) {
		return getFile(uidl, FULL_SUFFIX);
	}

	/**
	 * For reading or writing a new full mail (NOT headers only).
	 * For writing, caller MUST call writeComplete() on rv.
	 * Does not necessarily exist.
	 *
	 * @since 0.9.35
	 */
	public Buffer getFullBuffer(String uidl) {
		return new VaultFileBuffer(vault, getFile(uidl, FULL_SUFFIX), true);
	}

	MailVault getVault() { return vault; }
    String getLoadStatus() { return loadFailures.get() == 0 ? "" : "Some saved messages failed a read or integrity check. Their original files were preserved; restore an intact backup to read them."; }

	/**
	 * @return non-null only for Drafts
	 * @since 0.9.35
	 */
	public File getAttachmentDir() {
		return _attachmentDir;
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
	private boolean write(Buffer rb, File f) {
		VaultFileBuffer gb = new VaultFileBuffer(vault, f, true);
		try (InputStream in = rb.getInputStream(); OutputStream out = gb.getOutputStream()) {
			DataHelper.copy(in, out);
		} catch (IOException ioe) {
			_log.error("Unable to save encrypted mail", ioe);
			rb.readComplete(false);
			gb.writeComplete(false);
			return false;
		}
		rb.readComplete(true);
		gb.writeComplete(true);
		return gb.isCommitted();
	}

	/**
	 *  @return null on failure
	 */
	private Buffer read(File f) {
		if (!f.canRead())
			return null;
		return new VaultFileBuffer(vault, f, true);
	}

	/**
	 *  This is for the initial load only.
	 *  Others will use getMail().
	 *
	 *  @return null on failure
	 */
	private Mail load(File f, boolean isDrafts, boolean isSent) {
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
		if (uidl == null) {
			Log log = I2PAppContext.getGlobalContext().logManager().getLog(PersistentMailCache.class);
			if (log.shouldWarn())
				log.warn("Unable to extract UIDL from file " + f);
			return null;
		}
		File preview = getHeaderFile(uidl);
        Buffer rb = read(!isDrafts && !headerOnly && preview.exists() ? preview : f);
		if (rb == null) {
			Log log = I2PAppContext.getGlobalContext().logManager().getLog(PersistentMailCache.class);
			if (log.shouldWarn())
				log.warn("Unable to read file " + f);
			return null;
		}
		Mail mail;
		if (isDrafts)
			mail = new Draft(uidl);
		else
			mail = new Mail(uidl, isSent);
		if (headerOnly) {
			mail.setHeader(rb);
		} else if (isDrafts) {
			// drafts always have FULL_SUFFIX but
			// may not actually have a real body or part.
			// If we don't call setBody(), it has
			// a null part and we NPE on the compose page.
			// Attachments are stored in separate files so
			// these are all small.
			mail.setBody(rb);
		} else {
			// Deferred loading, body will be loaded
			// on-demand in MailCache.getMail()
			// We set the size of the gzipped file to be the
			// size so the UI doesn't have ?? in it.
			// The size will be corrected if and when the body is read.
			//mail.setBody(rb);
			mail.setHeader(rb);
            if (!preview.exists()) savePreview(rb, preview);
			mail.setSize(f.length());
		}
		return mail;
	}

    private void savePreview(Buffer full, File target) {
        ByteArrayOutputStream header = new ByteArrayOutputStream(4096);
        boolean complete = false;
        try (InputStream in = full.getInputStream()) {
            int value, end = 0;
            byte[] separator = {13, 10, 13, 10};
            while ((value = in.read()) != -1) {
                if (header.size() >= MailHeaders.MAX_BYTES) throw new IOException("Header preview is too large");
                header.write(value);
                end = value == separator[end] ? end + 1 : (value == 13 ? 1 : 0);
                if (end == 4) { complete = true; break; }
            }
            if (complete) write(new ReadBuffer(header.toByteArray(), 0, header.size()), target);
        } catch (IOException failure) { if (_log.shouldWarn()) _log.warn("Unable to save encrypted header preview"); }
        finally { full.readComplete(complete); }
    }

	/**
	 *  For debugging. Import .eml files from the import/ directory
	 *  @since 0.9.34
	 */
    private void importMail() {
        File importDir = new File(_cacheDir.getParentFile(), DIR_IMPORT);
        File[] files = importDir.listFiles(new FileSuffixFilter(".eml"));
        if (files == null) return;
        for (File file : files) {
            // Legacy imports were encrypted during migration. Newly supplied exports are read once and sealed.
            VaultFileBuffer destination = null;
            boolean success = false;
            try {
                boolean sealed = MailVault.isEncrypted(file.toPath());
                Buffer source = sealed ? new VaultFileBuffer(vault, file, false) : new FileBuffer(file);
                String uidl = "import-" + _context.random().nextLong();
                File target = getFullFile(uidl);
                destination = new VaultFileBuffer(vault, target, true);
                try (InputStream in = source.getInputStream(); OutputStream out = new FixCRLFOutputStream(destination.getOutputStream())) { DataHelper.copy(in, out); }
                finally { source.readComplete(true); }
                destination.writeComplete(true);
                if (!destination.isCommitted()) throw new IOException("Encrypted import was not saved");
                Mail check = new Mail(uidl);
                if (!getMail(check, false) || !check.hasBody()) { target.delete(); throw new IOException("Imported message is invalid"); }
                savePreview(check.getBody(), getHeaderFile(uidl));
                success = true;
            } catch (IOException failure) { if (_log.shouldWarn()) _log.warn("Mail import failed; the source was preserved"); }
            finally { if (destination != null) destination.writeComplete(success); }
            if (success) file.delete();
        }
    }
}
