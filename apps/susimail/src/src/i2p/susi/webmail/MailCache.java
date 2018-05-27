/*
 * Created on Nov 23, 2004
 * 
 *  This file is part of susimail project, see http://susi.i2p/
 *  
 *  Copyright (C) 2004-2005  <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *  
 *  $Revision: 1.2 $
 */
package i2p.susi.webmail;

import i2p.susi.util.Config;
import i2p.susi.util.Buffer;
import i2p.susi.util.FileBuffer;
import i2p.susi.util.Folder;
import i2p.susi.util.Folder.SortOrder;
import i2p.susi.util.ReadBuffer;
import i2p.susi.util.MemoryBuffer;
import static i2p.susi.webmail.Sorters.*;
import i2p.susi.webmail.pop3.POP3MailBox;
import i2p.susi.webmail.pop3.POP3MailBox.FetchRequest;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * There's one of these for each Folder.
 * However, only DIR_FOLDER has a non-null POP3MailBox.
 *
 * @author user
 */
class MailCache {
	
	public enum FetchMode {
		HEADER, ALL, CACHE_ONLY
	}

	private final POP3MailBox mailbox;
	private final Hashtable<String, Mail> mails;
	private final PersistentMailCache disk;
	private final I2PAppContext _context;
	private final Folder<String> folder;
	private final String folderName;
	private NewMailListener _loadInProgress;
	private boolean _isLoaded;
	private final boolean _isDrafts;
	private final Log _log;
	
	/** Includes header, headers are generally 1KB to 1.5 KB,
	 *  and bodies will compress well.
         */
	private static final int FETCH_ALL_SIZE = 32*1024;


	/**
	 * Does NOT load the mails in. Caller MUST call loadFromDisk().
	 *
	 * @param mailbox non-null for DIR_FOLDER; null otherwise
	 */
	MailCache(I2PAppContext ctx, POP3MailBox mailbox, String folderName,
		  String host, int port, String user, String pass) throws IOException {
		_log = ctx.logManager().getLog(MailCache.class);
		this.mailbox = mailbox;
		mails = new Hashtable<String, Mail>();
		disk = new PersistentMailCache(ctx, host, port, user, pass, folderName);
		_context = ctx;
		Folder<String> folder = new Folder<String>();	
		// setElements() sorts, so configure the sorting first
		//sessionObject.folder.addSorter( SORT_ID, new IDSorter( sessionObject.mailCache ) );
		folder.addSorter(WebMail.SORT_SENDER, new SenderSorter(this));
		folder.addSorter(WebMail.SORT_SUBJECT, new SubjectSorter(this));
		folder.addSorter(WebMail.SORT_DATE, new DateSorter(this));
		folder.addSorter(WebMail.SORT_SIZE, new SizeSorter(this));
		// reverse sort, latest mail first
		// TODO get user defaults from config
		folder.setSortBy(WebMail.SORT_DEFAULT, WebMail.SORT_ORDER_DEFAULT);
		this.folder = folder;
		this.folderName = folderName;
		_isDrafts = folderName.equals(WebMail.DIR_DRAFTS);
	}

	/**
	 * @since 0.9.35
	 * @return as passed in
	 */
	public String getFolderName() {
		return folderName;
	}

	/**
	 * @since 0.9.35
	 * @return translation of name passed in
	 */
	public String getTranslatedName() {
		String rv = folderName.equals(WebMail.DIR_FOLDER) ? "Inbox" : folderName;
		return Messages.getString(rv);
	}

	/**
	 * @since 0.9.35
	 * @return non-null
	 */
	public Folder<String> getFolder() {
		return folder;
	}

	/**
	 * For writing a new full mail (NOT headers only)
	 * Caller must close.
	 * @since 0.9.35
	 */
	public Buffer getFullWriteBuffer(String uidl) {
		// no locking this way
		return disk.getFullBuffer(uidl);
	}

	/**
	 * For writing a new full mail
	 * @param buffer as received from getFullBuffer
	 * @since 0.9.35
	 */
	public void writeComplete(String uidl, Buffer buffer, boolean success) {
		buffer.writeComplete(success);
		if (success) {
			Mail mail;
			if (_isDrafts)
				mail = new Draft(uidl);
			else
				mail = new Mail(uidl);
			mail.setBody(buffer);
			synchronized(mails) {
				mails.put(uidl, mail);
			}
			folder.addElement(uidl);
		}
	}

