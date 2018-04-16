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
import i2p.susi.util.Buffer;
import i2p.susi.util.ReadBuffer;
import i2p.susi.util.MemoryBuffer;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

import net.i2p.data.DataHelper;
import net.i2p.util.Log;

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
	 *  @param str must start with "field-name: ", must have non-whitespace after that
	 */
	@Override
	public String encode(String str) throws EncodingException {
		str = str.trim();
		int l = str.indexOf(": ");
		if (l <= 0 || l >= 64)
			throw new EncodingException("bad field-name: " + str);
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

	// could be 75 for quoted-printable only
	private static final int DECODE_MAX = 256;

	/**
	 *  Decode all the header lines, up through \r\n\r\n,
	 *  and puts them in the ReadBuffer, including the \r\n\r\n
	 */
	public void decode(InputStream in, Buffer bout) throws IOException {
		OutputStream out = bout.getOutputStream();
		boolean linebreak = false;
		boolean lastCharWasQuoted = false;
		byte[] encodedWord = null;
		// we support one char of pushback,
		// to catch some simple malformed input
		int pushbackChar = 0;
		boolean hasPushback = false;
		while (true) {
			int c;
			if (hasPushback) {
				c = pushbackChar;
				hasPushback = false;
				//if (_log.shouldDebug()) _log.debug("Loop " + count + " Using pbchar(dec) " + c);
			} else {
				c = in.read();
				if (c < 0)
					break;
			}
			if( c == '=' ) {
				// An encoded-word should be 75 chars max including the delimiters, and must be on a single line
				// Store the full encoded word, including =? through ?=, in the buffer
				// Sadly, base64 can be a lot longer
				if (encodedWord == null)
					encodedWord = new byte[DECODE_MAX];
				int offset = 0;
				int f1 = 0, f2 = 0, f3 = 0, f4 = 0;
				encodedWord[offset++] = (byte) c;
				// Read until we have 4 '?', stored in encodedWord positions f1, f2, f3, f4,
				// plus one char after the 4th '?', which should be '='
				// We make a small attempt to pushback one char if it's not what we expect,
				// but for the most part it gets thrown out, as RFC 2047 allows
				for (; offset < DECODE_MAX; offset++) {
					c = in.read();
					if (c == '?') {
						if (f1 == 0)
							f1 = offset;
						else if (f2 == 0)
							f2 = offset;
						else if (f3 == 0)
							f3 = offset;
						else if (f4 == 0)
							f4 = offset;
					} else if (c == -1) {
						break;
					} else if (c == '\r' || c == '\n') {
						pushbackChar = c;
						hasPushback = true;
						break;
					} else if (offset == 1) {
						// no '?' after '='
						out.write('=');
						pushbackChar = c;
						hasPushback = true;
						break;
					}
					encodedWord[offset] = (byte) c;
					// store one past the 4th '?', presumably the '='
					if (f4 > 0 && offset >= f4 + 1) {
						if (c == '=') {
							offset++;
						} else {
							pushbackChar = c;
							hasPushback = true;
						}
						break;
					}
				}
				//if (f1 > 0)
				//	if (_log.shouldDebug()) _log.debug("End of encoded word, f1 " + f1 + " f2 " + f2 + " f3 " + f3 + " f4 " + f4 +
				//	" offset " + offset + " pushback? " + hasPushback + " pbchar(dec) " + c + '\n' +
				//	net.i2p.util.HexDump.dump(encodedWord, 0, offset));
				if (f4 == 0) {
					// at most 1 byte is pushed back
					if (f1 == 0) {
						// This is normal
						continue;
					} else if (f2 == 0) {
						// =? but no more ?
						// output what we buffered
						if (_log.shouldDebug()) _log.debug("2nd '?' not found");
						for (int i = 0; i < offset; i++) {
							out.write(encodedWord[i] & 0xff);
						}
						continue;
					} else if (f3 == 0) {
						// discard what we buffered
						if (_log.shouldDebug()) _log.debug("3rd '?' not found");
						continue;
					} else {
						// probably just too long, but could be end of line without the "?=".
						// synthesize a 4th '?' in an attempt to output
						// something, probably with some trailing garbage
						if (_log.shouldDebug()) _log.debug("4th '?' not found");
						f4 = offset + 1;
						// keep going and output what we have
					}
				}
				/*
				 * 4th question mark found, we are complete, so lets start
				 */
				String enc = (encodedWord[f2+1] == 'Q' || encodedWord[f2+1] == 'q') ?
				             "quoted-printable" :
				             ((encodedWord[f2+1] == 'B' || encodedWord[f2+1] == 'b') ?
				              "base64" :
				              null);
				// System.err.println( "4th ? found at " + f4 + ", encoding=" + enc );
				if (enc != null) {
					Encoding e = EncodingFactory.getEncoding( enc );
					if( e != null ) {
						try {
							// System.err.println( "decode(" + (f3 + 1) + "," + ( f4 - f3 - 1 ) + ")" );
							ReadBuffer tmpIn = new ReadBuffer(encodedWord, f3 + 1, f4 - f3 - 1);
							// decoded won't be longer than encoded
							MemoryBuffer tmp = new MemoryBuffer(f4 - f3 - 1);
							try {
								e.decode(tmpIn, tmp);
							} catch (EOFException eof) {
								// probably Base64 exceeded DECODE_MAX
								// Keep going and output what we got, if any
								if (_log.shouldDebug()) {
									_log.debug("q-w " + enc, eof);
									_log.debug(net.i2p.util.HexDump.dump(encodedWord));
								}
							}
							tmp.writeComplete(true);
							// get charset
							String charset = new String(encodedWord, f1 + 1, f2 - f1 - 1, "ISO-8859-1");
							String clc = charset.toLowerCase(Locale.US);
							if (clc.equals("utf-8") || clc.equals("utf8")) {
								// FIXME could be more efficient?
								InputStream tis = tmp.getInputStream();
								if (enc.equals("quoted-printable")) {
									int d;
									while ((d = tis.read()) != -1) {
										out.write(d == '_' ? 32 : d);
									}
								} else {
									DataHelper.copy(tis, out);
								}
							} else {
								// FIXME could be more efficient?
								// decode string
								String decoded = new String(tmp.getContent(), tmp.getOffset(), tmp.getLength(), charset);
								// encode string
								byte[] utf8 = DataHelper.getUTF8(decoded);
								if (enc.equals("quoted-printable")) {
									for (int j = 0; j < utf8.length; j++) {
										byte d = utf8[j];
										out.write(d == '_' ? 32 : d);
									}
								} else {
									out.write(utf8);
								}
							}
							lastCharWasQuoted = true;
							continue;
						} catch (IOException e1) {
							_log.error("q-w " + enc, e1);
							if (_log.shouldDebug()) {
								_log.debug(net.i2p.util.HexDump.dump(encodedWord));
							}
						} catch (RuntimeException e1) {
							_log.error("q-w " + enc, e1);
							if (_log.shouldDebug()) {
								_log.debug(net.i2p.util.HexDump.dump(encodedWord));
							}
						}
					} else {
						// can't happen
						if (_log.shouldDebug()) _log.debug("No decoder for " + enc);
					}  // e != null
				} else {
					if (_log.shouldDebug()) _log.debug("Invalid encoding '" + (char) encodedWord[f2+1] + '\'');
				}  // enc != null
			}  // c == '='
			else if( c == '\r' ) {
				if ((c = in.read()) == '\n' ) {
					/*
					 * delay linebreak in case of long line
					 */
					linebreak = true;
				} else {
					// pushback?
					if (_log.shouldDebug()) _log.debug("No \\n after \\r");
				}
			}
			// swallow whitespace here if lastCharWasQuoted
			if( linebreak ) {
				linebreak = false;
				for (int i = 0; ; i++) {
					c = in.read();
					if (c == -1)
						break;
					if (c != ' ' && c != '\t') {
						if (i == 0) {
							/*
							 * new line does not start with whitespace, so its not a new part of a
							 * long line
							 */
							out.write('\r');
							out.write('\n');
							if (c == '\r') {
								linebreak = true;
								in.read();    //  \n
								break;
							}
						} else {
							// treat all preceding whitespace as a single one
							if (!lastCharWasQuoted)
								out.write(' ');
						}
						pushbackChar = c;
						hasPushback = true;
						break;
					}
					/*
					 * skip whitespace
					 */
				}
				// if \r\n\r\n, we are done
				if (linebreak)
					break;
			} else {
				/*
				 * print out everything else literally
				 */
				out.write(c);
				lastCharWasQuoted = false;
			}
		}  // while true
		if( linebreak ) {
			out.write('\r');
			out.write('\n');
		}
		bout.writeComplete(true);
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
