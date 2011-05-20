/******************************************************************
*
*	CyberHTTP for Java
*
*	Copyright (C) Satoshi Konno 2002-2004
*
*	File: HTTPRequest.java
*
*	Revision;
*
*	11/18/02
*		- first revision.
*	05/23/03
*		- Giordano Sassaroli <sassarol@cefriel.it>
*		- Add a relative URL check to setURI().
*	09/02/03
*		- Giordano Sassaroli <sassarol@cefriel.it>
*		- Problem : Devices whose description use absolute urls receive wrong http requests
*		- Error : the presence of a base url is not mandatory, the API code makes the assumption that control and event subscription urls are relative
*		- Description: The method setURI should be changed as follows
*	02/01/04
*		- Added URI parameter methods.
*	03/16/04
*		- Removed setVersion() because the method is added to the super class.
*		- Changed getVersion() to return the version when the first line string has the length.
*	05/19/04
*		- Changed post(HTTPResponse *) to close the socket stream from the server.
*	08/19/04
*		- Fixed getFirstLineString() and getHTTPVersion() no to return "HTTP/HTTP/version".
*	08/25/04
*		- Added isHeadRequest().
*	08/26/04
*		- Changed post(HTTPResponse) not to close the connection.
*		- Changed post(String, int) to add a connection header to close.
*	08/27/04
*		- Changed post(String, int) to support the persistent connection.
*	08/28/04
*		- Added isKeepAlive().
*	10/26/04
*		- Brent Hills <bhills@openshores.com>
*		- Added a fix to post() when the last position of Content-Range header is 0.
*		- Added a Content-Range header to the response in post().
*		- Changed the status code for the Content-Range request in post().
*		- Added to check the range of Content-Range request in post().
*	03/02/05
*		- Changed post() to suppot chunked stream.
*
******************************************************************/

package org.cybergarage.http;

import java.io.*;
import java.net.*;
import java.util.*;

import org.cybergarage.util.Debug;

public class HTTPRequest extends HTTPPacket
{
	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	public HTTPRequest()
	{
	}

	public HTTPRequest(InputStream in)
	{
		super(in);
	}

	public HTTPRequest(HTTPSocket httpSock)
	{
		this(httpSock.getInputStream());
		setSocket(httpSock);
	}

	////////////////////////////////////////////////
	//	Method
	////////////////////////////////////////////////

	private String method = null;

	public void setMethod(String value)
	{
		method = value;
	}
		
	public String getMethod()
	{
		if (method != null)
			return method;
		return getFirstLineToken(0);
	}

	public boolean isMethod(String method)
	{
		String headerMethod = getMethod();
		if (headerMethod == null)
			return false;
		return headerMethod.equalsIgnoreCase(method);
	}

	public boolean isGetRequest()
	{
		return isMethod(HTTP.GET);
	}

	public boolean isPostRequest()
	{
		return isMethod(HTTP.POST);
	}

	public boolean isHeadRequest()
	{
		return isMethod(HTTP.HEAD);
	}
	
	public boolean isSubscribeRequest()
	{
		return isMethod(HTTP.SUBSCRIBE);
	}

	public boolean isUnsubscribeRequest()
	{
		return isMethod(HTTP.UNSUBSCRIBE);
	}

	public boolean isNotifyRequest()
	{
		return isMethod(HTTP.NOTIFY);
	}
 
	////////////////////////////////////////////////
	//	URI
	////////////////////////////////////////////////

	private String uri = null;

	public void setURI(String value, boolean isCheckRelativeURL)
	{
		uri = value;
		if (isCheckRelativeURL == false)
			return;
		// Thanks for Giordano Sassaroli <sassarol@cefriel.it> (09/02/03)
		uri = HTTP.toRelativeURL(uri);
	}

	public void setURI(String value)
	{
		setURI(value, false);
	}

	public String getURI()
	{
		if (uri != null)
			return uri;
		return getFirstLineToken(1);
	}

	////////////////////////////////////////////////
	//	URI Parameter
	////////////////////////////////////////////////
	
