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
package net.i2p.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.mortbay.servlet.MultiPartRequest;

/**
 *  Required major changes for Jetty 6
 *  to support change from MultiPartRequest to MultiPartFilter.
 *  See http://docs.codehaus.org/display/JETTY/File+Upload+in+jetty6
 *  Unfortunately, Content-type not available until Jetty 8
 *  See https://bugs.eclipse.org/bugs/show_bug.cgi?id=349110
 *
 *  So we could either extend and fix MultiPartFilter, and rewrite everything here,
 *  or copy MultiPartRequest into our war and fix it so it compiles with Jetty 6.
 *  We do the latter.
 *
 *  The filter would have been added in web.xml,
 *  see that file, where it's commented out.
 *  Filter isn't supported until Tomcat 7 (Servlet 3.0)
 *
 *  @author user
 *  @since 0.9.19 moved from susimail so it may be used by routerconsole too
 */
public class RequestWrapper {

	private final HttpServletRequest httpRequest;
	private final MultiPartRequest multiPartRequest;
	private final Hashtable<String, String> cache;
	private Hashtable<String, Integer> cachedParameterNames;

	/**
	 * @param httpRequest
	 */
	public RequestWrapper(HttpServletRequest httpRequest) {
		cache = new Hashtable<String, String>();
		this.httpRequest = httpRequest;
		String contentType = httpRequest.getContentType();
		MultiPartRequest mpr = null;
		if( contentType != null && contentType.toLowerCase(Locale.US).startsWith( "multipart/form-data" ) ) {
			try {
				mpr = new MultiPartRequest( httpRequest );
			} catch (OutOfMemoryError oome) {
				// TODO Throw ioe from constructor?
				oome.printStackTrace();
			} catch (IOException e) {
				// TODO Throw ioe from constructor?
				e.printStackTrace();
			}
		}
		multiPartRequest = mpr;
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
	@SuppressWarnings("unchecked") // TODO-Java6: Remove, type is correct
	public Enumeration<String> getParameterNames() {
		if( multiPartRequest != null ) {
			if( cachedParameterNames == null ) {
				cachedParameterNames = new Hashtable<String, Integer>();
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
			Hashtable<String, String> params = multiPartRequest.getParams( partName );
			for( Map.Entry<String, String> e : params.entrySet() ) {
				String key = e.getKey();
				if( key.toLowerCase(Locale.US).compareToIgnoreCase( "content-type") == 0 ) {
					String value = e.getValue();
					int i = value.indexOf( ';' );
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
			String str = cache.get(name);
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
