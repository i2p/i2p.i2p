/*
 * Created on Nov 9, 2004
 * 
 *  This file is part of susimail project, see http://susi.i2p/
 *  
 *  Copyright (C) 2004-2005  susi23@mail.i2p
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

import i2p.susi.util.Buffer;
import i2p.susi.util.Config;
import i2p.susi.util.CountingInputStream;
import i2p.susi.util.EOFOnMatchInputStream;
import i2p.susi.util.FileBuffer;
import i2p.susi.util.MemoryBuffer;
import i2p.susi.webmail.encoding.Encoding;
import i2p.susi.webmail.encoding.EncodingFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.servlet.util.ServletUtil;
import net.i2p.util.Log;
import net.i2p.util.RFC822Date;
import net.i2p.util.SystemVersion;

/**
 * data structure to hold a single message, mostly used with folder view and sorting
 * 
 * @author susi
 */
class Mail {
	
	private static final String DATEFORMAT = "date.format";
	private static final String unknown = "unknown";
	private static final String P1 = "^[^@< \t]+@[^> \t]+$";
	private static final String P2 = "^<[^@< \t]+@[^> \t]+>$";
	private static final Pattern PATTERN1 = Pattern.compile(P1);
	private static final Pattern PATTERN2 = Pattern.compile(P2);
	/**
	 *  Also used by MailPart
	 *  See MailPart for why we don't do \r\n\r\n
	 */
	static final byte HEADER_MATCH[] = DataHelper.getASCII("\r\n\r");

	private long size;
	public String sender,   // as received, trimmed only, not HTML escaped
		reply,
		subject,	// as received, trimmed only, not HTML escaped, non-null, default ""
		dateString,
		//formattedSender,    // address only, enclosed with <>, not HTML escaped
		formattedDate,  // US Locale, UTC
		localFormattedDate,  // Current Locale, local time zone
		shortSender,    // Either name or address but not both, HTML escaped, double-quotes removed, truncated with hellip
		shortSubject,   // HTML escaped, truncated with hellip, non-null, default ""
		quotedDate;  // Current Locale, local time zone, longer format
	public final String uidl;
	public Date date;
	private Buffer header, body;
	private MailPart part;
	String[] to, cc;        // addresses only, enclosed by <>
	private boolean isNew, isSpam;
	public String contentType;
	public String messageID; // as received, trimmed only, probably enclosed with <>, not HTML escaped

	public String error;

	public boolean markForDeletion;
	private final Log _log;
	
	public Mail(String uidl) {
		this.uidl = uidl;
		//formattedSender = unknown;
		subject = "";
		formattedDate = unknown;
		localFormattedDate = unknown;
		sender = "";
		shortSender = unknown;
		shortSubject = "";
		quotedDate = unknown;
		error = "";
		_log = I2PAppContext.getGlobalContext().logManager().getLog(Mail.class);
	}

	/**
	 *  This may or may not contain the body also.
	 *  @return if null, nothing has been loaded yet for this UIDL
	 */
	public synchronized Buffer getHeader() {
		return header;
	}

	public synchronized void setHeader(Buffer rb) {
		try {
			setHeader(rb, rb.getInputStream(), true);
		} catch (IOException ioe) {
			// TODO...
		}
	}

	/** @since 0.9.34 */
	private synchronized String[] setHeader(Buffer rb, InputStream in, boolean closeIn) {
		if (rb == null)
			return null;
		header = rb;
		String[] rv = parseHeaders(in);
		if (closeIn)
			rb.readComplete(true);
		// set a date if we didn't get one in the headers
		if (date == null) {
			long dateLong;
			if (rb instanceof FileBuffer) {
				dateLong = ((FileBuffer) rb).getFile().lastModified();
			} else {
				dateLong = I2PAppContext.getGlobalContext().clock().now();
			}
			setDate(dateLong);
		}
		return rv;
	}

