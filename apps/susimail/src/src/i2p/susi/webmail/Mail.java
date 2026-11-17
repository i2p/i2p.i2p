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

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
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
	/**
	 *  Also used by MailPart
	 *  See MailPart for why we don't do \r\n\r\n
	 */
	static final byte HEADER_MATCH[] = DataHelper.getASCII("\r\n\r");

	private long size;
	public String sender,   // as received, trimmed only, not HTML escaped
		reply,          // address only, enclosed by <>
		subject,	// as received, trimmed only, not HTML escaped, non-null, default ""
		dateString,
		//formattedSender,    // address only, enclosed with <>, not HTML escaped
		formattedDate,  // US Locale, UTC
		localFormattedDate,  // Current Locale, local time zone
		shortSender,    // Actual mailbox when available; HTML escaped
		shortSubject,   // HTML escaped, truncated with hellip, non-null, default ""
		quotedDate;  // Current Locale, local time zone, longer format
	public final String uidl;
	public Date date;
	private Buffer header, body;
	private MailPart part;
        /** May be null. Non-empty if non-null. Not HTML escaped. */
	String[] to, cc;        // addresses only, enclosed by <>
	private boolean isNew, isSpam, headersParsed;
	private MailHeaders parsedHeaders;
	private SenderIdentity senderIdentity;
	private boolean ambiguousHeaders, changedHeaders, invalidReplyTo;
	private DKIMVerifier.Result authentication = DKIMVerifier.UNVERIFIED;
	private boolean locallySent;
	private boolean bodyParseFailed;
	private String verificationConfiguration = "";
	private long verifiedFileLength, verifiedFileTime;
	public String contentType;
	public String messageID; // as received, trimmed only, probably enclosed with <>, not HTML escaped

	public String error;

	public boolean markForDeletion;
	private final Log _log;
	
	public Mail(String uidl) {
		this(uidl, false);
	}

	Mail(String uidl, boolean locallySent) {
		this.locallySent = locallySent;
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
            if (rb == null) throw new IOException("Message header is unavailable");
			setHeader(rb, rb.getInputStream(), true);
		} catch (IOException ioe) {
			header = body = null; part = null; headersParsed = false; bodyParseFailed = true;
            authentication = new DKIMVerifier.Result(DKIMVerifier.State.FAILED, "", "Saved message headers failed a read or integrity check.");
			if (_log.shouldWarn())
				_log.warn("Header read error", ioe);
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
		refreshSecurity();
		return body;
	}

	public synchronized void setBody(Buffer rb) {
		if (rb == null)
			return;
		// A failed replacement must not leave an old parsed body available.
		body = null;
		part = null;
		bodyParseFailed = false;
		boolean success = false;
		CountingInputStream in = null;
		try {
			in = new CountingInputStream(rb.getInputStream());
			String[] headerLines = setHeader(rb, in, false);
			if (headerLines == null)
				throw new IOException("Invalid message headers");
			part = new MailPart(uidl, new AtomicInteger(), rb, in, in, headerLines);
			rb.readComplete(true);
			// may only be available after reading and calling readComplete()
			size = rb.getLength();
			body = rb;
			verificationConfiguration = DKIMVerifier.configurationStamp();
			authentication = DKIMVerifier.verify(rb, parsedHeaders, senderIdentity,
				I2PAppContext.getGlobalContext().clock().now() / 1000, !locallySent && !(this instanceof Draft));
			if (rb instanceof FileBuffer) {
				java.io.File file = ((FileBuffer) rb).getFile();
				verifiedFileLength = file.length();
				verifiedFileTime = file.lastModified();
			}
			
			success = true;
		} catch (IOException de) {
			_log.error("Decode error", de);
		} catch (RuntimeException e) {
			_log.error("Parse error", e);
		} finally {
			if (!success) { body = null; part = null; bodyParseFailed = true; }
			if (in != null) try { in.close(); } catch (IOException ioe) {}
			rb.readComplete(success);
		}
	}

	public synchronized boolean hasBody() {
		refreshSecurity();
		return body != null;
	}

	public synchronized MailPart getPart() {
		refreshSecurity();
		return part;
	}

	public synchronized boolean hasPart() {
		return part != null;
	}

	public synchronized String getSenderAddress() {
		return senderIdentity != null ? senderIdentity.address : "";
	}

	public synchronized boolean isReplyRedirected() {
		SenderIdentity destination = SenderIdentity.parse(reply);
		return senderIdentity != null && destination != null && !destination.address.equals(senderIdentity.address);
	}

	public synchronized boolean canReplySafely() {
        refreshSecurity();
        return headersParsed && !bodyParseFailed && !ambiguousHeaders && !changedHeaders && !invalidReplyTo && senderIdentity != null && authentication.state != DKIMVerifier.State.FAILED;
    }

	public synchronized boolean hasUnsafeContent() {
        refreshSecurity(); return !headersParsed || bodyParseFailed || ambiguousHeaders || changedHeaders || authentication.state == DKIMVerifier.State.FAILED;
    }

	public synchronized DKIMVerifier.Result getAuthentication() { refreshSecurity(); return authentication; }

	private void refreshSecurity() {
        if (body == null || bodyParseFailed) return;
		if (body instanceof FileBuffer) {
			java.io.File file = ((FileBuffer) body).getFile();
			if (file.length() != verifiedFileLength || file.lastModified() != verifiedFileTime) {
				setBody(body);
				return;
			}
		}
		String current = DKIMVerifier.configurationStamp();
		long now = I2PAppContext.getGlobalContext().clock().now() / 1000;
		if (!current.equals(verificationConfiguration) || now >= authentication.validUntil) {
			verificationConfiguration = current;
			authentication = DKIMVerifier.verify(body, parsedHeaders, senderIdentity, now, !locallySent && !(this instanceof Draft));
		}
	}

	

	

	
	

	/** Folder context comes from MailCache, never from an email header. */
	synchronized void setLocallySent(boolean locallySent) { this.locallySent = locallySent; }

	public synchronized java.util.List<String> getSecurityWarnings() {
		refreshSecurity();
		java.util.List<String> warnings = new ArrayList<String>();
		if (!headersParsed || ambiguousHeaders)
			warnings.add("This message has missing or conflicting identity headers. Automatic replies are blocked.");
		if (senderIdentity == null)
			warnings.add("The sender address is ambiguous or unsupported. Do not rely on the display name.");
		if (invalidReplyTo)
			warnings.add("This message has an invalid reply address. Automatic replies are blocked.");
		if (changedHeaders)
			warnings.add("The message headers changed since the preview. Automatic replies and inline attachments are blocked.");
		if (bodyParseFailed)
			warnings.add("The message structure could not be read safely. Automatic replies and attachments are blocked.");
		if (SenderIdentity.hasInvisible(sender) || SenderIdentity.hasInvisible(subject))
			warnings.add("The sender or subject contains hidden formatting characters.");
		return warnings;
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

	public synchronized boolean isSpam() { return isSpam; }

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
			!contentType.contains("text/html") &&
			!contentType.contains("multipart/alternative") &&
			!contentType.contains("multipart/related") &&
			!contentType.contains("multipart/signed");
	}

	/**
	 * 
	 * @param address E-mail address to be validated
	 * @return Is the e-mail address valid?
	 */
	public static boolean validateAddress( String address )
	{
		return SenderIdentity.parse(address) != null;
	}

	/**
	 * Returns the first email address portion, enclosed by &lt;&gt;
	 * @param address
	 */
	public static String getAddress(String address )
	{
		SenderIdentity identity = SenderIdentity.parse(address);
		return identity != null ? '<' + identity.address + '>' : null;
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
	static {
		// the router sets the JVM time zone to UTC but saves the original here so we can get it
		TimeZone tz = SystemVersion.getSystemTimeZone();
		localDateFormatter.setTimeZone(tz);
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
		}
		quotedDate = DataHelper.formatTime(dateLong);
	}

	/**
	 * @return all headers, to pass to MailPart, or null on error
	 */
	private String[] parseHeaders(InputStream in)
	{
		String[] headerLines = null;
		error = "";
		headersParsed = false;
		authentication = DKIMVerifier.UNVERIFIED;
		sender = subject = shortSubject = "";
		shortSender = unknown;
		reply = contentType = messageID = dateString = null;
		to = cc = null;
		senderIdentity = null;
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
					MailHeaders incoming = MailHeaders.read(eofin);
					if (!eofin.wasFound())
						throw new IOException("Incomplete message headers");
					headerLines = incoming.decodedLines();
					if (!(this instanceof Draft) && parsedHeaders != null && !parsedHeaders.raw.equals(incoming.raw))
						changedHeaders = true;
					parsedHeaders = incoming;
					ambiguousHeaders = incoming.ambiguousField() != null;
					date = null;
					invalidReplyTo = isSpam = false;
					for (int j = 0; j < headerLines.length; j++) {
						String line = headerLines[j];
						if( line.length() == 0 )
							break;

						String hlc = line.toLowerCase(Locale.US);
						if (hlc.startsWith("from:")) {
							sender = line.substring( 5 ).trim();
						}
						else if (hlc.startsWith("date:")) {
							dateString = line.substring( 5 ).trim();
							long dateLong = RFC822Date.parse822Date(dateString);
							if (dateLong > 0)
								setDate(dateLong);
						}
						else if (hlc.startsWith("subject:")) {
							subject = line.substring( 8 ).trim();
							shortSubject = SenderIdentity.display(subject);
							boolean trim = subject.length() > 75;
							if (trim)
								shortSubject = ServletUtil.truncate(shortSubject, 72).trim();
							shortSubject = html.encode( shortSubject );
							if (trim)
								shortSubject += "&hellip;";  // must be after html encode
						}
						else if (hlc.startsWith("reply-to:")) {
							reply = getAddress( line.substring( 9 ).trim() );
							invalidReplyTo = reply == null;
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
						} else if (hlc.startsWith("x-uidl:")) {
							// shouldn't happen unless you imported or
							// copied external emails to the cache
							if (!uidl.equals(line.substring(7).trim()) && _log.shouldWarn())
								_log.warn("UIDL mismatch, may be unable to load body later. Original: " + uidl +
								          " b64: " + Base64.encode(uidl) +
								          " header: " + line.substring(7).trim());
						}
					}
					senderIdentity = SenderIdentity.parse(sender);
					// Show the actual mailbox in the inbox, where display-name spoofing starts.
					shortSender = html.encode(senderIdentity != null ? senderIdentity.address : SenderIdentity.display(sender));
					headersParsed = true;
				}
				catch( Exception e ) {
					headerLines = null;
					senderIdentity = null;
					error += "Error parsing mail header: " + e.getClass().getName() + '\n';
					_log.error("Parse error", e);
				}		
			}
		}
		return headerLines;
	}
}

