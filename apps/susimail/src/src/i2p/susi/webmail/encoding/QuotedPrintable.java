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
public class QuotedPrintable implements Encoding {
	
	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#getName()
	 */
	public String getName() {
		return "quoted-printable";
	}
	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#encode(java.lang.String)
	 */
	public String encode( byte in[] ) throws EncodingException {
		try {
			return encode( new ByteArrayInputStream( in ) );
		}catch (IOException e) {
			throw new EncodingException( "IOException occured." );
		}
	}
	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#encode(java.lang.String)
	 */
	public String encode(String text) throws EncodingException {
		try {
			return encode( new StringBufferInputStream( text ) );
		}catch (IOException e) {
			throw new EncodingException( "IOException occured." );
		}
	}
	/**
	 * 
	 * @param in
	 * @return
	 */
	private static int BUFSIZE = 2;
	private String encode( InputStream in ) throws EncodingException, IOException {
		StringBuilder out = new StringBuilder();
		int read = 0, buffered = 0, tmp[] = new int[BUFSIZE];
		while( true ) {
			read = in.available();
			while( read > 0 && buffered < BUFSIZE ) {
				tmp[buffered++] = in.read();
				read--;
			}
			if( read == 0 && buffered == 0 )
				break;
			
			int c = tmp[0];
			buffered--;
			for( int j = 1; j < BUFSIZE; j++ )
				tmp[j-1] = tmp[j];
			
			if( c > 32 && c < 127 && c != 61 ) {
				out.append( (char)c );
			}
			else if( ( c == 32 || c == 9 ) ) {
				if( buffered > 0 && ( tmp[0] == 10 || tmp[0] == 13 ) ) {
					/*
					 * whitespace at end of line
					 */
					out.append( c == 32 ? "=20" : "=09" );
				}
				else {
					out.append( (char)c );
				}
			}
			else if( c == 13 && buffered > 0 && tmp[0] == 10 ) {
				out.append( "\r\n" );
				buffered--;
				for( int j = 1; j < BUFSIZE; j++ )
					tmp[j-1] = tmp[j];
			}
			else {
				if( c < 0 || c > 255 ) {
					throw new EncodingException( "Encoding supports only values of 0..255." );
				}
				out.append( HexTable.table[ c ] );
			}
		}
		return out.toString();
	}

	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#decode(java.lang.String)
	 */
	public ReadBuffer decode( byte in[] ) {
		return decode( in, 0, in.length );
	}
	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#decode(java.lang.String)
	 */
	public ReadBuffer decode(String text) {
		return text != null ? decode( text.getBytes() ) : null;
	}

	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#decode(byte[], int, int)
	 */
	public ReadBuffer decode(byte[] in, int offset, int length) {
		byte[] out = new byte[length];
		int written = 0;
		while( length-- > 0 ) {
			byte c = in[offset++];
			if( c == '=' ) {
				if( length >= 2 ) {
					byte a = in[offset];
					byte b = in[offset + 1];
					if( ( ( a >= '0' && a <= '9' ) || ( a >= 'A' && a <= 'F' ) ) &&
							( ( b >= '0' && b <= '9' ) || ( b >= 'A' && b <= 'F' ) ) ) {
						/*
						 * decode sequence
						 */
						// System.err.println( "decoding 0x" + (char)a + "" + (char)b );
						length -= 2 ;
						offset += 2;
						
						if( a >= '0' && a <= '9' )
							a -= '0';
						else if( a >= 'A' && a <= 'F' )
							a = (byte) (a - 'A' + 10);

						if( b >= '0' && b <= '9' )
							b -= '0';
						else if( b >= 'A' && b <= 'F' )
							b = (byte) (b - 'A' + 10);
						
						out[written++]=(byte) (a*16 + b);
						continue;
					}
					else if( a == '\r' && b == '\n' ) {
						/*
						 * softbreak, simply ignore it
						 */
						length -= 2;
						offset += 2;
						continue;
					}
					/*
					 * no correct encoded sequence found, ignore it and print it literally
					 */
				}
			}
			/*
			 * print out everything else literally
			 */
			out[written++] = c;
		}
		
		ReadBuffer readBuffer = new ReadBuffer();
		readBuffer.content = out;
		readBuffer.offset = 0;
		readBuffer.length = written;

		return readBuffer;
	}
	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#decode(i2p.susi.webmail.util.ReadBuffer)
	 */
	public ReadBuffer decode(ReadBuffer in) {
		return decode( in.content, in.offset, in.length );
	}
}