	/**
	 *  @return if false, nothing has been loaded yet for this UIDL
	 */
	public synchronized boolean hasHeader() {
		return header != null;
	}

	/**
         *  This contains the header also.
         *  @return may be null
         */
	public synchronized Buffer getBody() {
		return body;
	}

	public synchronized void setBody(Buffer rb) {
		if (rb == null)
			return;
		// In the common case where we have the body, we only parse the headers once.
		// we always re-set the header, even if it was non-null before,
		// as we have to parse them to find the start of the body
		// and who knows, the headers could have changed.
		//if (header == null)
		//	setHeader(rb);
		body = rb;
		boolean success = false;
		CountingInputStream in = null;
		try {
			in = new CountingInputStream(rb.getInputStream());
			String[] headerLines = setHeader(rb, in, false);
			// TODO just fail?
			if (headerLines == null)
				headerLines = new String[0];
			part = new MailPart(uidl, new AtomicInteger(), rb, in, in, headerLines);
			rb.readComplete(true);
			// may only be available after reading and calling readComplete()
			size = rb.getLength();
			success = true;
		} catch (IOException de) {
			_log.error("Decode error", de);
		} catch (RuntimeException e) {
			_log.error("Parse error", e);
		} finally {
			if (in != null) try { in.close(); } catch (IOException ioe) {}
			rb.readComplete(success);
		}
	}

	public synchronized boolean hasBody() {
		return body != null;
	}

	public synchronized MailPart getPart() {
		return part;
	}

	public synchronized boolean hasPart() {
		return part != null;
	}

	/**
	 *  @return 0 if unknown
	 */
	public synchronized long getSize() {
		return size;
	}

	public synchronized void setSize(long size) {
		if (body != null)
			return;
		this.size = size;
	}

	public synchronized boolean isSpam() {
		return isSpam;
	}

	public synchronized boolean isNew() {
		return isNew;
	}

	public synchronized void setNew(boolean isNew) {
		this.isNew = isNew;
	}

	public synchronized boolean hasAttachment() {
		// this isn't right but good enough to start
		// if part != null query parts instead?
		return contentType != null &&
			!contentType.contains("text/plain") &&
			!contentType.contains("multipart/alternative") &&
			!contentType.contains("multipart/signed");
	}

	/**
	 * 
	 * @param address E-mail address to be validated
	 * @return Is the e-mail address valid?
	 */
	public static boolean validateAddress( String address )
	{
		if( address == null || address.length() == 0 )
			return false;
		
		address = address.trim();
		
		if( address.indexOf('\n') != -1 ||
				address.indexOf('\r') != -1 )
			return false;
		
		String[] tokens = DataHelper.split(address, "[ \t]+");

		int addresses = 0;
		
		for( int i = 0; i < tokens.length; i++ ) {
			if( tokens[i].matches( "^[^@< \t]+@[^> \t]+$" ) ||
					tokens[i].matches( "^<[^@< \t]+@[^> \t]+>$" ) )
				addresses++;
		}
		return addresses == 1;
	}

	/**
	 * Returns the first email address portion, enclosed by &lt;&gt;
	 * @param address
	 */
	public static String getAddress(String address )
	{
		String[] tokens = DataHelper.split(address, "[ \t]+");

		for( int i = 0; i < tokens.length; i++ ) {
			if (PATTERN1.matcher(tokens[i]).matches())
				return "<" + tokens[i] + ">";
			if (PATTERN2.matcher(tokens[i]).matches())
				return tokens[i];
		}
		
		return null;
	}

	/**
	 * A little misnamed. Adds all addresses from the comma-separated
	 * line in text to the recipients list.
	 * 
	 * @param text comma-separated
	 * @param recipients out param
	 * @param ok will be returned
	 * @return true if ALL e-mail addresses are valid AND the in parameter was true
	 */
	public static boolean getRecipientsFromList( ArrayList<String> recipients, String text, boolean ok )
	{
		if( text != null && text.length() > 0 ) {			
			String[] ccs = DataHelper.split(text, ",");
			ok = getRecipientsFromList(recipients, ccs, ok);
		}
		return ok;
	}

