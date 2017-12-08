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

import i2p.susi.debug.Debug;
import i2p.susi.util.Config;
import i2p.susi.util.ReadBuffer;
import i2p.susi.webmail.pop3.POP3MailBox;
import i2p.susi.webmail.pop3.POP3MailBox.FetchRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;

/**
 * @author user
 */
class MailCache {
	
	public enum FetchMode {
		HEADER, ALL, CACHE_ONLY
	}
	
	private final POP3MailBox mailbox;
	private final Hashtable<String, Mail> mails;
	private final PersistentMailCache disk;
	
	/** Includes header, headers are generally 1KB to 1.5 KB,
	 *  and bodies will compress well.
         */
	private static final int FETCH_ALL_SIZE = 8192;

	/**
	 * @param mailbox non-null
	 */
	MailCache(POP3MailBox mailbox, 
		  String host, int port, String user, String pass) {
		this.mailbox = mailbox;
		mails = new Hashtable<String, Mail>();
		PersistentMailCache pmc = null;
		try {
			pmc = new PersistentMailCache(host, port, user, pass, PersistentMailCache.DIR_FOLDER);
			// TODO Drafts, Sent, Trash
		} catch (IOException ioe) {
			Debug.debug(Debug.ERROR, "Error creating disk cache: " + ioe);
		}
		disk = pmc;
		if (disk != null)
			loadFromDisk();
	}

	/**
	 * 
	 * @since 0.9.13
	 */
	private void loadFromDisk() {
		Collection<Mail> dmails = disk.getMails();
		for (Mail mail : dmails) {
			mails.put(mail.uidl, mail);
		}
	}

	/**
	 * The ones known locally, which will include any known on the server, if connected.
	 * Will not include any marked for deletion.
	 * 
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
	 * Fetch any needed data from pop3 server.
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
		int sz = mail.getSize();
		if (mode == FetchMode.HEADER && sz > 0 && sz <= FETCH_ALL_SIZE)
			mode = FetchMode.ALL;
			
		if (mode == FetchMode.HEADER) {
			if(!mail.hasHeader())
				mail.setHeader(mailbox.getHeader(uidl));
		} else if (mode == FetchMode.ALL) {
			if(!mail.hasBody()) {
				ReadBuffer rb = mailbox.getBody(uidl);
				if (rb != null) {
					mail.setBody(rb);
					if (disk != null && disk.saveMail(mail) &&
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
	 * 
	 * @param mode HEADER or ALL only
	 * @return true if any were fetched
	 * @since 0.9.13
	 */
        @SuppressWarnings({"unchecked", "rawtypes"})
	public boolean getMail(FetchMode mode) {
		if (mode == FetchMode.CACHE_ONLY)
			throw new IllegalArgumentException();
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
			int sz = mail.getSize();
			if (sz > 0 && sz <= FETCH_ALL_SIZE)
				headerOnly = false;
			if( headerOnly ) {
				if(!mail.hasHeader()) {
					if (disk != null) {
						if (disk.getMail(mail, true)) {
							Debug.debug(Debug.DEBUG, "Loaded header from disk cache: " + uidl);
							// note that disk loaded the full body if it had it
							if (mail.hasBody() &&
								!Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
								// we already have it, send delete
								mailbox.queueForDeletion(mail.uidl);
							}
							continue;  // found on disk, woo
						}
					}
					POP3Request pr = new POP3Request(mail, true);
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
					if (disk != null) {
						if (disk.getMail(mail, false)) {
							Debug.debug(Debug.DEBUG, "Loaded body from disk cache: " + uidl);
							// note that disk loaded the full body if it had it
							if (!Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
								// we already have it, send delete
								mailbox.queueForDeletion(mail.uidl);
							}
							continue;  // found on disk, woo
						}
					}
					POP3Request pr = new POP3Request(mail, false);
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
				ReadBuffer rb = pr.buf;
				if (rb != null) {
					Mail mail = pr.mail;
					if (!mail.hasHeader())
						mail.setNew(true);
					if (pr.getHeaderOnly()) {
						mail.setHeader(rb);
					} else {
						mail.setBody(rb);
					}
					rv = true;
					if (disk != null) {
						if (disk.saveMail(mail) && mail.hasBody() &&
						    !Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
							mailbox.queueForDeletion(mail.uidl);
						}
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
	 * 
	 * @since 0.9.13
	 */
	public void delete(Collection<String> uidls) {
		List<String> toDelete = new ArrayList<String>(uidls.size());
		for (String uidl : uidls) {
			if (disk != null)
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
		mailbox.queueForDeletion(toDelete);
	}

	/**
	 *  Outgoing to POP3
	 */
	private static class POP3Request implements FetchRequest {
		public final Mail mail;
		private final boolean headerOnly;
		public ReadBuffer buf;

		public POP3Request(Mail m, boolean hOnly) {
			mail = m;
			headerOnly = hOnly;
		}

		public String getUIDL() {
			return mail.uidl;
		}

		public boolean getHeaderOnly() {
			return headerOnly;
		}

		public void setBuffer(ReadBuffer buffer) {
			buf = buffer;
		}
	}
}
