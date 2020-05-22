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

import org.cybergarage.http.HTTP;
import org.cybergarage.http.HTTPRequest;
import org.cybergarage.http.HTTPResponse;
import org.cybergarage.util.Debug;

public abstract class Parser 
{
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
		String host = locationURL.getHost();
		int port = locationURL.getPort();
		// Thanks for Hao Hu 
		if (port == -1)
			port = 80;
		String uri = locationURL.getPath();
		// I2P note: Roku port 9080 now ignored in ControlPoint.addDevice()
		// I2P fix - Roku 
		if (uri.length() <= 0)
			uri = "/";
		
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
			urlCon.setRequestProperty(HTTP.CONTENT_LENGTH,"0");
			if (host != null)
				urlCon.setRequestProperty(HTTP.HOST, host);

			// I2P fix
			int code = urlCon.getResponseCode();
			if (code < 200 || code >= 300)
				throw new ParserException("Bad response code " + code);
			// I2P fix - Roku port 9080
			// not valid json either; returns "status=ok"
			if ("application/json".equals(urlCon.getContentType()))
				throw new ParserException("JSON response");

			urlIn = urlCon.getInputStream();
			Node rootElem = parse(urlIn);

			return rootElem;
			
		} catch (ParserException pe) {
			throw pe;
		} catch (Exception e) {
			// Why try twice???
			//throw new ParserException(e);
			Debug.warning("Failed fetch but retrying with HTTPRequest, URL: " + locationURL, e);
		} finally {
			if (urlIn != null) try { urlIn.close(); } catch (IOException ioe) {}
			if (urlCon != null) urlCon.disconnect();
		}

		HTTPRequest httpReq = new HTTPRequest();
		httpReq.setMethod(HTTP.GET);
		httpReq.setURI(uri);
		HTTPResponse httpRes = httpReq.post(host, port);
		if (!httpRes.isSuccessful())
			throw new ParserException("HTTP comunication failed. " +
					"Unable to retrieve resource -> " + locationURL +
					"\nRequest:\n" + httpReq +
					"\nResponse:\n" + httpRes);
		String content = new String(httpRes.getContent());
		ByteArrayInputStream strBuf = new ByteArrayInputStream(content.getBytes());
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


