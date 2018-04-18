/*
 * Created on 07.11.2004
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
 * $Revision: 1.4 $
 */
package i2p.susi.webmail;

import i2p.susi.util.Buffer;
import i2p.susi.util.CountingOutputStream;
import i2p.susi.util.DummyOutputStream;
import i2p.susi.util.EOFOnMatchInputStream;
import i2p.susi.util.FilenameUtil;
import i2p.susi.util.LimitInputStream;
import i2p.susi.util.ReadBuffer;
import i2p.susi.util.ReadCounter;
import i2p.susi.util.OutputStreamBuffer;
import i2p.susi.util.MemoryBuffer;
import i2p.susi.webmail.encoding.DecodingException;
import i2p.susi.webmail.encoding.Encoding;
import i2p.susi.webmail.encoding.EncodingFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * @author susi23
 */
class MailPart {

	private static final OutputStream DUMMY_OUTPUT = new DummyOutputStream();
	public final String[] headerLines;
	public final String type, encoding, name,
		description, disposition, charset, version;
	/** begin, end, and beginBody are relative to readBuffer.getOffset().
         *  begin is before the headers
         *  beginBody is after the headers
	 *  warning - end is exclusive
         */
	private final int beginBody, begin, end;
	/** fixme never set */
	public final String filename = null;
	public final List<MailPart> parts;
	public final boolean multipart, message;
	public final Buffer buffer;
	private final Log _log;

	/**
	 *  the decoded length if known, else -1
	 *  @since 0.9.34
	 */
	public int decodedLength = -1;

	/**
	 *  the UIDL of the mail, same for all parts
	 *  @since 0.9.33
	 */
	public final String uidl;
	private final int intID;
	