	public ParameterList getParameterList()
	{
		ParameterList paramList = new ParameterList();
		String _uri = getURI();
		if (_uri == null)
			return paramList;
		int paramIdx = _uri.indexOf('?');
		if (paramIdx < 0)
			return paramList;
		while (0 < paramIdx) {
			int eqIdx = _uri.indexOf('=', (paramIdx+1));
			String name = _uri.substring(paramIdx+1, eqIdx);
			int nextParamIdx = _uri.indexOf('&', (eqIdx+1));
			String value = _uri.substring(eqIdx+1, (0 < nextParamIdx) ? nextParamIdx : _uri.length());
			Parameter param = new Parameter(name, value);
			paramList.add(param);
			paramIdx = nextParamIdx;
		}
		return paramList;
	}
	
	public String getParameterValue(String name)
	{
		ParameterList paramList = getParameterList();
		return paramList.getValue(name);
	}
	
	////////////////////////////////////////////////
	//	SOAPAction
	////////////////////////////////////////////////

	public boolean isSOAPAction()
	{
		return hasHeader(HTTP.SOAP_ACTION);
	}

	////////////////////////////////////////////////
	// Host / Port	
	////////////////////////////////////////////////
	
	private String requestHost = "";
	
	public void setRequestHost(String host)
	{
		requestHost = host;
	}

	public String getRequestHost()
	{
		return requestHost;
	}

	private int requestPort = -1;
	
	public void setRequestPort(int host)
	{
		requestPort = host;
	}

	public int getRequestPort()
	{
		return requestPort;
	}
	
	////////////////////////////////////////////////
	//	Socket
	////////////////////////////////////////////////

	private HTTPSocket httpSocket = null;

	public void setSocket(HTTPSocket value)
	{
		httpSocket = value;
	}
		
	public HTTPSocket getSocket()
	{
		return httpSocket;
	}

	/////////////////////////// /////////////////////
	//	local address/port
	////////////////////////////////////////////////

	public String getLocalAddress()
	{
		return getSocket().getLocalAddress();	
	}

	public int getLocalPort()
	{
		return getSocket().getLocalPort();	
	}

	////////////////////////////////////////////////
	//	parseRequest
	////////////////////////////////////////////////

	public boolean parseRequestLine(String lineStr)
	{
		StringTokenizer st = new StringTokenizer(lineStr, HTTP.REQEST_LINE_DELIM);
		if (st.hasMoreTokens() == false)
			return false;
		setMethod(st.nextToken());
		if (st.hasMoreTokens() == false)
			return false;
		setURI(st.nextToken());
		if (st.hasMoreTokens() == false)
			return false;
		setVersion(st.nextToken());
		return true;
     }

	////////////////////////////////////////////////
	//	First Line
	////////////////////////////////////////////////

	public String getHTTPVersion()
	{
		if (hasFirstLine() == true)
			return getFirstLineToken(2);
		return "HTTP/" + super.getVersion();
	}

	public String getFirstLineString()
	{
		return getMethod() + " " + getURI() + " " + getHTTPVersion() + HTTP.CRLF;
	}

	////////////////////////////////////////////////
	//	getHeader
	////////////////////////////////////////////////
	
	public String getHeader()
	{
		StringBuilder str = new StringBuilder();
		
		str.append(getFirstLineString());
		
		String headerString  = getHeaderString();		
		str.append(headerString);
		
		return str.toString();
	}
	
	////////////////////////////////////////////////
	//	isKeepAlive
	////////////////////////////////////////////////
	
	public boolean isKeepAlive()
	{
		if (isCloseConnection() == true)
			return false;
		if (isKeepAliveConnection() == true)
			return true;
		String httpVer = getHTTPVersion();
		boolean isHTTP10 = (0 < httpVer.indexOf("1.0")) ? true : false;
		if (isHTTP10 == true)
			return false;
		return true;
	}

	////////////////////////////////////////////////
	//	read
	////////////////////////////////////////////////
	
	public boolean read()
	{
		return super.read(getSocket());
	}
	
	////////////////////////////////////////////////
	//	POST (Response)
	////////////////////////////////////////////////

