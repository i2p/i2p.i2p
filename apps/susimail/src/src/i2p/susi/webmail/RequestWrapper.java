/*
 * Created on Dec 8, 2004
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
package i2p.susi.webmail;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Locale;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.mortbay.servlet.MultiPartRequest;

/**
 * @author user
 */
public class RequestWrapper {

	private HttpServletRequest httpRequest = null;
	private MultiPartRequest multiPartRequest = null;
	private Hashtable cache;
	private Hashtable cachedParameterNames;
	/**
	 * do not call
	 */
	private RequestWrapper()
	{
	}
	/**
	 * @param httpRequest
	 */
	public RequestWrapper(HttpServletRequest httpRequest) {
		cache = new Hashtable();
		this.httpRequest = httpRequest;
		String contentType = httpRequest.getContentType();
		if( contentType != null && contentType.toLowerCase(Locale.US).startsWith( "multipart/form-data" ) ) {
			try {
				multiPartRequest = new MultiPartRequest( httpRequest );
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	/**
	 * @param b
	 */
	public HttpSession getSession(boolean b) {
		return httpRequest.getSession( b );
	}

	/**
	 * @param name Specific parameter key
	 * @return parameter value
	 */
	public String getParameter(String name ) {
		return getParameter( name, null );
	}

	public HttpSession getSession() {
		return httpRequest.getSession();
	}

	/**
	 * @return List of request parameter names
	 */
	public Enumeration getParameterNames() {
		if( multiPartRequest != null ) {
			if( cachedParameterNames == null ) {
				cachedParameterNames = new Hashtable();
				String[] partNames = multiPartRequest.getPartNames();
				for( int i = 0; i < partNames.length; i++ )
					cachedParameterNames.put( partNames[i], Integer.valueOf( i ) );
			}
			return cachedParameterNames.keys();
		}
		else
			return httpRequest.getParameterNames();
	}

	/**
	 * @return The total length of the content.
	 */
	public int getContentLength() {
		return httpRequest.getContentLength();
	}

	/**
	 * @return The content type of the request.
	 */
	public String getContentType() {
		return httpRequest.getContentType();
	}

	public String getContentType( String partName )
	{
		String result = null;
		if( multiPartRequest != null ) {
			Hashtable params = multiPartRequest.getParams( partName );
			for( Enumeration e = params.keys(); e.hasMoreElements(); ) {
				String key = (String)e.nextElement();
				if( key.toLowerCase(Locale.US).compareToIgnoreCase( "content-type") == 0 ) {
					String value = (String)params.get( key );
					int i = value.indexOf( ";" );
					if( i != -1 )
						result = value.substring( 0, i );
					else
						result = value;
				}
			}
		}
		return result;
	}

	public Object getAttribute(String string) {
		return httpRequest.getAttribute( string );
	}

	public String getParameter( String name, String defaultValue )
	{
		String result = defaultValue;
		if( multiPartRequest != null ) {
			String str = (String)cache.get( name );
			if( str != null ) {
				result = str;
			}
			else {
				String[] partNames = multiPartRequest.getPartNames();
				for( int i = 0; i < partNames.length; i++ )
					if( partNames[i].compareToIgnoreCase( name ) == 0 ) {
						str = multiPartRequest.getString( partNames[i] );
						if( str != null ) {
							result = str;
							cache.put( name, result );
							break;
						}
					}
			}
		}
		else {
			String str = httpRequest.getParameter( name );
			if( str != null )
				result = str;
		}
		return result;
	}

	public String getFilename(String partName )
	{
		String result = null;
		if( multiPartRequest != null ) {
			String str = multiPartRequest.getFilename( partName );
			if( str != null )
				result = str;
		}
		return result;
	}

	public InputStream getInputStream(String partName )
	{
		InputStream result = null;
		if( multiPartRequest != null ) {
			result = multiPartRequest.getInputStream( partName );
		}
		return result;
	}

}
