/*
 * Created on Nov 23, 2004
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
 *  $Revision: 1.3 $
 */
package i2p.susi.webmail.encoding;

import java.io.InputStream;

import i2p.susi.util.Buffer;

/**
 * @author user
 */
public class HTML extends Encoding {

	public String getName() {
		return "HTML";
	}

	public String encode(byte[] in) throws EncodingException {
		throw new EncodingException("unsupported");
	}

	@Override
	public String encode(String str) throws EncodingException
	{
		return  str.replace("&", "&amp;")  // must be first
			   .replace( "<", "&lt;" )
			   .replace( ">", "&gt;" )
			   .replaceAll( "\r{0,1}\n", "<br>\r\n" );
	}

	public void decode(InputStream in, Buffer out) throws DecodingException {
		throw new DecodingException("unsupported");
	}
}