	/**
	 * @return non-null only for Drafts
	 * @since 0.9.35
	 */
	public File getAttachmentDir() {
		return disk.getAttachmentDir();
	}

	/**
	 * Move a mail to another MailCache, neither may be DIR_DRAFTS
	 * @return success
	 * @since 0.9.35
	 */
	public boolean moveTo(String uidl, MailCache toMC) {
		if (folderName.equals(WebMail.DIR_DRAFTS) ||
		    toMC.getFolderName().equals(WebMail.DIR_DRAFTS))
			return false;
		Mail mail;
		synchronized(mails) {
			mail = mails.get(uidl);
			if (mail == null)
				return false;
			if (!mail.hasBody())
				return false;
			File from = disk.getFullFile(uidl);
			if (!from.exists())
				return false;
			PersistentMailCache toPMC = toMC.disk;
			File to = toPMC.getFullFile(uidl);
			if (to.exists())
				return false;
			if (!FileUtil.rename(from, to))
				return false;
			mails.remove(uidl);
			folder.removeElement(uidl);
		}
		toMC.movedTo(mail);
		if (mailbox != null)
			mailbox.queueForDeletion(mail.uidl);
		return true;
	}

	/**
	 * Moved a mail from another MailCache
	 * @since 0.9.35
	 */
	private void movedTo(Mail mail) {
		synchronized(mails) {
			// we must reset the body of the mail to the new FileBuffer
			Buffer body = disk.getFullBuffer(mail.uidl);
			mail.setBody(body);
			mails.put(mail.uidl, mail);
		}
		folder.addElement(mail.uidl);
	}

	/**
	 * Is loadFromDisk in progress?
	 * @since 0.9.35
	 */
	public synchronized boolean isLoading() {
		return _loadInProgress != null;
	}

	/**
	 * Has loadFromDisk completed?
	 * @since 0.9.35
	 */
	public synchronized boolean isLoaded() {
		return _isLoaded;
	}

	/**
	 * Threaded. Returns immediately.
	 * This will not access the mailbox. Mailbox need not be ready.
	 * 
	 * @return success false if in progress already and nml will NOT be called back, true if nml will be called back
	 * @since 0.9.13
	 */
	public synchronized boolean loadFromDisk(NewMailListener nml) {
		if (_isLoaded || _loadInProgress != null)
			return false;
		if (_log.shouldDebug()) _log.debug("Loading folder " + folderName);
		Thread t = new I2PAppThread(new LoadMailRunner(nml), "Email loader");
		_loadInProgress = nml;
		try {
			t.start();
		} catch (Throwable e) {
			_loadInProgress = null;
			return false;
		}
		return true;
	}

	/** @since 0.9.34 */
	private class LoadMailRunner implements Runnable {
		private final NewMailListener _nml;

		public LoadMailRunner(NewMailListener nml) {
			_nml = nml;
		}

		public void run() {
			boolean result = false;
			try {
				blockingLoadFromDisk();
				if(!mails.isEmpty())
					result = true;
				if (_log.shouldDebug()) _log.debug("Folder loaded: " + folderName);
			} finally {
				synchronized(MailCache.this) {
					if (_loadInProgress == _nml)
						_loadInProgress = null;
					_isLoaded = true;
				}
				_nml.foundNewMail(result);
			}
		}
	}

	/**
	 * Blocking. Only call once!
	 * This will not access the mailbox. Mailbox need not be ready.
	 * 
	 * @since 0.9.13
	 */
	private void blockingLoadFromDisk() {
		synchronized(mails) {
			if (_isLoaded)
				throw new IllegalStateException();
			Collection<Mail> dmails = disk.getMails();
			for (Mail mail : dmails) {
				mails.put(mail.uidl, mail);
			}
		}
	}

	/**
	 * The ones known locally, which will include any known on the server, if connected.
	 * Will not include any marked for deletion.
	 * 
	 * This will not access the mailbox. Mailbox need not be ready.
	 * loadFromDisk() must have been called first.
	 * 
	 * @return non-null
	 * @since 0.9.13
	 */
	public String[] getUIDLs() {
		List<String> uidls = new ArrayList<String>(mails.size());
		synchronized(mails) {
			for (Mail mail : mails.values()) {
				if (!mail.markForDeletion)
					uidls.add(mail.uidl);
			}
		}
		return uidls.toArray(new String[uidls.size()]);
	}

