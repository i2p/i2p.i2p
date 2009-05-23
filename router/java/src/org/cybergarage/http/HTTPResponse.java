/******************************************************************
*
*	CyberHTTP for Java
*
*	Copyright (C) Satoshi Konno 2002-2003
*
*	File: HTTPResponse.java
*
*	Revision;
*
*	11/18/02
*		- first revision.
*	10/22/03
*		- Changed to initialize a content length header.
*	10/22/04
*		- Added isSuccessful().
*	
******************************************************************/

package org.cybergarage.http;

import java.io.*;
import org.cybergarage.util.Debug;

public class HTTPResponse extends HTTPPacket
{
	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	public HTTPResponse()
	{
		setContentType(HTML.CONTENT_TYPE);
		setServer(HTTPServer.getName());
		setContent("");
	}

	public HTTPResponse(HTTPResponse httpRes)
	{
		set(httpRes);
	}

	public HTTPResponse(InputStream in)
	{
		super(in);
	}

	public HTTPResponse(HTTPSocket httpSock)
	{
		this(httpSock.getInputStream());
	}

	////////////////////////////////////////////////
	//	Status Line
	////////////////////////////////////////////////

	private int statusCode = 0;
	
	public void setStatusCode(int code)
	{
		statusCode = code;
	}

	public int getStatusCode()
	{
		if (statusCode != 0)
			return statusCode;
		HTTPStatus httpStatus = new HTTPStatus(getFirstLine());
		return httpStatus.getStatusCode();
	}

	public boolean isSuccessful()
	{
		return HTTPStatus.isSuccessful(getStatusCode());
	}
	
	public String getStatusLineString()
	{
		return "HTTP/" + getVersion() + " " + getStatusCode() + " " + HTTPStatus.code2String(statusCode) + HTTP.CRLF;
	}
	
	////////////////////////////////////////////////
	//	getHeader
	////////////////////////////////////////////////
	
	public String getHeader()
	{
		StringBuilder str = new StringBuilder();
	
		str.append(getStatusLineString());
		str.append(getHeaderString());
		
		return str.toString();
	}

	////////////////////////////////////////////////
	//	toString
	////////////////////////////////////////////////
	
	public String toString()
	{
		StringBuilder str = new StringBuilder();

		str.append(getStatusLineString());
		str.append(getHeaderString());
		str.append(HTTP.CRLF);
		str.append(getContentString());
		
		return str.toString();
	}

	public void print()
	{
		Debug.message(toString());
	}
}
