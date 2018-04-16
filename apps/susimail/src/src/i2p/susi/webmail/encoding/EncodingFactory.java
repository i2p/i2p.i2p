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

import i2p.susi.util.Config;
import i2p.susi.util.Buffer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.HexDump;
import net.i2p.util.Log;

/**
 * Manager class to handle content transfer encodings.
 * @author susi
 */
public class EncodingFactory {
	
	private static final String CONFIG_ENCODING = "encodings";
	private static final String DEFAULT_ENCODINGS = "i2p.susi.webmail.encoding.HeaderLine;i2p.susi.webmail.encoding.QuotedPrintable;i2p.susi.webmail.encoding.Base64;i2p.susi.webmail.encoding.SevenBit;i2p.susi.webmail.encoding.EightBit;i2p.susi.webmail.encoding.HTML";
	
	private static final Map<String, Encoding> encodings;
	
	static {
		encodings = new HashMap<String, Encoding>();
		// Let's not give the user a chance to break things
		//String list = Config.getProperty( CONFIG_ENCODING );
		String list = DEFAULT_ENCODINGS;
		if( list != null ) {
			String[] classNames = list.split( ";" );
			for( int i = 0; i < classNames.length; i++ ) {
				try {
					Class<?> c = Class.forName( classNames[i] );
					Encoding e = (Encoding) (c.getDeclaredConstructor().newInstance());
					encodings.put( e.getName(), e );
					//if (_log.shouldDebug()) _log.debug("Registered " + e.getClass().getName() );
				}
				catch (Exception e) {
					Log log = I2PAppContext.getGlobalContext().logManager().getLog(EncodingFactory.class);
					log.error("Error loading class '" + classNames[i] + "'", e);
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
		return name != null && name.length() > 0 ? encodings.get( name ) : null;
	}
	/**
	 * Returns list of available encodings;
	 * 
	 * @return List of encodings
	 */
	public static Set<String> availableEncodings()
	{
		return encodings.keySet();
	}

/****
	public static void main(String[] args) {
		String text = "Subject: test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test test \r\n" +
		              "From: UTF8 <smoerebroed@mail.i2p>\r\n" +
		              "To: UTF8 <lalala@mail.i2p>\r\n";
		byte[] test = DataHelper.getUTF8(text);
		for (String s : availableEncodings()) {
			Encoding e = getEncoding(s);
			try {
				String enc = e.encode(test);
				if (enc == null) {
					System.out.println(s + "\tFAIL - null encode result");
					continue;
				}
				Buffer rb = e.decode(enc);
				if (rb == null) {
					System.out.println(s + "\tFAIL - null decode result");
					continue;
				}
				byte[] result = rb.content;
				if (DataHelper.eq(test, 0, result, rb.offset, test.length)) {
					System.out.println(s + "\tPASS");
					System.out.println("encoding:");
					System.out.println('"' + enc + '"');
				} else {
					System.out.println(s + "\tFAIL");
					System.out.println("expected:");
					System.out.println(HexDump.dump(test));
					System.out.println("got:");
					System.out.println(HexDump.dump(result, rb.offset, rb.length));
					System.out.println("encoding:");
					System.out.println('"' + enc + '"');
				}
			} catch (Exception ex) {
				System.out.println(s + "\tFAIL");
				ex.printStackTrace();
			}
		}
	}
****/
}
