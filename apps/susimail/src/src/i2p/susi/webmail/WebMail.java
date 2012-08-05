/*
 * Created on 04.11.2004
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
 * $Revision: 1.2 $
 */
package i2p.susi.webmail;

import i2p.susi.debug.Debug;
import i2p.susi.util.Config;
import i2p.susi.util.Folder;
import i2p.susi.util.ReadBuffer;
import i2p.susi.webmail.Messages;
import i2p.susi.webmail.encoding.DecodingException;
import i2p.susi.webmail.encoding.Encoding;
import i2p.susi.webmail.encoding.EncodingException;
import i2p.susi.webmail.encoding.EncodingFactory;
import i2p.susi.webmail.pop3.POP3MailBox;
import i2p.susi.webmail.smtp.SMTPClient;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import net.i2p.I2PAppContext;

/**
 * @author susi23
 */
public class WebMail extends HttpServlet
{
	/*
	 * set to true, if its a release build
	 */
	private static final boolean RELEASE = true;
	/*
	 * increase version number for every release
	 */
	private static final int version = 13;
	
	private static final long serialVersionUID = 1L;
	
	private static final String DEFAULT_HOST = "127.0.0.1";
	private static final int DEFAULT_POP3PORT = 7660;
	private static final int DEFAULT_SMTPPORT = 7659;
	
	private static final int STATE_AUTH = 1;
	private static final int STATE_LIST = 2;
	private static final int STATE_SHOW = 3;
	private static final int STATE_NEW = 4;
	
	private static final String myself = "/susimail/susimail";
	
	/*
	 * form keys on login page
	 */
	private static final String LOGIN = "login";
	private static final String USER = "user";
	private static final String PASS = "pass";
	private static final String HOST = "host";
	private static final String POP3 = "pop3";
	private static final String SMTP = "smtp";
	
	/*
	 * button names
	 */
	private static final String LOGOUT = "logout";
	private static final String RELOAD = "reload";
	private static final String REFRESH = "refresh";
	private static final String NEW = "new";
	private static final String REPLY = "reply";
	private static final String REPLYALL = "replyall";
	private static final String FORWARD = "forward";
	private static final String DELETE = "delete";
	private static final String REALLYDELETE = "really_delete";
	private static final String SHOW = "show";
	private static final String DOWNLOAD = "download";
	
	private static final String MARKALL = "markall";
	private static final String CLEAR = "clearselection";
	private static final String INVERT = "invertselection";
	
	private static final String PREVPAGE = "prevpage";
	private static final String NEXTPAGE = "nextpage";
	private static final String FIRSTPAGE = "firstpage";
	private static final String LASTPAGE = "lastpage";
	private static final String PAGESIZE = "pagesize";
	private static final String SETPAGESIZE = "setpagesize";
	
	private static final String SEND = "send";
	private static final String CANCEL = "cancel";
	private static final String DELETE_ATTACHMENT = "delete_attachment";
	
	private static final String NEW_FROM = "new_from";
	private static final String NEW_SUBJECT = "new_subject";
	private static final String NEW_TO = "new_to";
	private static final String NEW_CC = "new_cc";
	private static final String NEW_BCC = "new_bcc";
	private static final String NEW_TEXT = "new_text";
	private static final String NEW_FILENAME = "new_filename";
	private static final String NEW_UPLOAD = "new_upload";
	private static final String NEW_BCC_TO_SELF = "new_bcc_to_self";
	
	private static final String LIST = "list";
	private static final String PREV = "prev";
	private static final String NEXT = "next";
	private static final String SORT_ID = "sort_id";
	private static final String SORT_SENDER = "sort_sender";
	private static final String SORT_SUBJECT = "sort_subject";
	private static final String SORT_DATE = "sort_date";
	private static final String SORT_SIZE = "sort_size";

	private static final boolean SHOW_HTML = true;
	private static final boolean TEXT_ONLY = false;
	
	/*
	 * name of configuration properties
	 */
	private static final String CONFIG_HOST = "host";

	private static final String CONFIG_PORTS_FIXED = "ports.fixed";
	private static final String CONFIG_PORTS_POP3 = "ports.pop3";
	private static final String CONFIG_PORTS_SMTP = "ports.smtp";

	private static final String CONFIG_SENDER_FIXED = "sender.fixed";
	private static final String CONFIG_SENDER_DOMAIN = "sender.domain";
	
	private static final String CONFIG_COMPOSER_COLS = "composer.cols";
	private static final String CONFIG_COMPOSER_ROWS = "composer.rows";

	private static final String CONFIG_BCC_TO_SELF = "composer.bcc.to.self";

	private static final String RC_PROP_THEME = "routerconsole.theme";
	private static final String RC_PROP_UNIVERSAL_THEMING = "routerconsole.theme.universal";
	private static final String CONFIG_THEME = "theme";
	private static final String DEFAULT_THEME = "light";

	private static final String spacer = "&nbsp;&nbsp;&nbsp;";
	private static final String thSpacer = "<th>&nbsp;</th>\n";
	/**
	 * sorts Mail objects by id field
	 * 
	 * @author susi
	 */
	private static class IDSorter implements Comparator<String> {
		private MailCache mailCache;
		
		/**
		 * Set MailCache object, where to get Mails from
		 * @param mailCache
		 */
		public IDSorter( MailCache mailCache )
		{
			this.mailCache = mailCache;
		}
		
		/* (non-Javadoc)
		 * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
		 */
		public int compare(String arg0, String arg1) {
			Mail a = mailCache.getMail( arg0, MailCache.FETCH_HEADER );
			Mail b = mailCache.getMail( arg1, MailCache.FETCH_HEADER );
			return a.id - b.id;
		}		
	}

	/**
	 * sorts Mail objects by sender field
	 * 
	 * @author susi
	 */
	private static class SenderSorter implements Comparator<String> {
		private MailCache mailCache;
		
		/**
		 * Set MailCache object, where to get Mails from
		 * @param mailCache
		 */
		public SenderSorter( MailCache mailCache )
		{
			this.mailCache = mailCache;
		}
		
		/* (non-Javadoc)
		 * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
		 */
		public int compare(String arg0, String arg1) {
			Mail a = mailCache.getMail( arg0, MailCache.FETCH_HEADER );
			Mail b = mailCache.getMail( arg1, MailCache.FETCH_HEADER );
			return a.formattedSender.compareToIgnoreCase( b.formattedSender );
		}		
	}

	/**
	 * sorts Mail objects by subject field
	 * @author susi
	 */
	private static class SubjectSorter implements Comparator<String> {

		private MailCache mailCache;
		/**
		 * Set MailCache object, where to get Mails from
		 * @param mailCache
		 */
		public SubjectSorter( MailCache mailCache )
		{
			this.mailCache = mailCache;
		}
		
		/* (non-Javadoc)
		 * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
		 */
		public int compare(String arg0, String arg1) {
			Mail a = mailCache.getMail( arg0, MailCache.FETCH_HEADER );
			Mail b = mailCache.getMail( arg1, MailCache.FETCH_HEADER );
			return a.formattedSubject.compareToIgnoreCase( b.formattedSubject );
		}		
	}

	/**
	 * sorts Mail objects by date field
	 * @author susi
	 */
	private static class DateSorter implements Comparator<String> {

		private MailCache mailCache;
		/**
		 * Set MailCache object, where to get Mails from
		 * @param mailCache
		 */
		public DateSorter( MailCache mailCache )
		{
			this.mailCache = mailCache;
		}
		
		/* (non-Javadoc)
		 * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
		 */
		public int compare(String arg0, String arg1) {
			Mail a = mailCache.getMail( arg0, MailCache.FETCH_HEADER );
			Mail b = mailCache.getMail( arg1, MailCache.FETCH_HEADER );
			return a.date != null ? ( b.date != null ? a.date.compareTo( b.date ) : -1 ) : ( b.date != null ? 1 : 0 );
		}		
	}
	/**
	 * sorts Mail objects by message size
	 * @author susi
	 */
	private static class SizeSorter implements Comparator<String> {

		private MailCache mailCache;
		/**
		 * Set MailCache object, where to get Mails from
		 * @param mailCache
		 */
		public SizeSorter( MailCache mailCache )
		{
			this.mailCache = mailCache;
		}
		
		/* (non-Javadoc)
		 * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
		 */
		public int compare(String arg0, String arg1) {
			Mail a = mailCache.getMail( arg0, MailCache.FETCH_HEADER );
			Mail b = mailCache.getMail( arg1, MailCache.FETCH_HEADER );
			return a.size - b.size;
		}		
	}
	