	/**
	 * Fetch any needed data from pop3 server, unless mode is CACHE_ONLY,
	 * or this isn't the Inbox.
	 * Blocking unless mode is CACHE_ONLY.
	 * 
	 * @param uidl message id to get
	 * @param mode CACHE_ONLY to not pull from pop server
	 * @return An e-mail or null
	 */
        @SuppressWarnings({"unchecked", "rawtypes"})
	public Mail getMail(String uidl, FetchMode mode) {
		Mail mail = null, newMail = null;

		/*
		 * synchronize update to hashtable
		 */
		synchronized(mails) {
			mail = mails.get( uidl );
			if( mail == null ) {
				// if not in inbox, we can't fetch, this is what we have
				if (mailbox == null)
					return null;
				newMail = new Mail(uidl);
				// TODO really?
				mails.put( uidl, newMail );
			}
		}
		if( mail == null ) {
			mail = newMail;
			mail.setSize(mailbox.getSize(uidl));
		}
		if (mail.markForDeletion)
			return null;
		// if not in inbox, we can't fetch, this is what we have
		if (mailbox == null)
			return mail;

		long sz = mail.getSize();
		if (mode == FetchMode.HEADER && sz > 0 && sz <= FETCH_ALL_SIZE)
			mode = FetchMode.ALL;
			
		if (mode == FetchMode.HEADER) {
			if (!mail.hasHeader()) {
				Buffer buf = mailbox.getHeader(uidl);
				if (buf != null)
					mail.setHeader(buf);
			}
		} else if (mode == FetchMode.ALL) {
			if(!mail.hasBody()) {
				File file = new File(_context.getTempDir(), "susimail-new-" + _context.random().nextLong());
				Buffer rb = mailbox.getBody(uidl, new FileBuffer(file));
				if (rb != null) {
					mail.setBody(rb);
					if (disk.saveMail(mail) &&
					    !Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
						mailbox.queueForDeletion(mail.uidl);
					}
				}
			}
		} else {
			// else if it wasn't in cache, too bad
		}
		return mail;
	}

