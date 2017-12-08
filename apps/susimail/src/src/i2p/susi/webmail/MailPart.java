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

import i2p.susi.debug.Debug;
import i2p.susi.util.ReadBuffer;
import i2p.susi.webmail.encoding.DecodingException;
import i2p.susi.webmail.encoding.Encoding;
import i2p.susi.webmail.encoding.EncodingFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.i2p.data.DataHelper;

/**
 * @author susi23
 */
class MailPart {

	public final String[] headerLines;
	public final String type, encoding, name,
		description, disposition, charset, version;
	private final int beginBody, begin, end;
	/** fixme never set */
	public final String filename = null;
	public final List<MailPart> parts;
	public final boolean multipart, message;
	public final ReadBuffer buffer;
	/**
	 *  the UIDL of the mail, same for all parts
	 *  @since 0.9.33
	 */
	public final String uidl;
	
/// todo add UIDL to constructors, use in WebMail.showpart()

	public MailPart(String uidl,  ReadBuffer readBuffer) throws DecodingException
	{
		this(uidl, readBuffer, readBuffer.offset, readBuffer.length);
	}

	public MailPart(String uidl, ReadBuffer readBuffer, int offset, int length) throws DecodingException
	{
		this.uidl = uidl;
		begin = offset;
		end = offset + length;
		buffer = readBuffer;
		
		parts = new ArrayList<MailPart>();

		/*
		 * parse header lines
		 */
		int bb = end;
		for( int i = begin; i < end - 4; i++ ) {
			if( buffer.content[i] == '\r' &&
					buffer.content[i+1] == '\n' &&
					buffer.content[i+2] == '\r' &&
					buffer.content[i+3] == '\n' ) {
				bb = i + 2;
				break;
			}
		}
		beginBody = bb;
			
		ReadBuffer decodedHeaders = EncodingFactory.getEncoding( "HEADERLINE" ).decode( buffer.content, begin, beginBody - begin );
		headerLines = DataHelper.split(new String(decodedHeaders.content, decodedHeaders.offset, decodedHeaders.length), "\r\n");

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
				str = getHeaderLineAttribute( headerLines[i], "filename" );
				if( str != null )
					x_name = str;
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
				if (x_type.startsWith( "message" ) )
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

		/*
		 * parse body
		 */
		int beginLastPart = -1;
		if( multipart ) {
			byte boundaryArray[] = DataHelper.getUTF8(boundary);
			for( int i = beginBody; i < end - 4; i++ ) {
				if( buffer.content[i] == '\r' &&
						buffer.content[i+1] == '\n' &&
						buffer.content[i+2] == '-' &&
						buffer.content[i+3] == '-' ) {
					/*
					 * begin of possible boundary line
					 */
					int j = 0;
					for( ; j < boundaryArray.length && i + 4 + j < end; j++ )
						if( buffer.content[ i + 4 + j ] != boundaryArray[j] )
							break;
					if( j == boundaryArray.length ) {
						int k = i + 4 + j;
						if( k < end - 2 &&
								buffer.content[k] == '-' &&
								buffer.content[k+1] == '-' )
							k += 2;
						
						if( k < end - 2 &&
								buffer.content[k] == '\r' &&
								buffer.content[k+1] == '\n' ) {
							
							k += 2;
							
							if( beginLastPart != -1 ) {
								int endLastPart = Math.min(i + 2, end);
								MailPart newPart = new MailPart(uidl, buffer, beginLastPart, endLastPart - beginLastPart);
								parts.add( newPart );
							}
							beginLastPart = k;
						}
						i = k;
					}
				}					
			}
		}
		else if( message ) {
			MailPart newPart = new MailPart(uidl, buffer, beginBody, end - beginBody);
			parts.add( newPart );			
		}
	}

	/**
         *  @param offset 2 for sendAttachment, 0 otherwise, probably for \r\n
	 *  @since 0.9.13
	 */
	public ReadBuffer decode(int offset) throws DecodingException {
		String encg = encoding;
		if (encg == null) {
			//throw new DecodingException("No encoding specified");
			Debug.debug(Debug.DEBUG, "Warning: no transfer encoding found, fallback to 7bit.");
			encg = "7bit";       
		}
		Encoding enc = EncodingFactory.getEncoding(encg);
		if(enc == null)
			throw new DecodingException(_t("No encoder found for encoding \\''{0}\\''.", WebMail.quoteHTML(encg)));
		return enc.decode(buffer.content, beginBody + offset, end - beginBody - offset);
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

	private static String getHeaderLineAttribute( String line, String attributeName )
	{
		String result = null;
		int h = 0;
		int l = attributeName.length();
		while( true ) {
			int i = line.indexOf( attributeName, h );
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
					result = line.substring( j + 1 );
				}
			}
		}
		return result;
	}

	/** translate */
	private static String _t(String s, Object o) {
		return Messages.getString(s, o);
	}
}
