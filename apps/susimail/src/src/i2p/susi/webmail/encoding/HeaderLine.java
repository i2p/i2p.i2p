/*
 * Created on Nov 12, 2004
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
 * $Revision: 1.3 $
 */
package i2p.susi.webmail.encoding;

import i2p.susi.util.HexTable;
import i2p.susi.util.ReadBuffer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringBufferInputStream;

/**
 * @author susi
 */
public class HeaderLine implements Encoding {
	public static final String NAME = "HEADERLINE";
	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#getName()
	 */
	public String getName() {
		return NAME;
	}
	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#encode(java.lang.String)
	 */
	public String encode(String text) throws EncodingException {
		try {
			return encode( new StringBufferInputStream( text ) );
		} catch (IOException e) {
			throw new EncodingException( "IOException occured." );
		}
	}
	private static final int BUFSIZE = 2;
	private String encode(InputStream in) throws IOException
	{
		StringBuilder out = new StringBuilder();
		int l = 0, buffered = 0, tmp[] = new int[BUFSIZE];
		boolean quoting = false;
		boolean quote = false;
		boolean linebreak = false;
		String quotedSequence = null;
		int rest = 0;
		while( true ) {
			rest = in.available();
			while( rest > 0 && buffered < BUFSIZE ) {
				tmp[buffered++] = in.read();
				rest--;
			}
			if( rest == 0 && buffered == 0 )
				break;
			
			int c = tmp[0];
			buffered--;
			for( int j = 1; j < BUFSIZE; j++ )
				tmp[j-1] = tmp[j];
			
			quote = true;
			if( c > 32 && c < 127 && c != 61 ) {
				quote = false;
			}
			else if( ( c == 32 || c == 9 ) ) {
				quote = false;
				if( rest == 0 && buffered == 1 )
					quote = true;
				if( buffered > 0 && ( tmp[0] == '\r' || tmp[0] == '\n' ) )
					quote = true;
			}
			else if( c == 13 && buffered > 0 && tmp[0] == 10 ) {
				quote = false;
				linebreak = true;
				buffered--;
				for( int j = 1; j < BUFSIZE; j++ )
					tmp[j-1] = tmp[j];
			}
			if( quote ) {
				if( ! quoting ) {
					quotedSequence = "=?iso-8859-1?Q?";
					quoting = true;
				}
				quotedSequence += HexTable.table[ c ];
			}
			else {
				if( quoting ) {
					quotedSequence += "?=";
					int sl = quotedSequence.length();
					if( l + sl > 76 ) {
						/*
						 * wrap line
						 */
						out.append( "\r\n\t" );
						l = 0;
					}
					out.append( quotedSequence );
					l += sl;
					quoting = false;
				}
				if( linebreak ) {
					out.append( "\r\n" );
					linebreak = false;
					l = 0;
				}
				else {
					if( l > 76 ) {
						out.append( "\r\n\t" );
						l = 0;
					}
					out.append( (char)c );
					l++;
				}
			}
		}
		if( quoting ) {
			quotedSequence += "?=";
			int sl = quotedSequence.length();
			if( l + sl > 76 ) {
				/*
				 * wrap line
				 */
				out.append( "\r\n\t" );
				l = 0;
			}
			out.append( quotedSequence );
		}
		return out.toString();
	}
	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#decode(java.lang.String)
	 */
	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#encode(java.lang.String)
	 */
	public String encode( byte in[] ) throws EncodingException {
		try {
			return encode( new ByteArrayInputStream( in ) );
		} catch (IOException e) {
			throw new EncodingException( "IOException occured." );
		}
	}
	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#decode(java.lang.String)
	 */
	public ReadBuffer decode( byte in[] ) throws DecodingException {
		return decode( in, 0, in.length );
	}
	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#decode(java.lang.String)
	 */
	public ReadBuffer decode( byte in[], int offset, int length ) throws DecodingException {
		byte[] out = new byte[length];
		int written = 0;
		int end = offset + length;
		if( end > in.length )
			throw new DecodingException( "Index out of bound." );
		boolean linebreak = false;
		boolean lastCharWasQuoted = false;
		while( length-- > 0 ) {
			byte c = in[offset++];
			if( c == '=' ) {
				if( length > 0 ) {
					if( in[offset] == '?' ) {
						// System.err.println( "=? found at " + ( offset -1 ) );
						int f2 = offset + 1;
						for( ; f2 < end && in[f2] != '?'; f2++ );
						if( f2 < end ) {
							/*
							 * 2nd question mark found
							 */
							// System.err.println( "2nd ? found at " + f2 );
							int f3 = f2 + 1;
							for( ; f3 < end && in[f3] != '?'; f3++ );
							if( f3 < end ) {
								/*
								 * 3rd question mark found
								 */
								// System.err.println( "3rd ? found at " + f3 );
								int f4 = f3 + 1;
								for( ; f4 < end && in[f4] != '?'; f4++ );
								if( f4 < end - 1 && in[f4+1] == '=' ) {
									/*
									 * 4th question mark found, we are complete, so lets start
									 */
									String enc = ( in[f2+1] == 'Q' || in[f2+1] == 'q' ) ? "quoted-printable" : ( ( in[f2+1] == 'B' || in[f2+1] == 'b' ) ? "base64" : null );
									// System.err.println( "4th ? found at " + f4 + ", encoding=" + enc );
									if( enc != null ) {
										Encoding e = EncodingFactory.getEncoding( enc );
										if( e != null ) {
											// System.err.println( "encoder found" );
											ReadBuffer tmp = null;
											try {
												// System.err.println( "decode(" + (f3 + 1) + "," + ( f4 - f3 - 1 ) + ")" );
												tmp = e.decode( in, f3 + 1, f4 - f3 - 1 );
												for( int j = 0; j < tmp.length; j++ ) {
													byte d = tmp.content[ tmp.offset + j ];
													out[written++] = ( d == '_' ? 32 : d );
												}
												int distance = f4 + 2 - offset;
												offset += distance;
												length -= distance;
												lastCharWasQuoted = true;
												continue;
											}
											catch (Exception e1) {
											}
										}
									}
								}
							}
						}
					}
				}
			}
			else if( c == '\r' ) {
				if( length > 0 && in[offset] == '\n' ) {
					/*
					 * delay linebreak in case of long line
					 */
					linebreak = true;
					length--;
					offset++;
					continue;
				}
			}
			if( linebreak ) {
				linebreak = false;
				if( c != ' ' && c != '\t' ) {
					/*
					 * new line does not start with whitespace, so its not a new part of a
					 * long line
					 */
					out[written++] = '\r';
					out[written++] = '\n';
				}
				else {
					if( !lastCharWasQuoted )
						out[written++] = ' ';
					/*
					 * skip whitespace
					 */
					while( length > 0 && ( in[offset] == ' ' || in[offset] == '\t' ) ) {
						offset++;
						length--;
					}
					continue;
				}
			}
			/*
			 * print out everything else literally
			 */
			out[written++] = c;
			lastCharWasQuoted = false;
		}
		if( linebreak ) {
			out[written++] = '\r';
			out[written++] = '\n';
		}
			
		ReadBuffer readBuffer = new ReadBuffer();
		readBuffer.content = out;
		readBuffer.offset = 0;
		readBuffer.length = written;

		return readBuffer;
	}
	public ReadBuffer decode(String text) throws DecodingException {
		return text != null ? decode( text.getBytes() ) : null;
	}
	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#decode(i2p.susi.webmail.util.ReadBuffer)
	 */
	public ReadBuffer decode(ReadBuffer in) throws DecodingException {
		return decode( in.content, in.offset, in.length );
	}
	public static void main( String[] args ) throws EncodingException {
		String text = "Subject: test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test \r\n" +
		"From: Smörebröd <smoerebroed@mail.i2p>\r\n" +
		"To: äöüß <lalala@mail.i2p>\r\n";
		HeaderLine hl = new HeaderLine();
		System.out.println( hl.encode( text ) );
		System.out.println( hl.encode( "test ÄÖÜ" ) );
	}
}
