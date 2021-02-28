/******************************************************************
*
*	CyberXML for Java
*
*	Copyright (C) Satoshi Konno 2002
*
*	File: Parser.java
*
*	Revision;
*
*	11/26/2003
*		- first revision.
*	03/30/2005
*		- Change parse(String) to use StringBufferInputStream instead of URL.
*	11/11/2009
*		- Changed Parser::parser() to use ByteArrayInputStream instead of StringBufferInputStream because of bugs in Android v1.6.
*
******************************************************************/

package org.cybergarage.xml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import net.i2p.util.Addresses;
import net.i2p.router.transport.TransportUtil;

import org.cybergarage.http.HTTP;
import org.cybergarage.http.HTTPRequest;
import org.cybergarage.http.HTTPResponse;
import org.cybergarage.util.Debug;

public abstract class Parser 
{
	// I2P
	private static final String USER_AGENT = "Debian/buster/sid, UPnP/1.1, MiniUPnPc/2.1";
	// HttpURLConnection proxy
	// https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html#Proxies
        // socks and system proxies are checked in TransportManager
	private static final String PROP_HURLC_PROXY1 = "http.proxyHost";
	private static final boolean HURLC_PROXY_ENABLED = System.getProperty(PROP_HURLC_PROXY1) != null &&
                                                           System.getProperty(PROP_HURLC_PROXY1).length() > 0;

	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////

	public Parser()
	{
	}

	////////////////////////////////////////////////
	//	parse
	////////////////////////////////////////////////

	public abstract Node parse(InputStream inStream) throws ParserException;

	////////////////////////////////////////////////
	//	parse (URL)
	////////////////////////////////////////////////

	public Node parse(URL locationURL) throws ParserException
	{
		// I2P multiple sanity checks
		if (!"http".equals(locationURL.getProtocol()))
			throw new ParserException("Not HTTP");
		String host = locationURL.getHost();
		if (host == null ||
		    host.startsWith("127."))
			throw new ParserException("Bad host " + host);
		if (host.startsWith("[") && host.endsWith("]"))
			host = host.substring(1, host.length() - 1);
		byte[] ip = Addresses.getIP(host);
		if (ip == null ||
		    TransportUtil.isPubliclyRoutable(ip, true))
			throw new ParserException("Bad host " + host);

		int port = locationURL.getPort();
		// Thanks for Hao Hu 
		if (port == -1)
			port = 80;
		String uri = locationURL.getPath();
		// I2P note: Roku port 9080 now ignored in ControlPoint.addDevice()
		// I2P fix - Roku 
		if (uri.length() <= 0)
			uri = "/";
		
		if (!HURLC_PROXY_ENABLED) {
			HttpURLConnection urlCon = null;
			InputStream urlIn = null;
			try {
				urlCon = (HttpURLConnection)locationURL.openConnection();

				// I2P mods to prevent hangs (see HTTPRequest for more info)
				// this seems to work, getInputStream actually does the connect(),
				// (as shown by a thread dump)
				// so we can set these after openConnection()
				// Alternative would be foo = new HttpURLConnection(locationURL); foo.set timeouts; foo.connect()
				urlCon.setConnectTimeout(2*1000);
				urlCon.setReadTimeout(1000);
				urlCon.setRequestMethod("GET");
				// See net.properties in Java install as of Java 12, however,
				// this appears to be true well earlier than that.
				// By default, the following request headers are not allowed to be set by user code
				// in HttpRequests: "connection", "content-length", "expect", "host" and "upgrade".
				// See sun.net.www.protocol.http.HttpUrlConnection source.
				// In that code, Connection: close is allowed.
				// Javadoc there says it throws IAE but it really is just ignored silently.
				urlCon.setRequestProperty("User-Agent", USER_AGENT);
				// Force even if System property http.keepAlive=true
				urlCon.setRequestProperty("Connection", "close");
				// I2P just in case
				urlCon.setAllowUserInteraction(false);
				urlCon.setUseCaches(false);
				urlCon.setInstanceFollowRedirects(false);

				// I2P fix
				int code = urlCon.getResponseCode();
				if (code < 200 || code >= 300)
					throw new ParserException("Bad response code " + code);
				// I2P fix - Roku port 9080
				// not valid json either; returns "status=ok"
				if ("application/json".equals(urlCon.getContentType()))
					throw new ParserException("JSON response");
				// I2P just in case - typ. responses are under 1KB
				if (urlCon.getContentLength() > 32768)
					throw new ParserException("too big");

				urlIn = urlCon.getInputStream();
				Node rootElem = parse(urlIn);
				return rootElem;
			} catch (ParserException pe) {
				throw pe;
			} catch (Exception e) {
				throw new ParserException(e);
			} finally {
				if (urlIn != null) try { urlIn.close(); } catch (IOException ioe) {}
				if (urlCon != null) urlCon.disconnect();
			}
		}

		// Fallback method, only if HURLC_PROXY_ENABLED
		// This way of doing things does not follow redirects
		HTTPRequest httpReq = new HTTPRequest();
		httpReq.setMethod(HTTP.GET);
		httpReq.setURI(uri);
		HTTPResponse httpRes = httpReq.post(host, port);
		if (!httpRes.isSuccessful())
			throw new ParserException("HTTP comunication failed. " +
					"Unable to retrieve resource -> " + locationURL +
					"\nRequest:\n" + httpReq +
					"\nResponse:\n" + httpRes);
		ByteArrayInputStream strBuf = new ByteArrayInputStream(httpRes.getContent());
		try {
			return parse(strBuf);
		} catch (ParserException pe) {
			Debug.warning("Parse error at resource " + locationURL +
					"\nRequest:\n" + httpReq +
					"\nResponse:\n" + httpRes, pe);
			throw pe;
		}
	}

	////////////////////////////////////////////////
	//	parse (File)
	////////////////////////////////////////////////

	public Node parse(File descriptionFile) throws ParserException
	{
		try {
			InputStream fileIn = new FileInputStream(descriptionFile);
			Node root = parse(fileIn);
			fileIn.close();
			return root;
			
		} catch (Exception e) {
			throw new ParserException(e);
		}
	}

	////////////////////////////////////////////////
	//	parse (Memory)
	////////////////////////////////////////////////
	
	public Node parse(String descr) throws ParserException
	{
		try {
			InputStream decrIn = new ByteArrayInputStream(descr.getBytes());
			Node root = parse(decrIn);
			return root;
		} catch (Exception e) {
			throw new ParserException(e);
		}
	}

}


