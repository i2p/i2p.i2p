/*
 * Created on Nov 16, 2004
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

import i2p.susi.util.ReadBuffer;

/**
 * @author susi
 */
public class EightBit implements Encoding {

	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#getName()
	 */
	public String getName() {
		return "8bit";
	}

	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#encode(byte[])
	 */
	public String encode(byte[] in) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#encode(java.lang.String)
	 */
	public String encode(String str) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#decode(byte[])
	 */
	public ReadBuffer decode(byte[] in) throws DecodingException {
		return decode( in, 0, in.length );
	}

	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#decode(byte[], int, int)
	 */
	public ReadBuffer decode(byte[] in, int offset, int length)
		throws DecodingException {
		ReadBuffer readBuffer = new ReadBuffer();
		readBuffer.content = in;
		readBuffer.offset = offset;
		readBuffer.length = length;
		return readBuffer;
	}

	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#decode(java.lang.String)
	 */
	public ReadBuffer decode(String str) throws DecodingException {
		return decode( str.getBytes() );
	}

	/* (non-Javadoc)
	 * @see i2p.susi.webmail.encoding.Encoding#decode(i2p.susi.webmail.util.ReadBuffer)
	 */
	public ReadBuffer decode(ReadBuffer in) throws DecodingException {
		return in;
	}

}