	/**
	 * data structure to hold any persistent data (to store them in session dictionary)
	 * @author susi
	 */
	private static class SessionObject {
		boolean pageChanged, markAll, clear, invert;;
		int state, smtpPort;
		POP3MailBox mailbox;
		MailCache mailCache;
		Folder folder;
		String user, pass, host, error, info;
		String replyTo, replyCC;
		String subject, body, showUIDL;
		public MailPart showAttachment;
		public String sentMail;
		public ArrayList attachments;
		public boolean reallyDelete;
		String themePath, imgPath;
		
		
		SessionObject()
		{
			state = STATE_AUTH;
		}
	}
	
	static {
			Debug.setLevel( RELEASE ? Debug.ERROR : Debug.DEBUG );
			Config.setPrefix( "susimail" );
	}

	/**
	 * returns html string of a form button with name and label
	 * 
	 * @param name
	 * @param label
	 * @return html string
	 */
	private static String button( String name, String label )
	{
		return "<input type=\"submit\" class=\"" + name + "\" name=\"" + name + "\" value=\"" + label + "\">";
	}
	/**
	 * returns html string of a disabled form button with name and label
	 * 
	 * @param name
	 * @param label
	 * @return html string
	 */
	private static String button2( String name, String label )
	{
		return "<input type=\"submit\" name=\"" + name + "\" value=\"" + label + "\" disabled>";
	}
	/**
	 * returns a html string of the label and two imaged links using the parameter name
	 * (used for sorting buttons in folder view)
	 * 
	 * @param name
	 * @param label
	 * @return
	 */
	private static String sortHeader( String name, String label, String imgPath )
	{
		return "" + label + "&nbsp;<a href=\"" + myself + "?" + name + "=up\"><img src=\"" + imgPath + "3up.png\" border=\"0\" alt=\"^\"></a><a href=\"" + myself + "?" + name + "=down\"><img src=\"" + imgPath + "3down.png\" border=\"0\" alt=\"v\"></a>";
	}
	/**
	 * check, if a given button "was pressed" in the received http request
	 * 
	 * @param request
	 * @param key
	 * @return
	 */
	private static boolean buttonPressed( RequestWrapper request, String key )
	{
		String value = request.getParameter( key );
		return value != null && value.length() > 0;
	}
	/**
	 * recursively render all mail body parts
	 * 
	 * 1. if type is multipart/alternative, look for text/plain section and ignore others
	 * 2. if type is multipart/*, recursively call all these parts
	 * 3. if type is text/plain (or mail is not mime), print out
	 * 4. in all other cases print out message, that part is not displayed
	 * 
	 * @param out
	 * @param mailPart
	 * @param level is increased by recursively calling sub parts
	 */
	private static void showPart( PrintWriter out, MailPart mailPart, int level, boolean html )
	{
		String br = html ? "<br>\r\n" : "\r\n";
		
		if( html ) {
			out.println( "<!-- " );
		
			for( int i = 0; i < mailPart.headerLines.length; i++ )
				out.println( mailPart.headerLines[i] );
		
			out.println( "-->" );
		}
		
		if( mailPart.multipart ) {
			if( mailPart.type.compareTo( "multipart/alternative" ) == 0 ) {
				MailPart chosen = null;
				for( ListIterator li = mailPart.parts.listIterator(); li.hasNext(); ) {
					MailPart subPart = (MailPart)li.next();
					if( subPart.type != null && subPart.type.compareTo( "text/plain" ) == 0 )
						chosen = subPart;
				}
				if( chosen != null ) {
					showPart( out, chosen, level + 1, html );
					return;
				}
			}
			for( ListIterator li = mailPart.parts.listIterator(); li.hasNext(); ) {
				MailPart part = (MailPart)li.next();
				showPart( out, part, level + 1, html );
			}
		}
		else if( mailPart.message ) {
			for( ListIterator li = mailPart.parts.listIterator(); li.hasNext(); ) {
				MailPart part = (MailPart)li.next();
				showPart( out, part, level + 1, html );
			}			
		}
		else {
			boolean showBody = false;
			boolean prepareAttachment = false;
			String reason = "";
			StringBuilder body = null;
			
			String ident = quoteHTML(
					( mailPart.description != null ? mailPart.description + ", " : "" ) +
					( mailPart.filename != null ? mailPart.filename + ", " : "" ) +
					( mailPart.name != null ? mailPart.name + ", " : "" ) +
					( mailPart.type != null ? mailPart.type : _("unknown") ) );
			
			if( level == 0 && mailPart.version == null ) {
				/*
				 * not a MIME mail, so simply print it literally
				 */
				showBody = true;
			}
			if( showBody == false && mailPart.type != null ) {
				if( mailPart.type.compareTo( "text/plain" ) == 0 ) {
					showBody = true;
				}
				else
					prepareAttachment = true;
			}
			if( showBody ) {			
				String encoding = mailPart.encoding;
				if( encoding == null ) {
					encoding = "7bit";
					reason += _("Warning: no transfer encoding found, fallback to 7bit.") + br;
				}
				Encoding e = EncodingFactory.getEncoding( encoding );
				if( e == null ) {
					showBody = false;
					reason += _("No encoder found for encoding \\''{0}\\''.", quoteHTML( encoding ));
				}
				else {
					String charset = mailPart.charset;
					if( charset == null ) {
						charset = "US-ASCII";
						reason += _("Warning: no charset found, fallback to US-ASCII.") + br;
					}
					try {
						ReadBuffer decoded = e.decode( mailPart.buffer.content, mailPart.beginBody, mailPart.end - mailPart.beginBody );
						BufferedReader reader = new BufferedReader( new InputStreamReader( new ByteArrayInputStream( decoded.content, decoded.offset, decoded.length ), charset ) );
						body = new StringBuilder();
						String line;
						while( ( line = reader.readLine() ) != null ) {
							body.append( quoteHTML( line ) );
							body.append( br );
						}
					}
					catch( UnsupportedEncodingException uee ) {
						showBody = false;
						reason = _("Charset \\''{0}\\'' not supported.", quoteHTML( mailPart.charset )) + br;
					}
					catch (Exception e1) {
						showBody = false;
						reason += _("Part ({0}) not shown, because of {1}", ident, e1.getClass().getName()) + br;
					}
				}
			}
			if( html )
				out.println( "<tr class=\"mailbody\"><td colspan=\"2\" align=\"center\">" );
			if( reason != null && reason.length() > 0 ) {
				if( html )
					out.println( "<p class=\"info\">");
				out.println( reason );
				if( html )
					out.println( "</p>" );
			}
			if( showBody ) {
				if( html )
					out.println( "<p class=\"mailbody\">" );
				out.println( body.toString() );
				if( html )
					out.println( "</p>" );
			}
			if( prepareAttachment ) {
				if( html ) {
					out.println( "<p class=\"mailbody\">" );
					out.println( "<a target=\"_blank\" href=\"" + myself + "?" + DOWNLOAD + "=" + mailPart.hashCode() + "\">" + _("Download") + "</a> " + _("attachment ({0}).", ident) + " " + _("File is packed into a zipfile for security reasons.") );
					out.println( "</p>" );					
				}
				else {
					out.println( _("Attachment ({0}).", ident) );
				}
			}
			if( html )
				out.println( "</td></tr>" );
		}
	}
	/**
	 * prepare line for presentation between html tags
	 * 
	 * - quote html tags
	 * 
	 * @param line
	 * @return
	 */
	private static String quoteHTML( String line )
	{
		if( line != null )
			line = line.replaceAll( "<", "&lt;" ).replaceAll( ">", "&gt;" );
		else
			line = "";
		return line;
	}
	/**
	 * 
	 * @param sessionObject
	 * @param request
	 */
	private static void processLogin( SessionObject sessionObject, RequestWrapper request )
	{
		if( sessionObject.state == STATE_AUTH ) {
			String user = request.getParameter( USER );
			String pass = request.getParameter( PASS );
			String host = request.getParameter( HOST );
			String pop3Port = request.getParameter( POP3 );
			String smtpPort = request.getParameter( SMTP );
			String fixedPorts = Config.getProperty( CONFIG_PORTS_FIXED, "true" );
			if( fixedPorts.compareToIgnoreCase( "false" ) != 0 ) {
				host = Config.getProperty( CONFIG_HOST, DEFAULT_HOST );
				pop3Port = Config.getProperty( CONFIG_PORTS_POP3, "" + DEFAULT_POP3PORT );
				smtpPort = Config.getProperty( CONFIG_PORTS_SMTP, "" + DEFAULT_SMTPPORT );
			}
			boolean doContinue = true;

			/*
			 * security :(
			 */
			if( buttonPressed( request, LOGIN ) ) {
				
				if( user == null || user.length() == 0 ) {
					sessionObject.error += _("Need username for authentication.") + "<br>";
					doContinue = false;
				}
				if( pass == null || pass.length() == 0 ) {
					sessionObject.error += _("Need password for authentication.") + "<br>";
					doContinue = false;
				}
				if( host == null || host.length() == 0 ) {
					sessionObject.error += _("Need hostname for connect.") + "<br>";
					doContinue = false;
				}
				int pop3PortNo = 0;
				if( pop3Port == null || pop3Port.length() == 0 ) {
					sessionObject.error += _("Need port number for pop3 connect.") + "<br>";
					doContinue = false;
				}
				else {
					try {
						pop3PortNo = Integer.parseInt( pop3Port );
						if( pop3PortNo < 0 || pop3PortNo > 65535 ) {
							sessionObject.error += _("POP3 port number is not in range 0..65535.") + "<br>";
							doContinue = false;
						}
					}
					catch( NumberFormatException nfe )
					{
						sessionObject.error += _("POP3 port number is invalid.") + "<br>";
						doContinue = false;
					}
				}
				int smtpPortNo = 0;
				if( smtpPort == null || smtpPort.length() == 0 ) {
					sessionObject.error += _("Need port number for smtp connect.") + "<br>";
					doContinue = false;
				}
				else {
					try {
						smtpPortNo = Integer.parseInt( smtpPort );
						if( smtpPortNo < 0 || smtpPortNo > 65535 ) {
							sessionObject.error += _("SMTP port number is not in range 0..65535.") + "<br>";
							doContinue = false;
						}
					}
					catch( NumberFormatException nfe )
					{
						sessionObject.error += _("SMTP port number is invalid.") + "<br>";
						doContinue = false;
					}
				}
				if( doContinue ) {
					sessionObject.mailbox = new POP3MailBox( host, pop3PortNo, user, pass );
					if( sessionObject.mailbox.isConnected() ) {
						sessionObject.user = user;
						sessionObject.pass = pass;
						sessionObject.host = host;
						sessionObject.smtpPort = smtpPortNo;
						sessionObject.state = STATE_LIST;
						sessionObject.mailCache = new MailCache( sessionObject.mailbox );
						sessionObject.folder = new Folder();
						sessionObject.folder.setElements( sessionObject.mailbox.getUIDLs() );
						sessionObject.folder.addSorter( SORT_ID, new IDSorter( sessionObject.mailCache ) );
						sessionObject.folder.addSorter( SORT_SENDER, new SenderSorter( sessionObject.mailCache ) );
						sessionObject.folder.addSorter( SORT_SUBJECT, new SubjectSorter( sessionObject.mailCache ) );
						sessionObject.folder.addSorter( SORT_DATE, new DateSorter( sessionObject.mailCache ) );
						sessionObject.folder.addSorter( SORT_SIZE, new SizeSorter( sessionObject.mailCache ) );
						sessionObject.folder.setSortingDirection( Folder.DOWN );
						sessionObject.reallyDelete = false;
					}
					else {
						sessionObject.error += sessionObject.mailbox.lastError();
						sessionObject.mailbox.close();
						sessionObject.mailbox = null;
					}
				}
			}
		}
	}
	/**
	 * 
	 * @param sessionObject
	 * @param request
	 */
	private static void processLogout( SessionObject sessionObject, RequestWrapper request )
	{
		if( buttonPressed( request, LOGOUT ) ) {
			HttpSession session = request.getSession();
			session.removeAttribute( "sessionObject" );
			session.invalidate();
			if( sessionObject.mailbox != null ) {
				sessionObject.mailbox.close();
				sessionObject.mailbox = null;
			}
			sessionObject.info += _("User logged out.") + "<br>";
			sessionObject.state = STATE_AUTH;
		}
		else if( sessionObject.mailbox == null ) {
			sessionObject.error += _("Internal error, lost connection.") + "<br>";
			sessionObject.state = STATE_AUTH;
		}
	}
	/**
	 * Process all buttons, which possibly change internal state.
	 * 
	 * @param sessionObject
	 * @param request
	 */
	private static void processStateChangeButtons(SessionObject sessionObject, RequestWrapper request )
	{
		/*
		 * LOGIN/LOGOUT
		 */
		if( sessionObject.state == STATE_AUTH )
			processLogin( sessionObject, request );

		if( sessionObject.state != STATE_AUTH )
			processLogout( sessionObject, request );

		/*
		 *  compose dialog
		 */
		if( sessionObject.state == STATE_NEW ) {
			if( buttonPressed( request, CANCEL ) )
				sessionObject.state = STATE_LIST;
		
			else if( buttonPressed( request, SEND ) )
				if( sendMail( sessionObject, request ) )
					sessionObject.state = STATE_LIST;
		}
		/*
		 * message dialog
		 */
		if( sessionObject.state == STATE_SHOW ) {
			if( buttonPressed( request, LIST ) ) { 
				sessionObject.state = STATE_LIST;
			}
		}
		/*
		 * buttons on both folder and message dialog
		 */
		if( sessionObject.state == STATE_SHOW || sessionObject.state == STATE_LIST ) {
			if( buttonPressed( request, NEW ) ) {
				sessionObject.state = STATE_NEW;
			}
			
			boolean reply = false;
			boolean replyAll = false;
			boolean forward = false;
			sessionObject.replyTo = null;
			sessionObject.replyCC = null;
			sessionObject.body = null;
			sessionObject.subject = null;
			
			if( buttonPressed( request, REPLY ) )
				reply = true;
			
			if( buttonPressed( request, REPLYALL ) ) {
				replyAll = true;
			}
			if( buttonPressed( request, FORWARD ) ) {
				forward = true;
			}
			if( reply || replyAll || forward ) {
				/*
				 * try to find message
				 */
				String uidl = null;
				if( sessionObject.state == STATE_LIST ) {
					int pos = getCheckedMessage( request );
					uidl = (String)sessionObject.folder.getElementAtPosXonCurrentPage( pos );
				}
				else {
					uidl = sessionObject.showUIDL;
				}
				
				if( uidl != null ) {
					Mail mail = sessionObject.mailCache.getMail( uidl, MailCache.FETCH_ALL );
					if( mail.part == null ) {
						mail.part = new MailPart();
						mail.part.parse( mail.body );
					}
					/*
					 * extract original sender from Reply-To: or From:
					 */
					if( mail.part != null ) {
						if( reply || replyAll ) {
							if( mail.reply != null && Mail.validateAddress( mail.reply ) )
								sessionObject.replyTo = Mail.getAddress( mail.reply );
							else if( mail.sender != null && Mail.validateAddress( mail.sender ) )
								sessionObject.replyTo = Mail.getAddress( mail.sender );
							sessionObject.subject = "Re: " + mail.formattedSubject;
							StringWriter text = new StringWriter();
							PrintWriter pw = new PrintWriter( text );
							pw.println( _("On {0} {1} wrote:", mail.formattedDate, sessionObject.replyTo) );
							StringWriter text2 = new StringWriter();
							PrintWriter pw2 = new PrintWriter( text2 );
							showPart( pw2, mail.part, 0, TEXT_ONLY );
							pw2.flush();
							String[] lines = text2.toString().split( "\r\n" );
							for( int i = 0; i < lines.length; i++ )
								pw.println( "> " + lines[i] );
							pw.flush();
							sessionObject.body = text.toString();
						}
						if( replyAll ) {
							/*
							 * extract additional recipients
							 */
							StringBuilder buf = new StringBuilder();
							String pad = "";
							if( mail.to != null ) {
								for( int i = 0; i < mail.to.length; i++ ) {
									buf.append( pad );
									buf.append( (String)mail.to[i] );
									pad = ", ";
								}
							}
							if( mail.cc != null ) {
								for( int i = 0; i < mail.cc.length; i++ ) {
									buf.append( pad );
									buf.append( (String)mail.cc[i] );
									pad = ", ";
								}
							}
							if( buf.length() > 0 )
								sessionObject.replyCC = buf.toString();
						}
						if( forward ) {
							sessionObject.subject = "FWD: " + mail.formattedSubject;
							String sender = null;
							if( mail.reply != null && Mail.validateAddress( mail.reply ) )
								sender = Mail.getAddress( mail.reply );
							else if( mail.sender != null && Mail.validateAddress( mail.sender ) )
								sender = Mail.getAddress( mail.sender );
							
							StringWriter text = new StringWriter();
							PrintWriter pw = new PrintWriter( text );
							pw.println();
							pw.println();
							pw.println();
							pw.println( "---- " + _("begin forwarded mail") + " ----" );
							pw.println( "From: " + sender );
							if( mail.to != null ) {
								String pad = "To: ";
								for( int i = 0; i < mail.to.length; i++ ) {
									pw.println( pad );
									pw.println( (String)mail.to[i] );
									pad = "    ";
								}
							}
							if( mail.cc != null ) {
								String pad = "Cc: ";
								for( int i = 0; i < mail.cc.length; i++ ) {
									pw.println( pad );
									pw.println( (String)mail.cc[i] );
									pad = "    ";
								}
							}
							if( mail.dateString != null )
								pw.print( "Date: " + mail.dateString );
							pw.println();
							showPart( pw, mail.part, 0, TEXT_ONLY );
							pw.println( "----  " + _("end forwarded mail") + "  ----" );
							pw.flush();
							sessionObject.body = text.toString();
						}
						sessionObject.state = STATE_NEW;
					}
					else {
						sessionObject.error += _("Could not fetch mail body.") + "<br>";
					}
				}
			}
		}
		/*
		 * folder view
		 */
		if( sessionObject.state == STATE_LIST || sessionObject.state == STATE_SHOW) {
			/*
			 * check if user wants to view a message
			 */
			String show = request.getParameter( SHOW );
			if( show != null && show.length() > 0 ) {
				try {

					int id = Integer.parseInt( show );
					
					if( id >= 0 && id < sessionObject.folder.getPageSize() ) {
						String uidl = (String)sessionObject.folder.getElementAtPosXonCurrentPage( id );
						if( uidl != null ) {
							sessionObject.state = STATE_SHOW;
							sessionObject.showUIDL = uidl;
						}
					}
				}
				catch( NumberFormatException nfe )
				{
					sessionObject.error += _("Message id not valid.") + "<br>";
				}
			}
		}
	}
	/**
	 * @param request
	 * @return
	 */
	private static int getCheckedMessage(RequestWrapper request) {
		for( Enumeration e = request.getParameterNames(); e.hasMoreElements(); ) {
			String parameter = (String)e.nextElement();
			if( parameter.startsWith( "check" ) && request.getParameter( parameter ).compareTo( "1" ) == 0 ) {
				String number = parameter.substring( 5 );
				try {
					int n = Integer.parseInt( number );
					return n;
				}
				catch( NumberFormatException nfe ) {
					
				}
			}
		}
		return -1;
	}
	/**
	 * @param sessionObject
	 * @param request
	 */
	private static void processGenericButtons(SessionObject sessionObject, RequestWrapper request)
	{
		if( buttonPressed( request, RELOAD ) ) {
			Config.reloadConfiguration();
		}
		if( buttonPressed( request, REFRESH ) ) {
			sessionObject.mailbox.refresh();
			sessionObject.folder.setElements( sessionObject.mailbox.getUIDLs() );
			sessionObject.pageChanged = true;
		}
	}
	/**
	 * process buttons of compose message dialog
	 * @param sessionObject
	 * @param request
	 */
	private static void processComposeButtons(SessionObject sessionObject, RequestWrapper request)
	{
		if( buttonPressed( request, NEW_UPLOAD ) ) {
			String filename = request.getFilename( NEW_FILENAME );
			int i = filename.lastIndexOf( "/" );
			if( i != - 1 )
				filename = filename.substring( i + 1 );
			i = filename.lastIndexOf( "\\" );
			if( i != -1 )
				filename = filename.substring( i + 1 );
			if( filename != null && filename.length() > 0 ) {
				InputStream in = request.getInputStream( NEW_FILENAME );
				int l;
				try {
					l = in.available();
					if( l > 0 ) {
						byte buf[] = new byte[l];
						in.read( buf );
						Attachment attachment = new Attachment();
						attachment.setFileName( filename );
						String contentType = request.getContentType( NEW_FILENAME );
						Encoding encoding;
						String encodeTo;
						if( contentType.toLowerCase(Locale.US).startsWith( "text/" ) )
							encodeTo = "quoted-printable";
						else
							encodeTo = "base64";
						encoding = EncodingFactory.getEncoding( encodeTo );
						try {
							if( encoding != null ) {
								attachment.setData( encoding.encode( buf ) );
								attachment.setTransferEncoding( encodeTo );
								attachment.setContentType( contentType );
								if( sessionObject.attachments == null )
									sessionObject.attachments = new ArrayList();
								sessionObject.attachments.add( attachment );
							}
							else {
								sessionObject.error += _("No Encoding found for {0}", encodeTo) + "<br>";
							}
						}
						catch (EncodingException e1) {
							sessionObject.error += _("Could not encode data: {0}", e1.getMessage());
						}
					}
				}
				catch (IOException e) {
					sessionObject.error += _("Error reading uploaded file: {0}", e.getMessage()) + "<br>";
				}
			}
		}
		else if( sessionObject.attachments != null && buttonPressed( request, DELETE_ATTACHMENT ) ) {
			for( Enumeration e = request.getParameterNames(); e.hasMoreElements(); ) {
				String parameter = (String)e.nextElement();
				if( parameter.startsWith( "check" ) && request.getParameter( parameter ).compareTo( "1" ) == 0 ) {
					String number = parameter.substring( 5 );
					try {
						int n = Integer.parseInt( number );
						for( int i = 0; i < sessionObject.attachments.size(); i++ ) {
							Attachment attachment = (Attachment)sessionObject.attachments.get( i );
							if( attachment.hashCode() == n ) {
								sessionObject.attachments.remove( i );
								break;
							}
						}
					}
					catch( NumberFormatException nfe ) {
						
					}
				}
			}			
		}
	}
	/**
	 * process buttons of message view
	 * @param sessionObject
	 * @param request
	 */
	private static void processMessageButtons(SessionObject sessionObject, RequestWrapper request)
	{
		if( buttonPressed( request, PREV ) ) {
			String uidl = (String)sessionObject.folder.getPreviousElement( sessionObject.showUIDL );
			if( uidl != null )
				sessionObject.showUIDL = uidl;
		}
		if( buttonPressed( request, NEXT ) ) {
			String uidl = (String)sessionObject.folder.getNextElement( sessionObject.showUIDL );
			if( uidl != null )
				sessionObject.showUIDL = uidl;
		}
		
		sessionObject.reallyDelete = buttonPressed( request, DELETE );
		
		if( buttonPressed( request, REALLYDELETE ) ) {
			/*
			 * first find the next message
			 */
			String nextUIDL = (String)sessionObject.folder.getNextElement( sessionObject.showUIDL );
			if( nextUIDL == null ) {
				/*
				 * nothing found? then look for the previous one
				 */
				nextUIDL = (String)sessionObject.folder.getPreviousElement( sessionObject.showUIDL );
				if( nextUIDL == null )
					/*
					 * still nothing found? then this was the last message, so go back to the folder
					 */
					sessionObject.state = STATE_LIST;
			}
			sessionObject.mailbox.delete( sessionObject.showUIDL );
			sessionObject.mailbox.performDelete();
			sessionObject.folder.setElements( sessionObject.mailbox.getUIDLs() );
			sessionObject.showUIDL = nextUIDL;
		}
		
		String str = request.getParameter( DOWNLOAD );
		if( str != null ) {
			try {
				int hashCode = Integer.parseInt( str );
				Mail mail = sessionObject.mailCache.getMail( sessionObject.showUIDL, MailCache.FETCH_ALL );
				MailPart part = getMailPartFromHashCode( mail.part, hashCode );
				if( part != null )
					sessionObject.showAttachment = part;
			}
			catch( NumberFormatException nfe ) {
				sessionObject.error += _("Error parsing download parameter.");
			}
		}
	}
	/**
	 * @param hashCode
	 * @return
	 */
	private static MailPart getMailPartFromHashCode( MailPart part, int hashCode )
	{
		if( part == null )
			return null;
		
		if( part.hashCode() == hashCode )
			return part;
		
		if( part.multipart || part.message ) {
			for( Iterator it = part.parts.iterator(); it.hasNext(); ) {
				MailPart subPart = getMailPartFromHashCode( (MailPart)it.next(), hashCode );
				if( subPart != null )
					return subPart;
			}
		}
		return null;
	}
	/**
	 * process buttons of folder view
	 * @param sessionObject
	 * @param request
	 */
	private static void processFolderButtons(SessionObject sessionObject, RequestWrapper request)
	{
		/*
		 * process paging buttons
		 */
		String str = request.getParameter( PAGESIZE );
		if( str != null && str.length() > 0 ) {
			try {
				int pageSize = Integer.parseInt( str );
				int oldPageSize = sessionObject.folder.getPageSize();
				if( pageSize != oldPageSize )
					sessionObject.folder.setPageSize( pageSize );
			}
			catch( NumberFormatException nfe ) {
				sessionObject.error += _("Invalid pagesize number, resetting to default value.") + "<br>";
			}
		}
		if( buttonPressed( request, PREVPAGE ) ) {
			sessionObject.pageChanged = true;
			sessionObject.folder.previousPage();
		}
		else if( buttonPressed( request, NEXTPAGE ) ) {
			sessionObject.pageChanged = true;
			sessionObject.folder.nextPage();
		}
		else if( buttonPressed( request, FIRSTPAGE ) ) {
			sessionObject.pageChanged = true;
			sessionObject.folder.firstPage();
		}
		else if( buttonPressed( request, LASTPAGE ) ) {
			sessionObject.pageChanged = true;
			sessionObject.folder.lastPage();
		}
		else if( buttonPressed( request, DELETE ) ) {
			int m = getCheckedMessage( request );
			if( m != -1 )
				sessionObject.reallyDelete = true;
			else
				sessionObject.error += _("No messages marked for deletion.") + "<br>";
		}
		else {
			int numberDeleted = 0;
			if( buttonPressed( request, REALLYDELETE ) ) {
				for( Enumeration e = request.getParameterNames(); e.hasMoreElements(); ) {
					String parameter = (String)e.nextElement();
					if( parameter.startsWith( "check" ) && request.getParameter( parameter ).compareTo( "1" ) == 0 ) {
						String number = parameter.substring( 5 );
						try {
							int n = Integer.parseInt( number );
							String uidl = (String)sessionObject.folder.getElementAtPosXonCurrentPage( n );
							if( uidl != null ) {
								Mail mail = sessionObject.mailCache.getMail( uidl, MailCache.FETCH_HEADER );
								if( mail != null ) {
									if( sessionObject.mailbox.delete( uidl ) ) {
										mail.markForDeletion = true;
										numberDeleted++;
									}
									else
										sessionObject.error += _("Error deleting message: {0}", sessionObject.mailbox.lastError()) + "<br>";
								}
							}
						}
						catch( NumberFormatException nfe ) {
						}
					}
				}
				sessionObject.mailbox.performDelete();
				sessionObject.folder.setElements( sessionObject.mailbox.getUIDLs() );
				sessionObject.pageChanged = true;
				sessionObject.info += ngettext("1 message deleted.", "{0} messages deleted.", numberDeleted);
			}
			sessionObject.reallyDelete = false;
		}
		
		sessionObject.markAll = buttonPressed( request, MARKALL );
		sessionObject.clear = buttonPressed( request, CLEAR );
		sessionObject.invert = buttonPressed( request, INVERT );

		/*
		 * process sorting buttons
		 */
		processSortingButton( sessionObject, request, SORT_ID );
		processSortingButton( sessionObject, request, SORT_SENDER );
		processSortingButton( sessionObject, request, SORT_SUBJECT );
		processSortingButton( sessionObject, request, SORT_DATE );
		processSortingButton( sessionObject, request, SORT_SIZE );		
	}
	/**
	 * @param sessionObject
	 * @param request
	 * @param sort_id
	 */
	private static void processSortingButton(SessionObject sessionObject, RequestWrapper request, String sort_id )
	{
		String str = request.getParameter( sort_id );
		if( str != null ) {
			if( str.compareToIgnoreCase( "up" ) == 0 ) {
				sessionObject.folder.setSortingDirection( Folder.UP );
				sessionObject.folder.sortBy( sort_id );
			}
			if( str.compareToIgnoreCase( "down" ) == 0 ) {
				sessionObject.folder.setSortingDirection( Folder.DOWN );
				sessionObject.folder.sortBy( sort_id );
			}
		}
	}
	/**
	 * @param httpSession
	 * @return
	 */
	private synchronized SessionObject getSessionObject( HttpSession httpSession )
	{
		SessionObject sessionObject = (SessionObject)httpSession.getAttribute( "sessionObject" );

		if( sessionObject == null ) {
			sessionObject = new SessionObject();
			httpSession.setAttribute( "sessionObject", sessionObject );
		}
		return sessionObject;
	}
	/**
	 * 
	 * @param httpRequest
	 * @param response
	 * @throws IOException
	 * @throws ServletException
	 */
	private void processRequest( HttpServletRequest httpRequest, HttpServletResponse response )
	throws IOException, ServletException
	{
		String theme = Config.getProperty(CONFIG_THEME, DEFAULT_THEME);
		I2PAppContext ctx = I2PAppContext.getGlobalContext();
		boolean universalTheming = ctx.getBooleanProperty(RC_PROP_UNIVERSAL_THEMING);
		if (universalTheming) {
			// Fetch routerconsole theme (or use our default if it doesn't exist)
			theme = ctx.getProperty(RC_PROP_THEME, DEFAULT_THEME);
			// Ensure that theme exists
			String[] themes = getThemes();
			boolean themeExists = false;
			for (int i = 0; i < themes.length; i++) {
				if (themes[i].equals(theme))
					themeExists = true;
			}
			if (!themeExists) {
				theme = DEFAULT_THEME;
			}
		}

		httpRequest.setCharacterEncoding("UTF-8");
		response.setCharacterEncoding("UTF-8");
                response.setHeader("X-Frame-Options", "SAMEORIGIN");
		RequestWrapper request = new RequestWrapper( httpRequest );
		
		SessionObject sessionObject = null;
		
		String subtitle = "";
		
		HttpSession httpSession = request.getSession( true );
		
		sessionObject = getSessionObject( httpSession );

		synchronized( sessionObject ) {
			
			sessionObject.error = "";
			sessionObject.info = "";
			sessionObject.pageChanged = false;
			sessionObject.showAttachment = null;
			sessionObject.themePath = "/themes/susimail/" + theme + '/';
			sessionObject.imgPath = sessionObject.themePath + "images/";
			
			processStateChangeButtons( sessionObject, request );
			
			if( sessionObject.state != STATE_AUTH )
				processGenericButtons( sessionObject, request );
			
			if( sessionObject.state == STATE_LIST ) {
				processFolderButtons( sessionObject, request );
				for( Iterator it = sessionObject.folder.currentPageIterator(); it != null && it.hasNext(); ) {
					String uidl = (String)it.next();
					Mail mail = sessionObject.mailCache.getMail( uidl, MailCache.FETCH_HEADER );
					if( mail != null && mail.error.length() > 0 ) {
						sessionObject.error += mail.error;
						mail.error = "";
					}
				}
			}
			
			if( sessionObject.state == STATE_SHOW ) {
				processMessageButtons( sessionObject, request );
				Mail mail = sessionObject.mailCache.getMail( sessionObject.showUIDL, MailCache.FETCH_ALL );
				if( mail != null && mail.error.length() > 0 ) {
					sessionObject.error += mail.error;
					mail.error = "";
				}
			}
			
			if( sessionObject.state == STATE_NEW )
				processComposeButtons( sessionObject, request );
		
			/*
			 * update folder content
			 */
			if( sessionObject.state != STATE_AUTH )
				sessionObject.folder.setElements( sessionObject.mailbox.getUIDLs() );

			if( ! sendAttachment( sessionObject, response ) ) { 
				PrintWriter out = response.getWriter();
				
				/*
				 * build subtitle
				 */
				if( sessionObject.state == STATE_AUTH )
					subtitle = _("Login");
				else if( sessionObject.state == STATE_LIST )
					subtitle = ngettext("1 Message", "{0} Messages", sessionObject.mailbox.getNumMails());
				else if( sessionObject.state == STATE_SHOW )
					subtitle = _("Show Message");

				response.setContentType( "text/html" );

				/*
				 * write header
				 */
				out.println( "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\">\n<html>\n" +
					"<head>\n" +
					"<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">\n" +
					"<title>susimail - " + subtitle + "</title>\n" +
					"<link rel=\"stylesheet\" type=\"text/css\" href=\"" + sessionObject.themePath + "susimail.css\">\n" +
					"</head>\n<body>\n" +
					"<div class=\"page\"><p><img src=\"" + sessionObject.imgPath + "susimail.png\" alt=\"Susimail\"><br>&nbsp;</p>\n" +
					"<form method=\"POST\" enctype=\"multipart/form-data\" action=\"" + myself + "\">" );

				if( sessionObject.error != null && sessionObject.error.length() > 0 ) {
					out.println( "<p class=\"error\">" + sessionObject.error + "</p>" );
				}
				if( sessionObject.info != null && sessionObject.info.length() > 0 ) {
					out.println( "<p class=\"info\">" + sessionObject.info + "</p>" );
				}
				/*
				 * now write body
				 */
				if( sessionObject.state == STATE_AUTH )
					showLogin( out );
				
				else if( sessionObject.state == STATE_LIST )
					showFolder( out, sessionObject, request );
				
				else if( sessionObject.state == STATE_SHOW )
					showMessage( out, sessionObject );
				
				else if( sessionObject.state == STATE_NEW )
					showCompose( out, sessionObject, request );
				
				out.println( "</form><hr><p class=\"footer\">susimail v0." + version +" " + ( RELEASE ? "release" : "development" ) + " &copy; 2004-2005 <a href=\"mailto:susi23@mail.i2p\">susi</a></div></body>\n</html>");				
				out.flush();
			}
		}
	}
	/**
	 * @param sessionObject
	 * @param response
	 * @return
	 */
	private static boolean sendAttachment(SessionObject sessionObject, HttpServletResponse response)
	{
		boolean shown = false;
		if( sessionObject.showAttachment != null ) {
			
			MailPart part = sessionObject.showAttachment;
			ReadBuffer content = part.buffer;
			
			if( part.encoding != null ) {
				Encoding encoding = EncodingFactory.getEncoding( part.encoding );
				if( encoding != null ) {
					try {
						content = encoding.decode( part.buffer.content, part.beginBody + 2, part.end - part.beginBody - 2 );
					}
					catch (DecodingException e) {
						sessionObject.error += _("Error decoding content: {0}", e.getMessage()) + "<br>";
						content = null;
					}
				}
				else {
					sessionObject.error += _("Error decoding content: No encoder found.");
					content = null;
				}
			}
			if( content != null ) {
				ZipOutputStream zip = null;
				try {
					zip = new ZipOutputStream( response.getOutputStream() );
					String name;
					if( part.filename != null )
						name = part.filename;
					else if( part.name != null )
						name = part.name;
					else
						name = "part" + part.hashCode();
					String name2 = name.replaceAll( "\\.", "_" );
					response.setContentType( "application/zip; name=\"" + name2 + ".zip\"" );
					response.addHeader( "Content-Disposition:", "attachment; filename=\"" + name2 + ".zip\"" );
					ZipEntry entry = new ZipEntry( name );
					zip.putNextEntry( entry );
					zip.write( content.content, content.offset, content.length );
					zip.finish();
					shown = true;
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} finally {
					if ( zip != null)
						try { zip.close(); } catch (IOException ioe) {}
				}
			}
		}
		return shown;
	}
	/**
	 * @param sessionObject
	 * @param request
	 * @return
	 */
	private static boolean sendMail( SessionObject sessionObject, RequestWrapper request )
	{
		boolean ok = true;
		
		String from = request.getParameter( NEW_FROM );
		String to = request.getParameter( NEW_TO );
		String cc = request.getParameter( NEW_CC );
		String bcc = request.getParameter( NEW_BCC );
		String subject = request.getParameter( NEW_SUBJECT, _("no subject") );
		String text = request.getParameter( NEW_TEXT, "" );

		String prop = Config.getProperty( CONFIG_SENDER_FIXED, "true" );
		String domain = Config.getProperty( CONFIG_SENDER_DOMAIN, "mail.i2p" );
		if( prop.compareToIgnoreCase( "false" ) != 0 ) {
			from = "<" + sessionObject.user + "@" + domain + ">";
		}
		ArrayList toList = new ArrayList();
		ArrayList ccList = new ArrayList();
		ArrayList bccList = new ArrayList();
		ArrayList recipients = new ArrayList();
		
		String sender = null;
		
		if( from == null || !Mail.validateAddress( from ) ) {
			ok = false;
			sessionObject.error += _("Found no valid sender address.") + "<br>";
		}
		else {
			sender = Mail.getAddress( from );
			if( sender == null || sender.length() == 0 ) {
				ok = false;
				sessionObject.error += _("Found no valid address in \\''{0}\\''.", quoteHTML( from )) + "<br>";
			}
		}
		
		ok = Mail.getRecipientsFromList( toList, to, ok );
		ok = Mail.getRecipientsFromList( ccList, cc, ok );
		ok = Mail.getRecipientsFromList( bccList, bcc, ok );

		recipients.addAll( toList );
		recipients.addAll( ccList );
		recipients.addAll( bccList );
		
		String bccToSelf = request.getParameter( NEW_BCC_TO_SELF );
		
		if( bccToSelf != null && bccToSelf.compareTo( "1" ) == 0 )
			recipients.add( sender );
		
		if( recipients.isEmpty() ) {
			ok = false;
			sessionObject.error += _("No recipients found.") + "<br>";
		}
		Encoding qp = EncodingFactory.getEncoding( "quoted-printable" );
		Encoding hl = EncodingFactory.getEncoding( "HEADERLINE" );
		
		if( qp == null ) {
			ok = false;
			sessionObject.error += _("Quoted printable encoder not available.");
		}
		
		if( hl == null ) {
			ok = false;
			sessionObject.error += _("Header line encoder not available.");
		}

		if( ok ) {
			StringBuilder body = new StringBuilder();
			body.append( "From: " + from + "\r\n" );
			Mail.appendRecipients( body, toList, "To: " );
			Mail.appendRecipients( body, ccList, "To: " );
			body.append( "Subject: " );
			try {
				body.append( hl.encode( subject ) );
			} catch (EncodingException e) {
				ok = false;
				sessionObject.error += e.getMessage();
			}
			String boundary = "_="+(int)(Math.random()*Integer.MAX_VALUE)+""+(int)(Math.random()*Integer.MAX_VALUE);
			boolean multipart = false;
			if( sessionObject.attachments != null && !sessionObject.attachments.isEmpty() ) {
				multipart = true;
				body.append( "\r\nMIME-Version: 1.0\r\nContent-type: multipart/mixed; boundary=\"" + boundary + "\"\r\n\r\n" );
			}
			else {
				body.append( "\r\nMIME-Version: 1.0\r\nContent-type: text/plain; charset=\"iso-8859-1\"\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n" );
			}
			try {
				if( multipart )
					body.append( "--" + boundary + "\r\nContent-type: text/plain; charset=\"iso-8859-1\"\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n" );
				body.append( qp.encode( text ) );
			} catch (EncodingException e) {
				ok = false;
				sessionObject.error += e.getMessage();
			}

			if( multipart ) {
				for( Iterator it = sessionObject.attachments.iterator(); it.hasNext(); ) {
					Attachment attachment = (Attachment)it.next();
					body.append( "\r\n--" + boundary + "\r\nContent-type: " + attachment.getContentType() + "\r\nContent-Disposition: attachment; filename=\"" + attachment.getFileName() + "\"\r\nContent-Transfer-Encoding: " + attachment.getTransferEncoding() + "\r\n\r\n" );
					body.append( attachment.getData() );
				}
				body.append( "\r\n--" + boundary + "--\r\n" );
			}
			
			sessionObject.sentMail = body.toString();	
			
			SMTPClient relay = new SMTPClient();
			
			if( ok ) {
				if( relay.sendMail( sessionObject.host, sessionObject.smtpPort,
						sessionObject.user, sessionObject.pass,
						sender, recipients.toArray(), body.toString() ) ) {
					
					sessionObject.info += _("Mail sent.");
					
					if( sessionObject.attachments != null )
						sessionObject.attachments.clear();
				}
				else {
						ok = false;
						sessionObject.error += relay.error;
				}
			}
		}
		return ok;
	}
	/**
	 * 
	 */
	@Override
	public void doGet( HttpServletRequest request, HttpServletResponse response )
	throws IOException, ServletException
	{
		processRequest( request, response );		
	}
	/**
	 * 
	 */
	@Override
	public void doPost( HttpServletRequest request, HttpServletResponse response )
	throws IOException, ServletException
	{
		processRequest( request, response );
	}
	/**
	 * 
	 * @param out
	 * @param sessionObject
	 * @param request
	 */
	private static void showCompose( PrintWriter out, SessionObject sessionObject, RequestWrapper request )
	{
		out.println( button( SEND, _("Send") ) +
				button( CANCEL, _("Cancel") ) + spacer +
				(sessionObject.attachments != null && (!sessionObject.attachments.isEmpty()) ? button( DELETE_ATTACHMENT, _("Delete Attachment") ) : button2( DELETE_ATTACHMENT, _("Delete Attachment") ) ) + spacer +
				button( RELOAD, _("Reload Config") ) + spacer +
				button( LOGOUT, _("Logout") ) );

		String from = request.getParameter( NEW_FROM );
		String fixed = Config.getProperty( CONFIG_SENDER_FIXED, "true" );
		
		if( from == null || fixed.compareToIgnoreCase( "false" ) != 0 ) {
				String domain = Config.getProperty( CONFIG_SENDER_DOMAIN, "mail.i2p" );
				from = "<" + sessionObject.user + "@" + domain + ">";
		}
		
		String to = request.getParameter( NEW_TO, sessionObject.replyTo != null ? sessionObject.replyTo : "" );
		String cc = request.getParameter( NEW_CC, sessionObject.replyCC != null ? sessionObject.replyCC : "" );
		String bcc = request.getParameter( NEW_BCC, "" );
		String subject = request.getParameter( NEW_SUBJECT, sessionObject.subject != null ? sessionObject.subject : "" );
		String text = request.getParameter( NEW_TEXT, sessionObject.body != null ? sessionObject.body : "" );
		String bccToSelf = Config.getProperty( CONFIG_BCC_TO_SELF, "true" );
		sessionObject.replyTo = null;
		sessionObject.replyCC = null;
		sessionObject.subject = null;
		sessionObject.body = null;
		
		out.println( "<table cellspacing=\"0\" cellpadding=\"5\">\n" +
				"<tr><td colspan=\"2\" align=\"center\"><hr></td></tr>\n" +
				"<tr><td align=\"right\">" + _("From:") + "</td><td align=\"left\"><input type=\"text\" size=\"80\" name=\"" + NEW_FROM + "\" value=\"" + from + "\" " + ( fixed.compareToIgnoreCase( "false" ) != 0 ? "disabled" : "" ) +"></td></tr>\n" +
				"<tr><td align=\"right\">" + _("To:") + "</td><td align=\"left\"><input type=\"text\" size=\"80\" name=\"" + NEW_TO + "\" value=\"" + to + "\"></td></tr>\n" +
				"<tr><td align=\"right\">" + _("Cc:") + "</td><td align=\"left\"><input type=\"text\" size=\"80\" name=\"" + NEW_CC + "\" value=\"" + cc + "\"></td></tr>\n" +
				"<tr><td align=\"right\">" + _("Bcc:") + "</td><td align=\"left\"><input type=\"text\" size=\"80\" name=\"" + NEW_BCC + "\" value=\"" + bcc + "\"></td></tr>\n" +
				"<tr><td align=\"right\">" + _("Subject:") + "</td><td align=\"left\"><input type=\"text\" size=\"80\" name=\"" + NEW_SUBJECT + "\" value=\"" + subject + "\"></td></tr>\n" +
				"<tr><td>&nbsp;</td><td align=\"left\"><input type=\"checkbox\" class=\"optbox\" name=\"" + NEW_BCC_TO_SELF + "\" value=\"1\"" + ( bccToSelf.compareToIgnoreCase( "false" ) != 0 ? "checked" : "" )+ ">" + _("Bcc to self") + "</td></tr>\n" +
				"<tr><td colspan=\"2\" align=\"center\"><textarea cols=\"" + Config.getProperty( CONFIG_COMPOSER_COLS, 80 )+ "\" rows=\"" + Config.getProperty( CONFIG_COMPOSER_ROWS, 10 )+ "\" name=\"" + NEW_TEXT + "\">" + text + "</textarea>" +
				"<tr><td colspan=\"2\" align=\"center\"><hr></td></tr>\n" +
				"<tr><td align=\"right\">" + _("New Attachment:") + "</td><td align=\"left\"><input type=\"file\" size=\"50%\" name=\"" + NEW_FILENAME + "\" value=\"\"><input type=\"submit\" name=\"" + NEW_UPLOAD + "\" value=\"" + _("Upload File") + "\"></td></tr>" );
		
		if( sessionObject.attachments != null && !sessionObject.attachments.isEmpty() ) {
			boolean wroteHeader = false;
			for( Iterator it = sessionObject.attachments.iterator(); it.hasNext(); ) {
				if( !wroteHeader ) {
					out.println( "<tr><td colspan=\"2\" align=\"center\">" + _("Attachments:") + "</td></tr>" );
					wroteHeader = true;
				}
				Attachment attachment = (Attachment)it.next();
				out.println( "<tr><td colspan=\"2\" align=\"center\"><input type=\"checkbox\" class=\"optbox\" name=\"check" + attachment.hashCode() + "\" value=\"1\">&nbsp;" + attachment.getFileName() + "</td></tr>");
			}
		}
		out.println( "</table>" );
	}
	/**
	 * 
	 * @param out
	 */
	private static void showLogin( PrintWriter out )
	{
		String fixedPorts = Config.getProperty( CONFIG_PORTS_FIXED, "true" );
		boolean fixed = fixedPorts.compareToIgnoreCase( "false" ) != 0;
		String host = Config.getProperty( CONFIG_HOST, DEFAULT_HOST );
		String pop3 = Config.getProperty( CONFIG_PORTS_POP3, "" + DEFAULT_POP3PORT );
		String smtp = Config.getProperty( CONFIG_PORTS_SMTP, "" + DEFAULT_SMTPPORT );
		
		out.println( "<table cellspacing=\"0\" cellpadding=\"5\">\n" +
			// current postman hq length limits 16/12, new postman version 32/32
			"<tr><td align=\"right\" width=\"30%\">" + _("User") + "</td><td width=\"40%\" align=\"left\"><input type=\"text\" size=\"32\" name=\"" + USER + "\" value=\"" + ( RELEASE ? "" : "test") + "\"> @mail.i2p</td></tr>\n" +
			"<tr><td align=\"right\" width=\"30%\">" + _("Password") + "</td><td width=\"40%\" align=\"left\"><input type=\"password\" size=\"32\" name=\"pass\" value=\"" + ( RELEASE ? "" : "test") + "\"></td></tr>\n");
		// which is better?
		//if (!fixed) {
		if (true) {
		    out.println(
			"<tr><td align=\"right\" width=\"30%\">" + _("Host") + "</td><td width=\"40%\" align=\"left\"><input type=\"text\" size=\"32\" name=\"" + HOST +"\" value=\"" + host + "\"" + ( fixed ? " disabled" : "" ) + "></td></tr>\n" +
			"<tr><td align=\"right\" width=\"30%\">" + _("POP3-Port") + "</td><td width=\"40%\" align=\"left\"><input type=\"text\" size=\"5\" name=\"" + POP3 +"\" value=\"" + pop3 + "\"" + ( fixed ? " disabled" : "" ) + "></td></tr>\n" +
			"<tr><td align=\"right\" width=\"30%\">" + _("SMTP-Port") + "</td><td width=\"40%\" align=\"left\"><input type=\"text\" size=\"5\" name=\"" + SMTP +"\" value=\"" + smtp + "\"" + ( fixed ? " disabled" : "" ) + "></td></tr>\n");
		}
		out.println(
			"<tr><td></td><td align=\"left\">" + button( LOGIN, _("Login") ) + " <input class=\"cancel\" type=\"reset\" value=\"" + _("Reset") + "\"></td></tr>\n" +
			"<tr><td></td><td align=\"left\"><a href=\"http://hq.postman.i2p/?page_id=14\">" + _("Learn about I2P mail") + "</a></td></tr>\n" +
			"<tr><td></td><td align=\"left\"><a href=\"http://hq.postman.i2p/?page_id=16\">" + _("Create Account") + "</a></td></tr>\n" +
			"</table>");
	}
	/**
	 * 
	 * @param out
	 * @param sessionObject
	 * @param request
	 */
	private static void showFolder( PrintWriter out, SessionObject sessionObject, RequestWrapper request )
	{
		if( sessionObject.reallyDelete ) {
			out.println( "<p class=\"error\">" + _("Really delete the marked messages?") + " " + button( REALLYDELETE, _("Yes, really delete them!") ) + "</p>" );
		}
		out.println( button( NEW, _("New") ) + spacer +
			button( REPLY, _("Reply") ) +
			button( REPLYALL, _("Reply All") ) +
			button( FORWARD, _("Forward") ) + spacer +
			button( DELETE, _("Delete") ) + spacer +
			button( REFRESH, _("Check Mail") ) + spacer +
			button( RELOAD, _("Reload Config") ) + spacer +
			button( LOGOUT, _("Logout") ) + "<table cellspacing=\"0\" cellpadding=\"5\">\n" +
			"<tr><td colspan=\"8\"><hr></td></tr>\n<tr>" +
			thSpacer + "<th>" + sortHeader( SORT_SENDER, _("Sender"), sessionObject.imgPath ) + "</th>" +
			thSpacer + "<th>" + sortHeader( SORT_SUBJECT, _("Subject"), sessionObject.imgPath ) + "</th>" +
			thSpacer + "<th>" + sortHeader( SORT_DATE, _("Date"), sessionObject.imgPath ) + sortHeader( SORT_ID, "", sessionObject.imgPath ) + "</th>" +
			thSpacer + "<th>" + sortHeader( SORT_SIZE, _("Size"), sessionObject.imgPath ) + "</th></tr>" );
		int bg = 0;
		int i = 0;
		for( Iterator it = sessionObject.folder.currentPageIterator(); it != null && it.hasNext(); ) {
			String uidl = (String)it.next();
			Mail mail = sessionObject.mailCache.getMail( uidl, MailCache.FETCH_HEADER );
			String link = "<a href=\"" + myself + "?" + SHOW + "=" + i + "\">";
			
			boolean idChecked = false;
			String checkId = sessionObject.pageChanged ? null : (String)request.getParameter( "check" + i );
			
			if( checkId != null && checkId.compareTo( "1" ) == 0 )
				idChecked = true;
			
			if( sessionObject.markAll )
				idChecked = true;
			if( sessionObject.invert )
				idChecked = !idChecked;
			if( sessionObject.clear )
				idChecked = false;

			Debug.debug( Debug.DEBUG, "check" + i + ": checkId=" + checkId + ", idChecked=" + idChecked + ", pageChanged=" + sessionObject.pageChanged +
					", markAll=" + sessionObject.markAll +
					", invert=" + sessionObject.invert +
					", clear=" + sessionObject.clear );
			out.println( "<tr class=\"list" + bg + "\"><td><input type=\"checkbox\" class=\"optbox\" name=\"check" + i + "\" value=\"1\"" + 
					( idChecked ? "checked" : "" ) + ">" + ( RELEASE ? "" : "" + i ) + "</td><td>" + link + mail.shortSender + "</a></td><td>&nbsp;</td><td>" + link + mail.shortSubject + "</a></td><td>&nbsp;</td><td>" + mail.formattedDate + "</td><td>&nbsp;</td><td>" + ngettext("1 Byte", "{0} Bytes", mail.size) + "</td></tr>" );
			bg = 1 - bg;
			i++;
		}
		out.println( "<tr><td colspan=\"8\"><hr></td></tr>\n</table>" +
				button( MARKALL, _("Mark All") ) +
				button( INVERT, _("Invert Selection") ) +
				button( CLEAR, _("Clear") ) +
				"<br>" +
				( sessionObject.folder.isFirstPage() ?
										 button2( FIRSTPAGE, _("First") ) + button2( PREVPAGE, _("Previous") ) :
 										 button( FIRSTPAGE, _("First") ) + button( PREVPAGE, _("Previous") ) ) +
				" &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;" + _("Page {0} of {1}", sessionObject.folder.getCurrentPage(), sessionObject.folder.getPages()) + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; " +
				( sessionObject.folder.isLastPage() ? 
														   button2( NEXTPAGE, _("Next") ) + button2( LASTPAGE, _("Last") ) :
														   button( NEXTPAGE, _("Next") ) + button( LASTPAGE, _("Last") ) ) +
														   
				"<br>" +
				_("Pagesize:") + "&nbsp;<input type=\"text\" name=\"" + PAGESIZE + "\" size=\"4\" value=\"" +  sessionObject.folder.getPageSize() + "\">" +
				button( SETPAGESIZE, _("Set") ) );
	}
	/**
	 * 
	 * @param out
	 * @param sessionObject
	 */
	private static void showMessage( PrintWriter out, SessionObject sessionObject )
	{
		if( sessionObject.reallyDelete ) {
			out.println( "<p class=\"error\">" + _("Really delete this message?") + " " + button( REALLYDELETE, _("Yes, really delete it!") ) + "</p>" );
		}
		Mail mail = sessionObject.mailCache.getMail( sessionObject.showUIDL, MailCache.FETCH_ALL );
		if( mail != null && mail.body != null && mail.part == null ) {
			mail.part = new MailPart();
			mail.part.parse( mail.body );
		}
		if( !RELEASE ) {
			out.println( "<!--" );
			out.println( quoteHTML( new String( mail.body.content, mail.body.offset, mail.body.length ) ) );
			out.println( "-->" );
		}
		out.println( button( NEW, _("New") ) + spacer +
			button( REPLY, _("Reply") ) +
			button( REPLYALL, _("Reply All") ) +
			button( FORWARD, _("Forward") ) + spacer +
			button( DELETE, _("Delete") ) + spacer +
			( sessionObject.folder.isFirstElement( sessionObject.showUIDL ) ? button2( PREV, _("Previous") ) : button( PREV, _("Previous") ) ) +
			( sessionObject.folder.isLastElement( sessionObject.showUIDL ) ? button2( NEXT, _("Next") ) : button( NEXT, _("Next") ) ) + spacer +
			button( LIST, _("Back to Folder") ) + spacer +
			button( RELOAD, _("Reload Config") ) + spacer +
			button( LOGOUT, _("Logout") ) );
		if( mail != null ) {
			out.println( "<table cellspacing=\"0\" cellpadding=\"5\">\n" +
					"<tr><td colspan=\"2\" align=\"center\"><hr></td></tr>\n" +
					"<tr class=\"mailhead\"><td align=\"right\">" + _("From:") + "</td><td align=\"left\">" + quoteHTML( mail.formattedSender ) + "</td></tr>\n" +
					"<tr class=\"mailhead\"><td align=\"right\">" + _("Date:") + "</td><td align=\"left\">" + mail.quotedDate + "</td></tr>\n" +
					"<tr class=\"mailhead\"><td align=\"right\">" + _("Subject:") + "</td><td align=\"left\">" + quoteHTML( mail.formattedSubject ) + "</td></tr>\n" +
					"<tr><td colspan=\"2\" align=\"center\"><hr></td></tr>" );
			if( mail.body != null ) {
				showPart( out, mail.part, 0, SHOW_HTML );
			}
			else {
				out.println( "<tr class=\"mailbody\"><td colspan=\"2\" align=\"center\"><p class=\"error\">" + _("Could not fetch mail body.") + "</p></td></tr>" );
			}
		}
		else {
			out.println( "<tr class=\"mailbody\"><td colspan=\"2\" align=\"center\"><p class=\"error\">" + _("Could not fetch mail.") + "</p></td></tr>" );
		}
		out.println( "<tr><td colspan=\"2\" align=\"center\"><hr></td></tr>\n</table>" );
	}

	/** translate */
	private static String _(String s) {
		return Messages.getString(s);
	}

	/** translate */
	private static String _(String s, Object o) {
		return Messages.getString(s, o);
	}

	/** translate */
	private static String _(String s, Object o, Object o2) {
		return Messages.getString(s, o, o2);
	}
	
	/** translate */
    private static String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p);
    }

    /**
     * Get all themes
     * @return String[] -- Array of all the themes found.
     */
    public String[] getThemes() {
            String[] themes = null;
            // "docs/themes/susimail/"
            File dir = new File(I2PAppContext.getGlobalContext().getBaseDir(), "docs/themes/susimail");
            FileFilter fileFilter = new FileFilter() { public boolean accept(File file) { return file.isDirectory(); } };
            // Walk the themes dir, collecting the theme names, and append them to the map
            File[] dirnames = dir.listFiles(fileFilter);
            if (dirnames != null) {
                themes = new String[dirnames.length];
                for(int i = 0; i < dirnames.length; i++) {
                    themes[i] = dirnames[i].getName();
                }
            }
            // return the map.
            return themes;
    }
}
