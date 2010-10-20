/*
 * Created on Nov 15, 2004
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
package i2p.susi.util;

import i2p.susi.debug.Debug;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import net.i2p.I2PAppContext;

/**
 * @author susi
 */
public class Config {
	
	private static Properties properties = null, config = null;
	private static String configPrefix = null;
	/**
	 * 
	 * @param name
	 */
	public static String getProperty( String name )
	{
		if( configPrefix != null )
			name = configPrefix + name;
		
		String result = null;
		
		if( properties == null ) {
			reloadConfiguration();
		}
		
		result = System.getProperty( name );
		
		if( result != null )
			return result;
		
		result = config.getProperty( name );

		if( result != null )
			return result;
		
		result = properties.getProperty( name );

		return result;
	}
	/**
	 * 
	 *
	 */
	public static void reloadConfiguration()
	{
		properties = new Properties();
		config = new Properties();
		try {
			properties.load( Config.class.getResourceAsStream( "/susimail.properties" ) );
		} catch (Exception e) {
			Debug.debug( Debug.DEBUG, "Could not open WEB-INF/classes/susimail.properties (possibly in jar), reason: " + e.getMessage() );
		}
                FileInputStream fis = null;
		try {
			File cfg = new File(I2PAppContext.getGlobalContext().getConfigDir(), "susimail.config");
			fis = new FileInputStream(cfg);
			config.load( fis );
		} catch (Exception e) {
			Debug.debug( Debug.DEBUG, "Could not open susimail.config, reason: " + e.getMessage() );
		} finally {
			if (fis != null)
				try { fis.close(); } catch (IOException ioe) {}
		}
	}
	/**
	 * 
	 * @param name
	 * @param defaultValue
	 */
	public static String getProperty( String name, String defaultValue )
	{
		String result = getProperty( name );
		return result != null ? result : defaultValue;
	}
	/**
	 * 
	 * @param name
	 * @param defaultValue
	 */
	public static int getProperty( String name, int defaultValue )
	{
		int result = defaultValue;
		
		String str = getProperty( name );
		
		if( str != null ) {
			try {
				result = Integer.parseInt( str );
			}
			catch( NumberFormatException nfe ) {
				result = defaultValue;
			}
		}
		return result;
	}
	/**
	 * 
	 * @param prefix
	 */
	public static void setPrefix( String prefix )
	{
		configPrefix = prefix.endsWith( "." ) ? prefix : prefix + ".";
	}
}