	public boolean post(HTTPResponse httpRes)
	{
		HTTPSocket httpSock = getSocket();
		long offset = 0;
		long length = httpRes.getContentLength();
		if (hasContentRange() == true) {
			long firstPos = getContentRangeFirstPosition();
			long lastPos = getContentRangeLastPosition();

			// Thanks for Brent Hills (10/26/04)
			if (lastPos <= 0) 
				lastPos = length - 1;
			if ((firstPos > length ) || (lastPos > length))
				return returnResponse(HTTPStatus.INVALID_RANGE);
			httpRes.setContentRange(firstPos, lastPos, length);
			httpRes.setStatusCode(HTTPStatus.PARTIAL_CONTENT);
			
			offset = firstPos;
			length = lastPos - firstPos + 1;
		}
		return httpSock.post(httpRes, offset, length, isHeadRequest());
		//httpSock.close();
	}

	////////////////////////////////////////////////
	//	POST (Request)
	////////////////////////////////////////////////
	
	private Socket postSocket = null;
	
	public HTTPResponse post(String host, int port, boolean isKeepAlive)
	{
		HTTPResponse httpRes = new HTTPResponse();

		setConnection((isKeepAlive == true) ? HTTP.KEEP_ALIVE : HTTP.CLOSE);
		
		boolean isHeaderRequest = isHeadRequest();
		
		OutputStream out = null;
		InputStream in = null;
		
 		try {
 			if (postSocket == null) {
				// Mod for I2P
				// We can't handle the default system soTimeout of 3 minutes or so
				// as when the device goes away, this hangs the display of peers.jsp
				// and who knows what else.
				// Set the timeout to be nice and short, the device should be local and fast.
				// Yeah, the UPnP standard is a minute or something, too bad.
				// If he can't get back to us in a few seconds, forget it.
				// And set the soTimeout to 2 second (for reads).
				//postSocket = new Socket(host, port);
				postSocket = new Socket();
				postSocket.setSoTimeout(2000);
				SocketAddress sa = new InetSocketAddress(host, port);
				postSocket.connect(sa, 3000);
			}

			out = postSocket.getOutputStream();
			PrintStream pout = new PrintStream(out);
			pout.print(getHeader());
			pout.print(HTTP.CRLF);
			
			boolean isChunkedRequest = isChunked();
			
			String content = getContentString();
			int contentLength = 0;
			if (content != null)
				contentLength = content.length();
			
			if (0 < contentLength) {
				if (isChunkedRequest == true) {
					String chunSizeBuf = Long.toString(contentLength);
					pout.print(chunSizeBuf);
					pout.print(HTTP.CRLF);
				}
				pout.print(content);
				if (isChunkedRequest == true)
					pout.print(HTTP.CRLF);
			}

			if (isChunkedRequest == true) {
				pout.print("0");
				pout.print(HTTP.CRLF);
			}
			
			pout.flush();

			in = postSocket.getInputStream();
			httpRes.set(in, isHeaderRequest);		
		}
		catch (Exception e) {
			httpRes.setStatusCode(HTTPStatus.INTERNAL_SERVER_ERROR);
			// I2P addition
			Debug.warning(e);
		} finally {
			if (isKeepAlive == false) {	
				try {
					in.close();
				} catch (Exception e) {}
				if (in != null)
				try {
					out.close();
				} catch (Exception e) {}
				if (out != null)
				try {
					postSocket.close();
				} catch (Exception e) {}
				postSocket = null;
			}
		}
		
		return httpRes;
	}

	public HTTPResponse post(String host, int port)
	{
		return post(host, port, false);
	}

	////////////////////////////////////////////////
	//	set
	////////////////////////////////////////////////

	public void set(HTTPRequest httpReq)
	{
		set((HTTPPacket)httpReq);
		setSocket(httpReq.getSocket());
	}

	////////////////////////////////////////////////
	//	OK/BAD_REQUEST
	////////////////////////////////////////////////

	public boolean returnResponse(int statusCode)
	{
		HTTPResponse httpRes = new HTTPResponse();
		httpRes.setStatusCode(statusCode);
		httpRes.setContentLength(0);
		return post(httpRes);
	}

	public boolean returnOK()
	{
		return returnResponse(HTTPStatus.OK);
	}

	public boolean returnBadRequest()
	{
		return returnResponse(HTTPStatus.BAD_REQUEST);
	}

	////////////////////////////////////////////////
	//	toString
	////////////////////////////////////////////////
	
    @Override
	public String toString()
	{
		StringBuilder str = new StringBuilder();

		str.append(getHeader());
		str.append(HTTP.CRLF);
		str.append(getContentString());
		
		return str.toString();
	}

	public void print()
	{
		Debug.message(toString());
	}
}