	/**
	 * A little misnamed. Adds all addresses from the elements
	 * in text to the recipients list.
	 * 
	 * @param recipients out param
	 * @param ok will be returned
	 * @return true if ALL e-mail addresses are valid AND the in parameter was true
	 * @since 0.9.35
	 */
	public static boolean getRecipientsFromList( ArrayList<String> recipients, String[] ccs, boolean ok )
	{
		if (ccs != null && ccs.length > 0 ) {			
			for( int i = 0; i < ccs.length; i++ ) {
				String recipient = ccs[i].trim();
				if( validateAddress( recipient ) ) {
					String str = getAddress( recipient );
					if( str != null && str.length() > 0 ) {
						recipients.add( str );
					}
					else {
						ok = false;
					}
				}
				else {
					ok = false;
				}
			}
		}
		return ok;
	}

	/**
	 * Adds all items from the list
	 * to the builder, separated by tabs.
	 * This is for SMTP/POP.
	 * 
	 * @param buf out param
	 * @param prefix prepended to the addresses
	 */
	public static void appendRecipients( StringBuilder buf, ArrayList<String> recipients, String prefix )
	{
		for (int i = 0; i < recipients.size(); i++) {
			buf.append( prefix );
			prefix ="\t";
			buf.append(recipients.get(i));
			if (i < recipients.size() - 1)
				buf.append(',');
			buf.append( "\r\n" );
		}
	}

	/**
	 * Adds all items from the array
	 * to the builder, separated by commas
	 * This is for display of a forwarded email.
	 * 
	 * @param prefix prepended to the addresses, includes trailing ": "
	 * @since 0.9.35
	 */
	public static void appendRecipients(PrintWriter out, String[] recipients, String prefix)
	{
		StringBuilder buf = new StringBuilder(120);
		buf.append(prefix);
		for (int i = 0; i < recipients.length; i++) {
			buf.append(recipients[i]);
			if (i < recipients.length - 1)
				buf.append(", ");
			if (buf.length() > 75) {
				out.println(buf);
				buf.setLength(0);
			}
		}
		if (buf.length() > 0)
			out.println(buf);
	}

	private static final DateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
	private static final DateFormat localDateFormatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
	private static final DateFormat longLocalDateFormatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
	static {
		// the router sets the JVM time zone to UTC but saves the original here so we can get it
		TimeZone tz = SystemVersion.getSystemTimeZone();
		localDateFormatter.setTimeZone(tz);
		longLocalDateFormatter.setTimeZone(tz);
	}

	/**
	 * @param dateLong non-negative
	 * @since 0.9.34 pulled from parseHeaders()
	 */
	private void setDate(long dateLong) {
		date = new Date(dateLong);
		synchronized(dateFormatter) {
			formattedDate = dateFormatter.format( date );
			localFormattedDate = localDateFormatter.format( date );
			quotedDate = longLocalDateFormatter.format(date);
		}
	}

