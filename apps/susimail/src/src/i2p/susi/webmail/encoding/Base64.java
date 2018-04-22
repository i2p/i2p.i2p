/*
 * Created on 09.11.2004
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
 * $Revision: 1.6 $
 */
package i2p.susi.webmail.encoding;

import i2p.susi.util.Buffer;
import i2p.susi.util.MemoryBuffer;
import i2p.susi.util.StringBuilderWriter;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;

/**
 * @author susi
 */
public class Base64 extends Encoding {
	
	public String getName() {
		return "base64";
	}

	/**
	 * @return Base64-encoded String.
	 * @throws EncodingException 
	 */
	public String encode( byte in[] ) throws EncodingException
	{
		try {
			Writer strBuf = new StringBuilderWriter();
			encode(new ByteArrayInputStream(in), strBuf);
			return strBuf.toString();
		}catch (IOException e) {
			throw new EncodingException("encode error",  e);
		}
	}

	/**
	 * More efficient than super
	 * 
	 * @param in
	 * @see Base64#encode(String)
	 * @since public since 0.9.33 with new params
	 */
	@Override
	public void encode(InputStream in, Writer strBuf) throws IOException
	{
		int buf[] = new int[3];
		int out[] = new int[4];
		int l = 0;
		while( true ) {
			int read = in.available();
			if( read == 0 )
				break;
			int i = 0;
			buf[0] = buf[1] = buf[2] = 0;
			while( read > 0 && i < 3 ) {
				buf[i] = in.read();
				if( buf[i] < 0 || buf[i] > 255 )
					throw new EncodingException( "Encoding supports only values 0..255 (" + buf[i] + ")" );
				i++;
				read--;
			}
			out[0] = encodeByte( ( buf[0] >> 2 ) & 63 );
			out[1] = encodeByte( ( ( buf[0] & 3 ) << 4 ) | ( ( buf[1] >> 4 ) & 15 ) );
			out[2] = encodeByte( ( ( buf[1] & 15 ) << 2 ) | ( ( buf[2] >> 6 ) & 3 ) );
			out[3] = encodeByte( buf[2] & 63 );
			strBuf.append( (char)out[0] );
			strBuf.append( (char)out[1] );
			if( i > 1 ) {
				strBuf.append( (char)out[2] );
			}
			else
				strBuf.append( "=" );
			if( i > 2 )
				strBuf.append( (char)out[3] );
			else
				strBuf.append( "=" );
			i += 3;
			l += 4;
			if( l >= 76 ) {
				strBuf.append( "\r\n" );
				l -= 76;
			}
		}
	}

	/**
	 * @param b
	 * @return Encoded single byte.
	 */
	private static int encodeByte(int b) {
		/*
	       0 A            17 R            34 i            51 z
	       1 B            18 S            35 j            52 0
	       2 C            19 T            36 k            53 1
	       3 D            20 U            37 l            54 2
	       4 E            21 V            38 m            55 3
	       5 F            22 W            39 n            56 4
	       6 G            23 X            40 o            57 5
	       7 H            24 Y            41 p            58 6
	       8 I            25 Z            42 q            59 7
	       9 J            26 a            43 r            60 8
	      10 K            27 b            44 s            61 9
	      11 L            28 c            45 t            62 +
	      12 M            29 d            46 u            63 /
	      13 N            30 e            47 v
	      14 O            31 f            48 w         (pad) =
	      15 P            32 g            49 x
	      16 Q            33 h            50 y
	      */
		if( b < 26 )
			b += 'A';
		else if( b < 52 )
			b += 'a' - 26;
		else if( b < 62 )
			b += '0' - 52;
		else if( b == 62 )
			b = '+';
		else
			b = '/';
		return b;
	}

	private static byte decodeByte( int c ) throws IOException {
		if (c < 0)
			throw new EOFException();
		byte b = (byte) (c & 0xff);
		if( b >= 'A' && b <= 'Z' )
			b -= 'A';
		else if( b >= 'a' && b <= 'z' )
			b = (byte) (b - 'a' + 26);
		else if( b >= '0' && b <= '9' )
			b = (byte) (b - '0' + 52);
		else if( b == '+' )
			b = 62;
		else if( b == '/' )
			b = 63;
		else if( b == '=' )
			b = 0;
		else
			throw new DecodingException("Decoding base64 failed, invalid data: " + c);
		// System.err.println( "decoded " + (char)a + " to " + b );
		return b;
	}

	private static int readIn(InputStream in) throws IOException {
		int c;
		do {
			c = in.read();
		} while (c == '\r' || c == '\n' || c == ' ' || c == '\t');
		return c;
	}

	/**
	 * @since 0.9.34
	 */
	public void decode(InputStream in, Buffer bout) throws IOException {
		OutputStream out = bout.getOutputStream();
		while (true) {
			int c = readIn(in);
			if (c < 0)
				break;

			// System.out.println( "decode: " + (char)in[offset] + (char)in[offset+1]+ (char)in[offset+2]+ (char)in[offset+3] );
			byte b1 = decodeByte(c);
			c = readIn(in);
			byte b2 = decodeByte(c);
			out.write(((b1 << 2) | ((b2 >> 4) & 3)) & 0xff);

			c = readIn(in);
			if (c < 0)
				break;
			byte b3 = 0;
			if (c != '=') {
				b3 = decodeByte(c);
				out.write((((b2 & 15) << 4) | ((b3 >> 2) & 15)) & 0xff);
			}
			c = readIn(in);
			if (c < 0)
				break;
			if (c == '=') break; // done
			byte b4 = decodeByte(c);
			out.write((((b3 & 3) << 6) | (b4 & 63)) & 0xff);
		}
	}
}
