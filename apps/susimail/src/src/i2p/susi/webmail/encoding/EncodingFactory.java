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
 * $Revision: 1.4 $
 */
package i2p.susi.webmail.encoding;

import i2p.susi.debug.Debug;
import i2p.susi.util.Config;

import java.util.Hashtable;
import java.util.Set;

/**
 * Manager class to handle content transfer encodings.
 * @author susi
 */
public class EncodingFactory {
	
	public static final String CONFIG_ENCODING = "encodings";
	
	private static Hashtable encodings = null;
	
	static {
		encodings = new Hashtable();
		String list = Config.getProperty( CONFIG_ENCODING );
		if( list != null ) {
			String[] classNames = list.split( ";" );
			for( int i = 0; i < classNames.length; i++ ) {
				try {
					Class c = Class.forName( classNames[i] );
					Encoding e = (Encoding)c.newInstance();
					encodings.put( e.getName(), e );
					Debug.debug( Debug.DEBUG, "Registered " + e.getClass().getName() );
				}
				catch (Exception e) {
					Debug.debug( Debug.ERROR, "Error loading class '" + classNames[i] + "', reason: " + e.getClass().getName() );
				}
			}
		}
	}
	/**
	 * Retrieve instance of an encoder for a supported encoding (or null).
	 * 
	 * @param name name of encoding (e.g. quoted-printable)
	 * 
	 * @return Encoder instance
	 */
	public static Encoding getEncoding( String name )
	{
		return name != null && name.length() > 0 ? (Encoding)encodings.get( name ) : null;
	}
	/**
	 * Returns list of available encodings;
	 * 
	 * @return List of encodings
	 */
	public static Set availableEncodings()
	{
		return encodings.keySet();
	}
}