	/**
	 * @return all headers, to pass to MailPart, or null on error
	 */
	private String[] parseHeaders(InputStream in)
	{
		String[] headerLines = null;
		error = "";
		if( header != null ) {

			boolean ok = true;
			
			Encoding html = EncodingFactory.getEncoding( "HTML" );
			
			if( html == null ) {
				error += "HTML encoder not found.\n";
				ok = false;
			}
			
			Encoding hl = EncodingFactory.getEncoding( "HEADERLINE" );

			if( hl == null ) {
				error += "Header line encoder not found.\n";
				ok = false;
			}

			if( ok ) {
				
				try {
					EOFOnMatchInputStream eofin = new EOFOnMatchInputStream(in, HEADER_MATCH);
					MemoryBuffer decoded = new MemoryBuffer(4096);
					hl.decode(eofin, decoded);
					if (!eofin.wasFound())
						if (_log.shouldDebug()) _log.debug("EOF hit before \\r\\n\\r\\n in Mail");
					// Fixme UTF-8 to bytes to UTF-8
					headerLines = DataHelper.split(new String(decoded.getContent(), decoded.getOffset(), decoded.getLength()), "\r\n");
					for (int j = 0; j < headerLines.length; j++) {
						String line = headerLines[j];
						if( line.length() == 0 )
							break;

						String hlc = line.toLowerCase(Locale.US);
						if (hlc.startsWith("from:")) {
							sender = line.substring( 5 ).trim();
							//formattedSender = getAddress( sender );
							shortSender = sender.replace("\"", "").trim();
							int lt = shortSender.indexOf('<');
							if (lt > 0)
								shortSender = shortSender.substring(0, lt).trim();
							else if (lt < 0 && shortSender.contains("@"))
								shortSender = '<' + shortSender + '>';  // add missing <> (but thunderbird doesn't...)
							boolean trim = shortSender.length() > 45;
							if (trim)
								shortSender = ServletUtil.truncate(shortSender, 42).trim();
							shortSender = html.encode( shortSender );
							if (trim)
								shortSender += "&hellip;";  // must be after html encode
						}
						else if (hlc.startsWith("date:")) {
							dateString = line.substring( 5 ).trim();
							long dateLong = RFC822Date.parse822Date(dateString);
							if (dateLong > 0)
								setDate(dateLong);
						}
						else if (hlc.startsWith("subject:")) {
							subject = line.substring( 8 ).trim();
							shortSubject = subject;
							boolean trim = subject.length() > 75;
							if (trim)
								shortSubject = ServletUtil.truncate(subject, 72).trim();
							shortSubject = html.encode( shortSubject );
							if (trim)
								shortSubject += "&hellip;";  // must be after html encode
						}
						else if (hlc.startsWith("reply-to:")) {
							reply = getAddress( line.substring( 9 ).trim() );
						}
						else if (hlc.startsWith("to:") ) {
							ArrayList<String> list = new ArrayList<String>();
							getRecipientsFromList( list, line.substring( 3 ).trim(), true );
							if (list.isEmpty()) {
								// don't set
							} else if (to == null) {
								to = list.toArray(new String[list.size()]);
							} else if (cc == null) {
								// Susimail bug before 0.9.33, sent 2nd To line that was really Cc
								cc = list.toArray(new String[list.size()]);
							} else {	
								// add to the array, shouldn't happen
								for (int i = 0; i < to.length; i++) {
									list.add(i, to[i]);
								}
								to = list.toArray(new String[list.size()]);
							}
						}
						else if (hlc.startsWith("cc:")) {
							ArrayList<String> list = new ArrayList<String>();
							getRecipientsFromList( list, line.substring( 3 ).trim(), true );
							if (list.isEmpty()) {
								// don't set
							} else if (cc == null) {
								cc = list.toArray(new String[list.size()]);
							} else {	
								// add to the array, shouldn't happen
								for (int i = 0; i < cc.length; i++) {
									list.add(i, cc[i]);
								}
								cc = list.toArray(new String[list.size()]);
							}
						} else if (hlc.equals("x-spam-flag: yes")) {
							// TODO trust.spam.headers config
							isSpam = true;
						} else if (hlc.startsWith("content-type:")) {
							// this is duplicated in MailPart but
							// we want to know if we have attachments, even if
							// we haven't fetched the body
							contentType = line.substring(13).trim();
						} else if (hlc.startsWith("message-id:")) {
							messageID = line.substring(11).trim();
						}
					}
				}
				catch( Exception e ) {
					error += "Error parsing mail header: " + e.getClass().getName() + '\n';
					_log.error("Parse error", e);
				}		
			}
		}
		return headerLines;
	}
}