	/**
	 *  @param readBuffer has zero offset for top-level MailPart.
	 *  @param in used for reading (NOT readBuffer.getInputStream())
	 *  @param counter used for counting how much we have read.
         *                 Probably the same as InputStream but a different interface.
	 *  @param hdrlines non-null for top-level MailPart, where they
	 *         were already parsed in Mail. Null otherwise
	 */
	public MailPart(String uidl, AtomicInteger id, Buffer readBuffer, InputStream in, ReadCounter counter, String[] hdrlines) throws IOException
	{
		_log = I2PAppContext.getGlobalContext().logManager().getLog(MailPart.class);
		this.uidl = uidl;
		intID = id.getAndIncrement();
		buffer = readBuffer;
		
		parts = new ArrayList<MailPart>(4);

		if (hdrlines != null) {
			// from Mail headers
			headerLines = hdrlines;
			begin = 0;
		} else {
			begin = (int) counter.getRead();
			// parse header lines
			// We don't do \r\n\r\n because then we can miss the \r\n--
			// of the multipart boundary. So we do \r\n\r here,
			// and \n-- below. If it's not multipart, we will swallow the
			// \n below.
			EOFOnMatchInputStream eofin = new EOFOnMatchInputStream(in, Mail.HEADER_MATCH);
			MemoryBuffer decodedHeaders = new MemoryBuffer(4096);
			EncodingFactory.getEncoding("HEADERLINE").decode(eofin, decodedHeaders);
			if (!eofin.wasFound())
				if (_log.shouldDebug()) _log.debug("EOF hit before \\r\\n\\r\\n in MailPart");
			// Fixme UTF-8 to bytes to UTF-8
			headerLines = DataHelper.split(new String(decodedHeaders.getContent(), decodedHeaders.getOffset(), decodedHeaders.getLength()), "\r\n");
		}

		String boundary = null;
		String x_encoding = null;
		String x_disposition = null;
		String x_type = null;
		boolean x_multipart = false;
		boolean x_message = false;
		String x_name = null;
		String x_charset = null;
		String x_description = null;
		String x_version = null;

		for( int i = 0; i < headerLines.length; i++ )
		{
			String hlc = headerLines[i].toLowerCase(Locale.US);
			if( hlc.startsWith( "content-transfer-encoding: " ) ) {
				x_encoding = getFirstAttribute( headerLines[i] ).toLowerCase(Locale.US);
			}
			else if( hlc.startsWith( "content-disposition: " ) ) {
				x_disposition = getFirstAttribute( headerLines[i] ).toLowerCase(Locale.US);
				String str;
				str = getHeaderLineAttribute(headerLines[i], "filename*");
				if (str != null) {
					x_name = FilenameUtil.decodeFilenameRFC5987(str);
				} else {
					str = getHeaderLineAttribute(headerLines[i], "filename");
					if (str != null)
						x_name = str;
				}
			}
			else if( hlc.startsWith( "content-type: " ) ) {
				x_type = getFirstAttribute( headerLines[i] ).toLowerCase(Locale.US);
				/*
				 * extract boundary, name and charset from content type
				 */
				String str;
				str = getHeaderLineAttribute( headerLines[i], "boundary" );
				if( str != null )
					boundary = str;
				if (x_type.startsWith( "multipart" ) && boundary != null )
					x_multipart = true;
				else if (x_type.startsWith( "message" ) )
					x_message = true;
				str = getHeaderLineAttribute( headerLines[i], "name" );
				if( str != null )
					x_name = str;
				str = getHeaderLineAttribute( headerLines[i], "charset" );
				if( str != null )
					x_charset = str.toUpperCase(Locale.US);
			}
			else if( hlc.startsWith( "content-description: " ) ) {
				x_description = getFirstAttribute( headerLines[i] );
			}
			else if( hlc.startsWith( "mime-version: " ) ) {
				x_version = getFirstAttribute( headerLines[i] );
			}
		}

		encoding = x_encoding;
		disposition = x_disposition;
		type = x_type;
		multipart = x_multipart;
		message = x_message;
		name = x_name;
		charset = x_charset;
		description = x_description;
		version = x_version;

		// see above re: \n
		if (multipart) {
			// EOFOnMatch will eat the \n
			beginBody = (int) counter.getRead() + 1;
		} else {
			// swallow the \n
			int c = in.read();
			if (c != '\n')
				if (_log.shouldDebug()) _log.debug("wasn't a \\n, it was " + c);
			beginBody = (int) counter.getRead();
		}

		int tmpEnd = 0;
		/*
		 * parse body
		 */
		if( multipart ) {
			// See above for why we don't include the \r
			byte[] match = DataHelper.getASCII("\n--" + boundary);
			for (int i = 0; ; i++) {
				EOFOnMatchInputStream eofin = new EOFOnMatchInputStream(in, counter, match);
				if (i == 0) {
					// Read through first boundary line, not including "\r\n" or "--\r\n"
					OutputStream dummy = new DummyOutputStream();
					DataHelper.copy(eofin, dummy);  
					if (!eofin.wasFound())
						if (_log.shouldDebug()) _log.debug("EOF hit before first boundary " + boundary + " UIDL: " + uidl);
					if (readBoundaryTrailer(in)) {
						if (!eofin.wasFound())
							if (_log.shouldDebug()) _log.debug("EOF hit before first part body " + boundary + " UIDL: " + uidl);
						tmpEnd = (int) eofin.getRead();
						break;
					}
					// From here on we do include the \r
					match = DataHelper.getASCII("\r\n--" + boundary);
					eofin = new EOFOnMatchInputStream(in, counter, match);
				}
				MailPart newPart = new MailPart(uidl, id, buffer, eofin, eofin, null);
				parts.add( newPart );
				tmpEnd = (int) eofin.getRead();
				if (!eofin.wasFound()) {
					// if MailPart contains a MailPart, we may not have drained to the end
					DataHelper.copy(eofin, DUMMY_OUTPUT);  
					if (!eofin.wasFound())
						if (_log.shouldDebug()) _log.debug("EOF hit before end of body " + i + " boundary: " + boundary + " UIDL: " + uidl);
				}
				if (readBoundaryTrailer(in))
					break;
			}
		}
		else if( message ) {
			MailPart newPart = new MailPart(uidl, id, buffer, in, counter, null);
			// TODO newPart doesn't save message headers we might like to display,
			// like From, To, and Subject
			parts.add( newPart );			
			tmpEnd = (int) counter.getRead();
		} else {
			// read through to the end
			DataHelper.copy(in, DUMMY_OUTPUT);  
			tmpEnd = (int) counter.getRead();
		}
		end = tmpEnd;
		if (encoding == null || encoding.equals("7bit") || encoding.equals("8bit")) {
			decodedLength = end - beginBody;
		}
		//if (Debug.getLevel() >= Debug.DEBUG)
		//	if (_log.shouldDebug()) _log.debug("New " + this);
	}

	/**
	 *  A value unique across all the parts of this Mail,
	 *  and constant across restarts, so it may be part of a bookmark.
	 *
	 *  @since 0.9.34
	 */
	public int getID() { return intID; }


