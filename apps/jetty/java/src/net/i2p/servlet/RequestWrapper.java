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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 *  Refactored in 0.9.33 to use Servlet 3.0 API and remove dependency
 *  on old Jetty 5 MultiPartRequest code. See ticket 2109.
 *
 *  Previous history:
 *
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
	private final boolean isMultiPartRequest;
	private final Hashtable<String, String> cache;
	private Hashtable<String, Integer> cachedParameterNames;
	private static final int MAX_STRING_SIZE = 64*1024;

	/**
	 * @param httpRequest
	 */
	public RequestWrapper(HttpServletRequest httpRequest) {
		cache = new Hashtable<String, String>();
		this.httpRequest = httpRequest;
		String contentType = httpRequest.getContentType();
		isMultiPartRequest = contentType != null && contentType.toLowerCase(Locale.US).startsWith("multipart/form-data");
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
	 * @throws IllegalStateException if the request is too large
	 */
	public Enumeration<String> getParameterNames() {
		if (isMultiPartRequest) {
			if( cachedParameterNames == null ) {
				cachedParameterNames = new Hashtable<String, Integer>();
				try {
					Integer DUMMY = Integer.valueOf(0);
					for (Part p : httpRequest.getParts()) {
						cachedParameterNames.put(p.getName(), DUMMY);
					}
				} catch (IOException ioe) {
					log(ioe);
				} catch (ServletException se) {
					log(se);
				} catch (IllegalStateException ise) {
					log(ise);
					throw ise;
				}
			}
			return cachedParameterNames.keys();
		} else {
			return httpRequest.getParameterNames();
		}
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

	/**
	 * @throws IllegalStateException if the request is too large
	 */
	public String getContentType( String partName )
	{
		String result = null;
		if (isMultiPartRequest) {
			try {
				Part p = httpRequest.getPart(partName);
				if (p != null)
					result = p.getContentType();
			} catch (IOException ioe) {
				log(ioe);
			} catch (ServletException se) {
				log(se);
			} catch (IllegalStateException ise) {
				log(ise);
				throw ise;
			}
		}
		return result;
	}

	public Object getAttribute(String string) {
		return httpRequest.getAttribute( string );
	}

	/**
	 * @throws IllegalStateException if the request is too large
	 */
	public String getParameter( String name, String defaultValue )
	{
		String result = defaultValue;
		if (isMultiPartRequest) {
			String str = cache.get(name);
			if( str != null ) {
				result = str;
			} else {
				InputStream in = null;
				try {
					Part p = httpRequest.getPart(name);
					if (p != null) {
						long len = p.getSize();
						if (len > MAX_STRING_SIZE)
							throw new IOException("String too big: " + len);
						in = p.getInputStream();
						byte[] data = new byte[(int) len];
						DataHelper.read(in, data);
						String enc = httpRequest.getCharacterEncoding();
						if (enc == null)
							enc = "UTF-8";
						result = new String(data, enc);
						cache.put( name, result );
					}
				} catch (IOException ioe) {
					log(ioe);
				} catch (ServletException se) {
					log(se);
				} catch (IllegalStateException ise) {
					log(ise);
					throw ise;
				} finally {
					if (in != null) try { in.close(); } catch (IOException ioe) {}
				}
			}
		} else {
			String str = httpRequest.getParameter( name );
			if( str != null )
				result = str;
		}
		return result;
	}

	/**
	 * @throws IllegalStateException if the request is too large
	 */
	public String getFilename(String partName )
	{
		String result = null;
		if (isMultiPartRequest) {
			try {
				Part p = httpRequest.getPart(partName);
				if (p != null)
					result = p.getSubmittedFileName();
			} catch (IOException ioe) {
				log(ioe);
			} catch (ServletException se) {
				log(se);
			} catch (IllegalStateException ise) {
				log(ise);
				throw ise;
			}
		}
		return result;
	}

	/**
	 * @throws IllegalStateException if the request is too large
	 */
	public InputStream getInputStream(String partName )
	{
		InputStream result = null;
		if (isMultiPartRequest) {
			try {
				Part p = httpRequest.getPart(partName);
				if (p != null)
					result = p.getInputStream();
			} catch (IOException ioe) {
				log(ioe);
			} catch (ServletException se) {
				log(se);
			} catch (IllegalStateException ise) {
				log(ise);
				throw ise;
			}
		}
		return result;
	}

	/** @since 0.9.33 */
	private static void log(Exception e) {
		Log log = I2PAppContext.getGlobalContext().logManager().getLog(RequestWrapper.class);
		log.error("Multipart form error", e);
	}
}
