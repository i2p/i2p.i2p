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

import i2p.susi.util.ReadBuffer;

/**
 * Interface to encode/decode content transfer encodings like quoted-printable, base64 etc.
 * 
 * @author susi
 */
public interface Encoding {
	public String getName();
	/**
	 * 
	 * @param in
	 * @return Encoded string.
	 * @throws EncodingException 
	 */
	public String encode( byte in[] ) throws EncodingException;
	/**
	 * 
	 * @param str
	 * @see Encoding#encode(byte[])
	 * @throws EncodingException 
	 */
	public String encode( String str ) throws EncodingException;
	/**
	 * 
	 * @param in
	 * @see Encoding#decode(byte[], int, int)
	 * @throws DecodingException 
	 */
	public ReadBuffer decode( byte in[] ) throws DecodingException;
	/**
	 * 
	 * @param in
	 * @param offset 
	 * @param length 
	 * @return Output buffer containing decoded String.
	 * @throws DecodingException 
	 */
	public ReadBuffer decode( byte in[], int offset, int length ) throws DecodingException;
	/**
	 * 
	 * @param str
	 * @see Encoding#decode(byte[], int, int)
	 * @throws DecodingException 
	 */
	public ReadBuffer decode( String str ) throws DecodingException;
	/**
	 * 
	 * @param in
	 * @see Encoding#decode(byte[], int, int)
	 * @throws DecodingException 
	 */
	public ReadBuffer decode( ReadBuffer in ) throws DecodingException;
}
