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

import i2p.susi.util.ReadBuffer;
import i2p.susi.webmail.pop3.POP3MailBox;
import i2p.susi.webmail.pop3.POP3MailBox.FetchRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;

/**
 * @author user
 */
class MailCache {
	
	public static final boolean FETCH_HEADER = true;
	public static final boolean FETCH_ALL = false;
	
	private final POP3MailBox mailbox;
	private final Hashtable<String, Mail> mails;
	
	/** Includes header, headers are generally 1KB to 1.5 KB,
	 *  and bodies will compress well.
         */
	private static final int FETCH_ALL_SIZE = 3072;

	/**
	 * @param mailbox non-null
	 */
	MailCache( POP3MailBox mailbox ) {
		this.mailbox = mailbox;
		mails = new Hashtable<String, Mail>();
	}

	/**
	 * Fetch any needed data from pop3 server.
	 * 
	 * @param uidl message id to get
	 * @param headerOnly fetch only header lines?
	 * @return An e-mail or null
	 */
	public Mail getMail( String uidl, boolean headerOnly ) {
		
		Mail mail = null, newMail = null;

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
				mail.size = mailbox.getSize( uidl );
			}
			if( mail.size <= FETCH_ALL_SIZE)
				headerOnly = false;
			
			if( headerOnly ) {
				if(!mail.hasHeader())
					mail.setHeader(mailbox.getHeader(uidl));
			}
			else {
				if(!mail.hasBody()) {
					mail.setBody(mailbox.getBody(uidl));
				}
			}
		if( mail != null && mail.deleted )
			mail = null;
		return mail;
	}

	/**
	 * Fetch any needed data from pop3 server.
	 * Mail objects are inserted into the requests.
	 * 
	 * @since 0.9.13
	 */
	public void getMail(Collection<MailRequest> requests) {
		
		List<POP3Request> fetches = new ArrayList<POP3Request>();
		//  Fill in the answers from the cache and make a list of
		//  requests.to send off
		for (MailRequest mr : requests) {
			Mail mail = null, newMail = null;
			String uidl = mr.getUIDL();
			boolean headerOnly = mr.getHeaderOnly();

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
				mail.size = mailbox.getSize( uidl );
			}
			if(!mail.deleted) {
				mr.setMail(mail);
				if( mail.size <= FETCH_ALL_SIZE)
					headerOnly = false;
				if( headerOnly ) {
					if(!mail.hasHeader()) {
						POP3Request pr = new POP3Request(mr, mail, true);
						fetches.add(pr);
					}
				} else {
					if(!mail.hasBody()) {
						POP3Request pr = new POP3Request(mr, mail, false);
						fetches.add(pr);
					}
				}
			}
		}

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
					if (pr.getHeaderOnly()) {
						mail.setHeader(rb);
					} else {
						mail.setBody(rb);
					}
				}
			}
		}
	}

	/**
	 *  Incoming to us
	 */
	public interface MailRequest {
		public String getUIDL();
		public boolean getHeaderOnly();
		public void setMail(Mail mail);
	}

	/**
	 *  Outgoing to POP3
	 */
	private static class POP3Request implements FetchRequest {
		public final MailRequest request;
		public final Mail mail;
		private final boolean headerOnly;
		public ReadBuffer buf;

		public POP3Request(MailRequest req, Mail m, boolean hOnly) {
			request = req;
			mail = m;
			headerOnly = hOnly;
		}

		public String getUIDL() {
			return request.getUIDL();
		}

		public boolean getHeaderOnly() {
			return headerOnly;
		}

		public void setBuffer(ReadBuffer buffer) {
			buf = buffer;
		}
	}
}
