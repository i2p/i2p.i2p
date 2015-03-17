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

import i2p.susi.webmail.pop3.POP3MailBox;

import java.util.Hashtable;

/**
 * @author user
 */
public class MailCache {
	
	public static final boolean FETCH_HEADER = true;
	public static final boolean FETCH_ALL = false;
	
	private POP3MailBox mailbox;
	private Hashtable mails;
	private Object synchronizer;
	
	MailCache( POP3MailBox mailbox ) {
		this.mailbox = mailbox;
		mails = new Hashtable();
		synchronizer = new Object();
	}
	/**
	 * Fetch any needed data from pop3 server.
	 * 
	 * @param uidl message id to get
	 * @param headerOnly fetch only header lines?
	 * @return An e-mail
	 */
	public Mail getMail( String uidl, boolean headerOnly ) {
		
		Mail mail = null, newMail = null;

		if( mailbox != null ) {
			/*
			 * synchronize update to hashtable
			 */
			synchronized( synchronizer ) {

				mail = (Mail)mails.get( uidl );
			
				if( mail == null ) {
					newMail = new Mail();
					mails.put( uidl, newMail );
				}
			}
			if( mail == null ) {
				mail = newMail;
				mail.uidl = uidl;
				mail.size = mailbox.getSize( uidl );
			}
			if( mail.size < 1024 )
				headerOnly = false;
			
			boolean parseHeaders = mail.header == null;
			
			if( headerOnly ) {
				if( mail.header == null )
					mail.header = mailbox.getHeader( uidl );
			}
			else {
				if( mail.body == null ) {
					mail.body = mailbox.getBody( uidl );
					if( mail.body != null ) {
						mail.header = mail.body;
						MailPart.parse( mail );
					}
				}
			}
			if( parseHeaders && mail.header != null )
				mail.parseHeaders();
		}
		if( mail != null && mail.deleted )
			mail = null;
		return mail;
	}
}
