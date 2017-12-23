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

import i2p.susi.debug.Debug;
import i2p.susi.util.HexTable;
import i2p.susi.util.ReadBuffer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import net.i2p.data.DataHelper;

/**
 *  Ref:
 *  http://en.wikipedia.org/wiki/MIME#Encoded-Word
 *  http://tools.ietf.org/html/rfc2047
 *  https://jeffreystedfast.blogspot.com/2013/09/time-for-rant-on-mime-parsers.html
 *  https://jeffreystedfast.blogspot.com/2013/08/why-decoding-rfc2047-encoded-headers-is.html
 *
 * @author susi
 */
public class HeaderLine extends Encoding {
	public static final String NAME = "HEADERLINE";

	public String getName() {
		return NAME;
	}

	private static final int BUFSIZE = 2;

	/**
	 *  This will split multibyte chars across lines,
	 *  see 4th ref above
	 *
	 *  @throws UnsupportedOperationException always
	 */
	public String encode(byte in[]) throws EncodingException {
		throw new UnsupportedOperationException("use encode(String)");
	}

	@Override
	public String encode(String str) throws EncodingException {
		StringBuilder out = new StringBuilder();
		int l = 0;
		boolean quoting = false;
		boolean quote = false;
		boolean linebreak = false;
		StringBuilder quotedSequence = null;
		int index = 0;
		while( true ) {
			if (index >= str.length())
				break;
			char c = str.charAt(index++);
			quote = true;
			if( c > 32 && c < 127 && c != 61 ) {
				quote = false;
			}
			else if( ( c == 32 || c == 9 ) ) {
				quote = false;
				if (index >= str.length())
					quote = true;
				else if (str.charAt(index) == '\r' || str.charAt(index) == '\n')
					quote = true;
			}
			else if (c == '\r' && index < str.length() && str.charAt(index) == '\n') {
				quote = false;
				linebreak = true;
				index++;
			}
			if( quote ) {
				// the encoded char
				StringBuilder qc = new StringBuilder(16);
				if (c <= 127) {
					// single byte char
					qc.append(HexTable.table[c]);
				} else {
					byte[] utf = DataHelper.getUTF8(String.valueOf(c));
					for (int j = 0; j < utf.length; j++) {
						int b = utf[j] & 0xff;
						qc.append(HexTable.table[b]);
					}
				}
				if (quoting) {
					// would it be too long?
					if (l + quotedSequence.length() + qc.length() + 2 >= 76) {
						// close q-seq, wrap line, and start a new q-seq
						out.append(quotedSequence);
						out.append("?=\r\n\t");
						l = 1;
						quoting = false;
					}
				}
				if (!quoting) {
					// close q-seq, wrap line, and start a new q-seq
					quotedSequence = new StringBuilder(64);
					quotedSequence.append("=?utf-8?Q?");
					quoting = true;
				}
				quotedSequence.append(qc);
			}
			else {
				if( quoting ) {
					quotedSequence.append("?=");
					int sl = quotedSequence.length();
					if( l + sl >= 76 ) {
						/*
						 * wrap line
						 */
						out.append( "\r\n\t" );
						l = 1;
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
					if( l >= 76 ) {
						out.append( "\r\n\t" );
						l = 1;
					}
					out.append(c);
					l++;
				}
			}
		}
		if( quoting ) {
			quotedSequence.append("?=");
			int sl = quotedSequence.length();
			if( l + sl >= 76 ) {
				/*
				 * wrap line
				 */
				out.append( "\r\n\t" );
				l = 1;
			}
			out.append( quotedSequence );
		}
		return out.toString();
	}

	public ReadBuffer decode( byte in[], int offset, int length ) throws DecodingException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
		int written = 0;
		int end = offset + length;
		if( end > in.length )
			throw new DecodingException( "Index out of bound." );
		boolean linebreak = false;
		boolean lastCharWasQuoted = false;
		int lastSkip = 0;
		while( length-- > 0 ) {
			byte c = in[offset++];
			if( c == '=' ) {
				if( length > 0 ) {
					if( in[offset] == '?' ) {
						// System.err.println( "=? found at " + ( offset -1 ) );
						// save charset position here f1+1 to f2-1
						int f1 = offset;
						int f2 = f1 + 1;
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
												// get charset
												String charset = new String(in, f1 + 1, f2 - f1 - 1, "ISO-8859-1");
												String clc = charset.toLowerCase(Locale.US);
												if (clc.equals("utf-8") || clc.equals("utf8")) {
													if (enc.equals("quoted-printable")) {
														for( int j = 0; j < tmp.length; j++ ) {
															byte d = tmp.content[ tmp.offset + j ];
															out.write( d == '_' ? 32 : d );
														}
													} else {
														out.write(tmp.content, tmp.offset, tmp.length);
													}
												} else {
													// decode string
													String decoded = new String(tmp.content, tmp.offset, tmp.length, charset);
													// encode string
													byte[] utf8 = DataHelper.getUTF8(decoded);
													if (enc.equals("quoted-printable")) {
														for( int j = 0; j < utf8.length; j++ ) {
															byte d = utf8[j];
															out.write( d == '_' ? 32 : d );
														}
													} else {
														out.write(utf8);
													}
												}
												int distance = f4 + 2 - offset;
												offset += distance;
												length -= distance;
												lastCharWasQuoted = true;
												continue;
											} catch (IOException e1) {
												Debug.debug(Debug.ERROR, e1.toString());
											} catch (RuntimeException e1) {
												Debug.debug(Debug.ERROR, e1.toString());
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
					// The ReadBuffer can contain the body too.
					// If we just had a linebreak, we are done...
					// don't keep parsing!
					if( length > 2 && in[offset+1] == '\r' && in[offset+2] == '\n')
						break;
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
					out.write('\r');
					out.write('\n');
					lastSkip = 0;
				}
				else {
					if( !lastCharWasQuoted )
						out.write(' ');
					/*
					 * skip whitespace
					 */
					int skipped = 1;
					while( length > 0 && ( in[offset] == ' ' || in[offset] == '\t' ) ) {
						if( lastSkip > 0 && skipped >= lastSkip ) {
							break;
						}
						offset++;
						length--;
						skipped++;
					}
					if( lastSkip == 0 && skipped > 0 ) {
						lastSkip = skipped;
					}
					continue;
				}
			}
			/*
			 * print out everything else literally
			 */
			out.write(c);
			lastCharWasQuoted = false;
		}
		if( linebreak ) {
			out.write('\r');
			out.write('\n');
		}
			
		return new ReadBuffer(out.toByteArray(), 0, out.size());
	}

/*****
TODO put UTF-8 back and move to a unit test
	public static void main( String[] args ) throws EncodingException {
		String text = "Subject: test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test \r\n" +
		"From: UTF8 <smoerebroed@mail.i2p>\r\n" +
		"To: UTF8 <lalala@mail.i2p>\r\n";
		HeaderLine hl = new HeaderLine();
		System.out.println( hl.encode( text ) );
		System.out.println( hl.encode( "test UTF8" ) );
		System.out.println( hl.encode( "UTF8" ) );
	}
****/
}
