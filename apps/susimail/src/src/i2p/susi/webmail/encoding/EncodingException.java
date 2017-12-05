/*
 * Created on Nov 17, 2004
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
 *  $Revision: 1.2 $
 */
package i2p.susi.webmail.encoding;

import java.io.IOException;

/**
 * Converted from Exception to IOException in 0.9.33
 * @author susi
 */
public class EncodingException extends IOException {
	/**
	 * Comment for <code>serialVersionUID</code>
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * @since public since 0.9.33, was package private
	 */
	public EncodingException( String msg )
	{
		super( msg );
	}

	/**
	 * @since 0.9.33
	 */
	public EncodingException(String msg, Exception cause)
	{
		super(msg, cause);
	}

}