	/**
	 *  Swallow "\r\n" or "--\r\n".
	 *  We don't have any pushback if this goes wrong.
	 *
	 *  @return true if end of input
	 */
	private boolean readBoundaryTrailer(InputStream in) throws IOException {
		int c = in.read();
		if (c == '-') {
			// end of parts with this boundary
			c = in.read();
			if (c != '-') {
				if (_log.shouldDebug()) _log.debug("Unexpected char after boundary-: " + c);
				return true;
			}
			c = in.read();
			if (c == -1) {
				return true;
			}
			if (c != '\r') {
				if (_log.shouldDebug()) _log.debug("Unexpected char after boundary--: " + c);
				return true;
			}
			c = in.read();
			if (c != '\n')
				if (_log.shouldDebug()) _log.debug("Unexpected char after boundary--\\r: " + c);
			return true;
		} else if (c == '\r') {
			c = in.read();
			if (c != '\n')
				if (_log.shouldDebug()) _log.debug("Unexpected char after boundary\\r: " + c);
		} else {
			if (_log.shouldDebug()) _log.debug("Unexpected char after boundary: " + c);
		}
		return c == -1;
	}

	/**
         *  Synched because FileBuffer keeps stream open
         *
         *  @param offset 2 for sendAttachment, 0 otherwise, probably for \r\n
	 *  @since 0.9.13
	 */
	public synchronized void decode(int offset, Buffer out) throws IOException {
		String encg = encoding;
		if (encg == null) {
			//throw new DecodingException("No encoding specified");
			if (_log.shouldDebug()) _log.debug("Warning: no transfer encoding found, fallback to 7bit.");
			encg = "7bit";       
		}
		Encoding enc = EncodingFactory.getEncoding(encg);
		if(enc == null)
			throw new DecodingException(_t("No encoder found for encoding \\''{0}\\''.", WebMail.quoteHTML(encg)));
		InputStream in = null;
		LimitInputStream lin = null;
		CountingOutputStream cos = null;
		Buffer dout = null;
		try {
			lin = getRawInputStream(offset);
			if (decodedLength < 0) {
				cos = new CountingOutputStream(out.getOutputStream());
				dout = new OutputStreamBuffer(cos);
			} else {
	      			dout = out;
			}
			enc.decode(lin, dout);
			//dout.getOutputStream().flush();
		} catch (IOException ioe) {
			if (lin != null)
				if (_log.shouldDebug()) _log.debug("Decode IOE at in position " + lin.getRead()
				            + " offset " + offset, ioe);
			else if (cos != null)
				if (_log.shouldDebug()) _log.debug("Decode IOE at out position " + cos.getWritten()
				            + " offset " + offset, ioe);
			else
				if (_log.shouldDebug()) _log.debug("Decode IOE", ioe);
			throw ioe;
		} finally {
			if (lin != null) try { lin.close(); } catch (IOException ioe) {};
			buffer.readComplete(true);
			// let the servlet do this
			//if (cos != null) try { cos.close(); } catch (IOException ioe) {};
			//if (dout != null)
			//	dout.writeComplete(true);
			//out.writeComplete(true);
		}
		if (cos != null)
			decodedLength = (int) cos.getWritten();
	}

	/**
         *  Synched because FileBuffer keeps stream open
         *  Caller must close out
         *
	 *  @since 0.9.35
	 */
	public synchronized void outputRaw(OutputStream out) throws IOException {
		LimitInputStream lin = null;
		try {
			lin = getRawInputStream(0);
			DataHelper.copy(lin, out);
		} catch (IOException ioe) {
			if (_log.shouldDebug()) _log.debug("Decode IOE", ioe);
			throw ioe;
		} finally {
			if (lin != null) try { lin.close(); } catch (IOException ioe) {};
			buffer.readComplete(true);
		}
	}

	/**
         *  Synched because FileBuffer keeps stream open
         *  Caller must call readComplete() on buffer
         *
         *  @param offset 2 for sendAttachment, 0 otherwise, probably for \r\n
	 *  @since 0.9.35
	 */
	private synchronized LimitInputStream getRawInputStream(int offset) throws IOException {
		InputStream in = buffer.getInputStream();
		DataHelper.skip(in, buffer.getOffset() + beginBody + offset);
		return new LimitInputStream(in, end - beginBody - offset);
	}

	private static String getFirstAttribute( String line )
	{
		String result = null;
		int i = line.indexOf( ": " );
		if( i != - 1 ) {
			int j = line.indexOf(';', i + 2 );
			if( j == -1 )
				result = line.substring( i + 2 );
			else
				result = line.substring( i + 2, j );
			result = result.trim();
		}
		return result;
	}

