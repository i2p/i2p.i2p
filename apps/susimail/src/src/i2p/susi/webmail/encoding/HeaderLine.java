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

	/** @since 0.9.33 */
	private static boolean isWhiteSpace(char c) {
		return c == ' ' || c == '\t';
	}

	/** @since 0.9.33 */
/*
	private static boolean isControl(char c) {
		return (c < 32 && !isWhiteSpace(c)) ||
		       (c >= 127 && c < 160);
	}
*/

	/** @since 0.9.33 */
	private static boolean isControlOrMultiByte(char c) {
		return (c < 32 && !isWhiteSpace(c)) || c >= 127;
	}

	/** @since 0.9.33 */
/*
	private static boolean isSpecial(char c) {
		return c == '(' || c == ')' || c == '<' || c == '>' ||
		       c == '@' || c == ',' || c == ';' || c == ':' ||
		       c == '\\' || c == '"' || c == '.' || c == '[' ||
		       c == ']';
	}
*/

	/** @since 0.9.33 */
	private static boolean isPSpecial(char c) {
		return c == '!' || c == '*' || c == '+' || c == '-' ||
		       c == '/' || c == '=' || c == '_';
	}

	/** @since 0.9.33 */
	private static boolean isPSafe(char c) {
		return (c >= '0' && c <= '9') ||
		       (c >= 'a' && c <= 'z') ||
		       (c >= 'A' && c <= 'Z') ||
		       isPSpecial(c);
	}

	/** @since 0.9.33 */
/*
	private static boolean isAtom(char c) {
		return ! (isWhiteSpace(c) || isControl(c) || isSpecial(c));
	}
*/

	/**
	 *  Encode a single header line ONLY. Do NOT include the \r\n.
	 *  Returns a string of one or more lines including the trailing \r\n.
	 *  Field-name will not be encoded, must be less than 62 chars.
	 *
	 *  The fieldBody is treated as "unstructured text",
	 *  which is suitable only for the field names "Subject" and "Comments".
	 *  We do NOT tokenize into structured fields.
	 *
	 *  To make things easy, we either encode the whole field body as RFC 2047,
	 *  or don't encode at all. If it's too long for a single line, we
	 *  encode it, even if we didn't otherwise have to.
	 *  We don't do quoted-string.
	 *
	 *  This will not split multibyte chars, including supplementary chars,
	 *  across lines.
	 *
	 *  TODO this will not work for quoting structured text
	 *  such as recipient names on the "To" and "Cc" lines.
	 *
	 *  @param str must start with "field-name: "
	 */
	@Override
	public String encode(String str) throws EncodingException {
		str = str.trim();
		int l = str.indexOf(": ");
		if (l <= 0 || l >= 64)
			throw new EncodingException("bad 'field-name: '" + str);
		l += 2;
		boolean quote = false;
		if (str.length() > 76) {
			quote = true;
		} else {
			for (int i = l; i < str.length(); i++) {
				char c = str.charAt(i);
				if (isControlOrMultiByte(c) || isPSpecial(c)) {
					quote = true;
					break;
				}
			}
		}
		if (!quote)
			return str + "\r\n";
		// Output encoded.
		StringBuilder out = new StringBuilder();
		out.append(str.substring(0, l));
		int start = l;
		StringBuilder qc = new StringBuilder(16);
		for (int i = start; i < str.length(); i++) {
			// use codePointAt(), not charAt(), so supplementary chars work
			char c = str.charAt(i);
			// put the encoded char in the temp buffer
			qc.setLength(0);
			if (c <= 127) {
				// single byte char
				if (c == ' ') {
					qc.append('_');
				} else if (isPSafe(c) && c != '_' && c != '?') {
					qc.append(c);
				} else {
					qc.append(HexTable.table[c]);
				}
			} else {
				// multi-byte char, possibly supplementary
				byte[] utf;
				if (Character.isHighSurrogate(c) && i < str.length() - 1) {
					// use substring() to get the whole thing if multi-char
					utf = DataHelper.getUTF8(str.substring(i, i + 2));
					// increment i below after start test
				} else {
					utf = DataHelper.getUTF8(String.valueOf(c));
				}
				for (int j = 0; j < utf.length; j++) {
					int b = utf[j] & 0xff;
					qc.append(HexTable.table[b]);
				}
			}
			// now see if we have room
			if (i == start) {
				out.append("=?utf-8?Q?");
				l += 10;
			} else {
				// subsequent chars
				if (l + 2 + qc.length() > 76) {
					out.append("?=\r\n\t=?utf-8?Q?");
					l = 11;
				}
			}
			if (Character.isHighSurrogate(c) && i < str.length() - 1)
				i++;
			out.append(qc);
			l += qc.length();
		}
		out.append("?=\r\n");
		return out.toString();
	}

	/**
	 *  Decode all the header lines, up through \r\n\r\n,
	 *  and puts them in the ReadBuffer, including the \r\n\r\n
	 */
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
	public static void main( String[] args ) throws EncodingException {
		test("Subject: not utf8");
		test("Subject: a=b c+d");
		test("Subject: está");
		test("Subject: 🚚 ORDER SHIPPED");
		test("12345678: 12345678901234567890123456789012345678901234567890123456789012345");
		test("12345678: 123456789012345678901234567890123456789012345678901234567890123456");
		test("12345678: 1234567890123456789012345678901234567890123456789012345678901234567");
		test("12345678: 1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789");
		test("12345678: 12345678901234567890123456789012345678901234567890123456789012345@");
		test("12345678: 123456789012345678901234567890123456789012345678901234567890123456@");
		test("12345678: 123456789012345678901234567890123456789012345678901@234567890123456789");
		test("12345678: 1234567890123456789012345678901234567890123456789012@34567890123456789");
		test("12345678: 12345678901234567890123456789012345678901234567890123@4567890123456789");
		test("12345678: 123456789012345678901234567890123456789012345678901234@567890123456789");
	}

	private static void test(String x) {
		HeaderLine hl = new HeaderLine();
		String orig = x;
		System.out.println(x);
		try {
			x = hl.encode(x);
		} catch (EncodingException e) {
			e.printStackTrace();
			return;
		}
		System.out.print(x);
		try {
			ReadBuffer rb = hl.decode(x);
			String rt = DataHelper.getUTF8(rb.content, rb.offset, rb.length);
			if (rt.equals(orig + "\r\n")) {
				System.out.println("Test passed\n");
			} else {
				System.out.print(rt);
				System.out.println("*** Test failed ***\n");
			}
		} catch (DecodingException e) {
			e.printStackTrace();
		}
	}
****/
}
