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

import i2p.susi.util.Buffer;
import i2p.susi.util.HexTable;
import i2p.susi.util.MemoryBuffer;
import i2p.susi.util.StringBuilderWriter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;

/**
 * ref: https://en.wikipedia.org/wiki/Quoted-printable
 * @author susi
 */
public class QuotedPrintable extends Encoding {
	
	public String getName() {
		return "quoted-printable";
	}

	private static int BUFSIZE = 2;

	public String encode( byte in[] ) throws EncodingException {
		try {
			Writer strBuf = new StringBuilderWriter();
			encode(new ByteArrayInputStream(in), strBuf);
			return strBuf.toString();
		} catch (IOException e) {
			throw new EncodingException("encode error",  e);
		}
	}

	/**
	 * More efficient than super
	 * 
	 * @param in
	 * @see Base64#encode(String)
	 * @since since 0.9.33
	 */
	@Override
	public void encode(InputStream in, Writer out) throws IOException
	{
		int buffered = 0, tmp[] = new int[BUFSIZE];
		int index = 0;
		int l = 0;
		while( true ) {
			int read = 0;
			int r;
			while(buffered < BUFSIZE && (r = in.read()) >= 0) {
				tmp[buffered++] = r;
				read++;
			}
			if( read == 0 && buffered == 0 )
				break;
			
			int c = tmp[0];
			buffered--;
			for( int j = 1; j < BUFSIZE; j++ )
				tmp[j-1] = tmp[j];
			
			if ((c == '.' || c == '-') && l == 0) {
				// leading '.' seems to get eaten by SMTP,
				// even if more chars after it
				// just to be sure, do the same for '-'
				// because it starts a boundary
				String s = HexTable.table[c];
				l = s.length();
				out.append(s);
			} else if (c > 32 && c < 127 && c != 61) {
				out.append( (char)c );
				l++;
			}
			else if( ( c == 32 || c == 9 ) ) {
				if( buffered > 0 && ( tmp[0] == 10 || tmp[0] == 13 ) ) {
					/*
					 * whitespace at end of line
					 */
					if (l >= 73) {
						// soft line breaks
						out.append("=\r\n");
						l = 0;
					}
					out.append( c == 32 ? "=20" : "=09" );
					l += 3;
				}
				else {
					out.append( (char)c );
					l++;
				}
			}
			else if( c == 13 && buffered > 0 && tmp[0] == 10 ) {
				out.append( "\r\n" );
				l = 0;
				buffered--;
				for( int j = 1; j < BUFSIZE; j++ )
					tmp[j-1] = tmp[j];
			} else {
				String s = HexTable.table[ c < 0 ? 256 + c : c ];
				l += s.length();
				if (l > 75) {
					// soft line breaks
					out.append("=\r\n");
					l = s.length();
				}
				out.append(s);
			}
			if (l >= 75) {
				// soft line breaks
				out.append("=\r\n");
				l = 0;
			}
		}
	}

	/**
	 * @since 0.9.34
	 */
	public void decode(InputStream in, Buffer bout) throws IOException {
		OutputStream out = bout.getOutputStream();
		while (true) {
			int c = in.read();
			if (c < 0)
				break;
			if( c == '=' ) {
					int a = in.read();
					if (a < 0) {
						out.write(c);
						break;
					}
					int b = in.read();
					if (b < 0) {
						out.write(c);
						out.write(a);
						break;
					}
					if (((a >= '0' && a <= '9') || (a >= 'A' && a <= 'F') || (a >= 'a' && a <= 'f')) &&
					    ((b >= '0' && b <= '9') || (b >= 'A' && b <= 'F') || (b >= 'a' && b <= 'f'))) {
						/*
						 * decode sequence
						 */
						// System.err.println( "decoding 0x" + (char)a + "" + (char)b );
						if( a >= '0' && a <= '9' )
							a -= '0';
						else if( a >= 'A' && a <= 'F' )
							a = (byte) (a - 'A' + 10);
						else if(a >= 'a' && a <= 'f')
							a = (byte) (a - 'a' + 10);

						if( b >= '0' && b <= '9' )
							b -= '0';
						else if( b >= 'A' && b <= 'F' )
							b = (byte) (b - 'A' + 10);
						else if(b >= 'a' && b <= 'f')
							b = (byte) (b - 'a' + 10);
						
						out.write(a*16 + b);
						continue;
					}
					else if( a == '\r' && b == '\n' ) {
						/*
						 * softbreak, simply ignore it
						 */
						continue;
					} else {
						throw new DecodingException("Bad q-p data after '='");
					}
			}
			/*
			 * print out everything else literally
			 */
			out.write(c);
		}
		bout.writeComplete(true);
	}
}