	/**
	 *  @param attributeName must be lower case, will be matched case-insensitively
	 *  @return as found, not necessarily lower case
	 */
	private static String getHeaderLineAttribute( String line, String attributeName )
	{
		String lineLC = line.toLowerCase(Locale.US);
		String result = null;
		int h = 0;
		int l = attributeName.length();
		while (result == null) {
			int i = lineLC.indexOf(attributeName, h);
			// System.err.println( "i=" + i );
			if( i == -1 )
				break;
			h = i + l;
			int j = line.indexOf('=', i + l );
			// System.err.println( "j=" + j );
			if( j != -1 ) {
				int k = line.indexOf('"', j + 1 );
				int m = line.indexOf(';', j + 1 );
				// System.err.println( "k=" + k );
				if( k != -1 && ( m == -1 || k < m ) ) {
					/*
					 * we found a " before a possible ;
					 * 
					 * now we look for the 2nd (not quoted) "
					 */
					m = -1;
					int k2 = k + 1;
					while( true ) {
						m = line.indexOf('"', k2 );
						// System.err.println( "m=" + m + " '" + line.substring( m ) + "'" );
						if( m == -1 ) {
							break;
						}
						else {
							/*
							 * found one
							 */
							if( line.charAt( m - 1 ) != '\\' ) {
								/*
								 * its not quoted, so it is the one we look for 
								 */
								result = line.substring( k + 1, m );
								break;
							}
							else {
								/*
								 * this is quoted, so we extract the quote and continue the search
								 */
								line = line.substring( 0, m - 1 ) + line.substring( m );
								// System.err.println( "quoting found, line='" + line + "'" );
								k2 = m;
							}
						}
					}
				}
				else if( m != -1 ) {
					/*
					 * no " found, but a ;
					 */
					result = line.substring( j + 1, m ).trim();
				}
				else {
					/*
					 * no " found and no ;
					 */
					result = line.substring( j + 1 ).trim();
				}
			}
		}
		return result;
	}

	/** translate */
	private static String _t(String s, Object o) {
		return Messages.getString(s, o);
	}

	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder(1024);
		buf.append(
			"MailPart:" +
			"\n\tuidl:\t" + uidl +
			"\n\tbuffer:\t" + buffer +
			"\n\tbuffer offset:\t" + buffer.getOffset() +
			"\n\tbegin:\t" + begin +
			"\n\theader lines:\t" + headerLines.length +
			"\n"
		);
		for (int i = 0; i < headerLines.length; i++) {
			buf.append("\t\t\"").append(headerLines[i]).append("\"\n");
		}
		buf.append(
			"\tmultipart?\t" + multipart +
			"\n\tmessage?\t" + message +
			"\n\ttype:\t" + type +
			"\n\tencoding:\t" + encoding +
			"\n\tname:\t" + name +
			"\n\tdescription:\t" + description +
			"\n\tdisposition:\t" + disposition +
			"\n\tcharset:\t" + charset +
			"\n\tversion:\t" + version +
			"\n\tsubparts:\t" + parts.size() +
			"\n\tbeginbody:\t" + beginBody +
			"\n\tbody len:\t" + (end - beginBody) +
			"\n\tdecoded len:\t" + decodedLength +
			"\n\tend:\t" + (end - 1) +
			"\n\ttotal len:\t" + (end - begin) +
			"\n\tbuffer len:\t" + buffer.getLength()
		);
		return  buf.toString();
	}

/****
	public static void main(String[] args) {
		String test = "Content-Type: multipart/alternative; boundary=\"__________MIMEboundary__________\"; charset=\"UTF-8\"";
		System.out.println(test);
		String hlc = test.toLowerCase(Locale.US);
		if (hlc.startsWith( "content-type: ")) {
			System.out.println("find first attribute");
			String x_type = getFirstAttribute(test).toLowerCase(Locale.US);
			String x_charset = null;
			String boundary = null;
			System.out.println("find boundary");
			String str = getHeaderLineAttribute(test, "boundary");
			if( str != null )
				boundary = str;
			System.out.println("find charset");
			str = getHeaderLineAttribute(test, "charset");
			if( str != null )
				x_charset = str.toUpperCase(Locale.US);
			System.out.println("Type: " + x_type +
			                   "\nBoundary: " + boundary +
			                   "\nCharset: " + x_charset);
		}
	}
****/
}
