/*
 * Created on 15.11.2004
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

import java.io.IOException;
import java.io.InputStream;

import i2p.susi.util.Buffer;
import i2p.susi.util.ReadBuffer;

import net.i2p.data.DataHelper;

/**
 * Decode only.
 * @author susi
 */
public class SevenBit extends Encoding {

	public String getName() {
		return "7bit";
	}

	/**
	 * @throws EncodingException always
	 */
	public String encode(byte[] in) throws EncodingException {
		throw new EncodingException("unsupported");
	}

	/**
	 * @throws DecodingException on illegal characters
	 */
	@Override
	public Buffer decode(byte[] in, int offset, int length)
			throws DecodingException {
		int backupLength = length;
		int backupOffset = offset;
		while( length-- > 0 ) {
			byte b = in[offset++];
			if( b >= 32 && b < 127 )
				continue;
			if( b == '\t' )
				continue;
			if( b == '\r' || b == '\n' )
				continue;
			throw new DecodingException( "No 8 bit data allowed in 7 bit encoding (" + b + ')' );
		}
		return new ReadBuffer(in, backupOffset, backupLength);
	}

	/**
	 * We don't do any 8-bit checks like we do for decode(byte[])
	 * @return in, unchanged
	 */
	@Override
	public Buffer decode(Buffer in) {
		return in;
	}

	/**
	 * Copy in to out, unchanged
	 * We don't do any 8-bit checks like we do for decode(byte[])
	 * @since 0.9.34
	 */
	public void decode(InputStream in, Buffer out) throws IOException {
		DataHelper.copy(in, out.getOutputStream());
		// read complete, write complete
	}
}
