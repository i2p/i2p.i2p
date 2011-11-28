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

import i2p.susi.util.ReadBuffer;
import i2p.susi.webmail.encoding.EncodingFactory;

import java.util.ArrayList;
import java.util.Locale;

/**
 * @author susi23
 */
public class MailPart {

	public String headerLines[], type, boundary, encoding, name,
		filename, description, disposition, charset, version;
	public int beginBody, begin, end;
	public ArrayList parts = null;
	public boolean multipart = false, message = false;
	public ReadBuffer buffer = null;
	
	public void parse( ReadBuffer readBuffer )
	{
		parse( readBuffer, readBuffer.offset, readBuffer.length );
	}

	public void parse( ReadBuffer readBuffer, int offset, int length )
	{
		begin = offset;
		end = offset + length;
		buffer = readBuffer;
		
		if( parts == null )
			parts = new ArrayList();
		else
			parts.clear();

		/*
		 * parse header lines
		 */
		beginBody = end;
		for( int i = begin; i < end - 4; i++ )
			if( buffer.content[i] == '\r' &&
					buffer.content[i+1] == '\n' &&
					buffer.content[i+2] == '\r' &&
					buffer.content[i+3] == '\n' ) {
				beginBody = i + 2;
				break;
			}
			
		ReadBuffer decodedHeaders = null;
		try {
			decodedHeaders = EncodingFactory.getEncoding( "HEADERLINE" ).decode( buffer.content, begin, beginBody - begin );
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		if (decodedHeaders == null)
			return;
		headerLines = new String( decodedHeaders.content, decodedHeaders.offset, decodedHeaders.length ).split( "\r\n" );

		for( int i = 0; i < headerLines.length; i++ )
		{
			if( headerLines[i].toLowerCase(Locale.US).startsWith( "content-transfer-encoding: " ) ) {
				encoding = getFirstAttribute( headerLines[i] ).toLowerCase(Locale.US);
			}
			else if( headerLines[i].toLowerCase(Locale.US).startsWith( "content-disposition: " ) ) {
				disposition = getFirstAttribute( headerLines[i] ).toLowerCase(Locale.US);
				String str;
				str = getHeaderLineAttribute( headerLines[i], "filename" );
				if( str != null )
					name = str;
			}
			else if( headerLines[i].toLowerCase(Locale.US).startsWith( "content-type: " ) ) {
				type = getFirstAttribute( headerLines[i] ).toLowerCase(Locale.US);
				/*
				 * extract boundary, name and charset from content type
				 */
				String str;
				str = getHeaderLineAttribute( headerLines[i], "boundary" );
				if( str != null )
					boundary = str;
				if( type != null && type.startsWith( "multipart" ) && boundary != null )
					multipart = true;
				if( type != null && type.startsWith( "message" ) )
					message = true;
				str = getHeaderLineAttribute( headerLines[i], "name" );
				if( str != null )
					name = str;
				str = getHeaderLineAttribute( headerLines[i], "charset" );
				if( str != null )
					charset = str.toUpperCase(Locale.US);
			}
			else if( headerLines[i].toLowerCase(Locale.US).startsWith( "content-description: " ) ) {
				description = getFirstAttribute( headerLines[i] );
			}
			else if( headerLines[i].toLowerCase(Locale.US).startsWith( "mime-version: " ) ) {
				version = getFirstAttribute( headerLines[i] );
			}
		}
		/*
		 * parse body
		 */
		int beginLastPart = -1;
		if( multipart ) {
			byte boundaryArray[] = boundary.getBytes();
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
							
							int endLastPart = i + 2;
							if( beginLastPart != -1 ) {
								MailPart newPart = new MailPart();
								newPart.parse( buffer, beginLastPart, endLastPart - beginLastPart );
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
			MailPart newPart = new MailPart();
			newPart.parse( buffer, beginBody, end );
			parts.add( newPart );			
		}
	}
	public static String getFirstAttribute( String line )
	{
		String result = null;
		int i = line.indexOf( ": " );
		if( i != - 1 ) {
			int j = line.indexOf( ";", i + 2 );
			if( j == -1 )
				result = line.substring( i + 2 );
			else
				result = line.substring( i + 2, j );
			result = result.trim();
		}
		return result;
	}
	public static String getHeaderLineAttribute( String line, String attributeName )
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
			int j = line.indexOf( "=", i + l );
			// System.err.println( "j=" + j );
			if( j != -1 ) {
				int k = line.indexOf( "\"", j + 1 );
				int m = line.indexOf( ";", j + 1 );
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
						m = line.indexOf( "\"", k2 );
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
	/**
	 * @param mail
	 */
	public static void parse(Mail mail) {
		// TODO Auto-generated method stub
		
	}
}