	/**
	 * Fetch any needed data from pop3 server.
	 * Mail objects are inserted into the requests.
	 * After this, call getUIDLs() to get all known mail UIDLs.
	 * MUST already be connected, otherwise returns false.
	 * Call only on inbox!
	 * 
	 * Blocking.
	 * 
	 * @param mode HEADER or ALL only
	 * @return true if any were fetched
	 * @since 0.9.13
	 */
        @SuppressWarnings({"unchecked", "rawtypes"})
	public boolean getMail(FetchMode mode) {
		if (mode == FetchMode.CACHE_ONLY)
			throw new IllegalArgumentException();
		if (mailbox == null) {
			if (_log.shouldDebug()) _log.debug("getMail() mode " + mode + " called on wrong folder " + getFolderName(), new Exception());
			return false;
		}
		boolean hOnly = mode == FetchMode.HEADER;
		
		Collection<String> popKnown = mailbox.getUIDLs();
		if (popKnown == null)
			return false;
		List<POP3Request> fetches = new ArrayList<POP3Request>();
		//  Fill in the answers from the cache and make a list of
		//  requests.to send off
		for (String uidl : popKnown) {
			Mail mail = null, newMail = null;
			boolean headerOnly = hOnly;

			/*
			 * synchronize update to hashtable
			 */
			synchronized(mails) {
				mail = mails.get( uidl );
				if( mail == null ) {
					newMail = new Mail(uidl);
					mails.put( uidl, newMail );
				}
			}
			if( mail == null ) {
				mail = newMail;
				mail.setSize(mailbox.getSize(uidl));
			}
			if (mail.markForDeletion)
				continue;
			long sz = mail.getSize();
			if (sz > 0 && sz <= FETCH_ALL_SIZE)
				headerOnly = false;
			if( headerOnly ) {
				if(!mail.hasHeader()) {
					if (disk.getMail(mail, true)) {
						if (_log.shouldDebug()) _log.debug("Loaded header from disk cache: " + uidl);
						// note that disk loaded the full body if it had it
						if (mail.hasBody() &&
							!Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
							// we already have it, send delete
							mailbox.queueForDeletion(mail.uidl);
						}
						continue;  // found on disk, woo
					}
					POP3Request pr = new POP3Request(mail, true, new MemoryBuffer(1024));
					fetches.add(pr);
				} else {
					if (mail.hasBody() &&
						!Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
						// we already have it, send delete
						mailbox.queueForDeletion(mail.uidl);
					}
				}
			} else {
				if(!mail.hasBody()) {
					if (disk.getMail(mail, false)) {
						if (_log.shouldDebug()) _log.debug("Loaded body from disk cache: " + uidl);
						// note that disk loaded the full body if it had it
						if (!Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
							// we already have it, send delete
							mailbox.queueForDeletion(mail.uidl);
						}
						continue;  // found on disk, woo
					}
					File file = new File(_context.getTempDir(), "susimail-new-" + _context.random().nextLong());
					POP3Request pr = new POP3Request(mail, false, new FileBuffer(file));
					fetches.add(pr);
				} else {
					if (!Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
						// we already have it, send delete
						mailbox.queueForDeletion(mail.uidl);
					}
				}
			}
		}

		boolean rv = false;
		if (!fetches.isEmpty()) {
			//  Send off the fetches
			// gaah compiler
			List foo = fetches;
			List<FetchRequest> bar = foo;
			mailbox.getBodies(bar);
			//  Process results
			for (POP3Request pr : fetches) {
				if (pr.getSuccess()) {
					Mail mail = pr.mail;
					if (!mail.hasHeader())
						mail.setNew(true);
					if (pr.getHeaderOnly()) {
						mail.setHeader(pr.getBuffer());
					} else {
						mail.setBody(pr.getBuffer());
					}
					rv = true;
					if (disk.saveMail(mail) && mail.hasBody() &&
					    !Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
						mailbox.queueForDeletion(mail.uidl);
					}
				}
			}
		}
		return rv;
	}

	/**
	 * Mark mail for deletion locally.
	 * Send delete requests to POP3 then quit and reconnect.
	 * No success/failure indication is returned.
	 * Does not delete from folder.
	 * 
	 * @since 0.9.13
	 */
	public void delete(String uidl) {
		delete(Collections.singleton(uidl));
	}

	/**
	 * Mark mail for deletion locally.
	 * Send delete requests to POP3 then quit and reconnect.
	 * No success/failure indication is returned.
	 * Does not delete from folder.
	 * 
	 * @since 0.9.13
	 */
	public void delete(Collection<String> uidls) {
		List<String> toDelete = new ArrayList<String>(uidls.size());
		for (String uidl : uidls) {
			disk.deleteMail(uidl);
			synchronized(mails) {
				Mail mail = mails.get(uidl);
				if (mail == null)
					continue;
				mail.markForDeletion = true;
				// now replace it with an empty one to save memory
				mail = new Mail(uidl);
				mail.markForDeletion = true;
				mails.put(uidl, mail);
			}
			toDelete.add(uidl);
		}
		if (toDelete.isEmpty())
			return;
		if (mailbox != null)
			mailbox.queueForDeletion(toDelete);
	}

	/**
	 *  Outgoing to POP3
	 */
	private static class POP3Request implements FetchRequest {
		public final Mail mail;
		private boolean headerOnly, success;
		public final Buffer buf;

		public POP3Request(Mail m, boolean hOnly, Buffer buffer) {
			mail = m;
			headerOnly = hOnly;
			buf = buffer;
		}

		public String getUIDL() {
			return mail.uidl;
		}

		/** @since 0.9.34 */
		public synchronized void setHeaderOnly(boolean headerOnly) {
			this.headerOnly = headerOnly;
		}

		public synchronized boolean getHeaderOnly() {
			return headerOnly;
		}

		/** @since 0.9.34 */
		public Buffer getBuffer() {
			return buf;
		}

		/** @since 0.9.34 */
		public synchronized void setSuccess(boolean success) {
			this.success = success;
		}

		/** @since 0.9.34 */
		public synchronized boolean getSuccess() {
			return success;
		}
	}
}
