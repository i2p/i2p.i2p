package net.i2p.httptunnel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;

import net.i2p.util.Log;

/**
 * A HTTP request (GET or POST). This will be passed to a hander for
 * handling it.
 */
public class Request {

    private static final Log _log = new Log(Request.class);

    // all strings are forced to be ISO-8859-1 encoding
    private String method;
    private String url;
    private String proto;
    private String params;
    private String postData;
    
    public Request(InputStream in) throws IOException {
	BufferedReader br = new BufferedReader
	    (new InputStreamReader(in, "ISO-8859-1"));
	String line = br.readLine();
	if (line == null) { // no data at all
	    method = null;
	    _log.error("Connection but no data");
	    return;
	}
	int pos = line.indexOf(" ");
	if (pos == -1) {
	    method = line;
	    url="";
	    _log.error("Malformed HTTP request: "+line);
	} else {
	    method = line.substring(0,pos);
	    url=line.substring(pos+1);
	}
	proto="";
	pos = url.indexOf(" ");
	if (pos != -1) {
	    proto=url.substring(pos); // leading space intended
	    url = url.substring(0,pos);
	}
	StringBuffer sb = new StringBuffer(512);
	while((line=br.readLine()) != null) {
	    if (line.length() == 0) break;
	    sb.append(line).append("\r\n");
	}
	params = sb.toString(); // no leading empty line!
	sb = new StringBuffer();
	// hack for POST requests, ripped from HttpClient
	// this won't work for large POSTDATA
	// FIXME: do this better, please.
	if (!method.equals("GET")) {
	    while (br.ready()) { // empty the buffer (POST requests)
		int i=br.read();
		if (i != -1) {
		    sb.append((char)i);
		    }
	    }
	    postData = sb.toString();
	} else {
	    postData="";
	}
    }

    public byte[] toByteArray() throws IOException {
	if (method == null) return null;
	return toISO8859_1String().getBytes("ISO-8859-1");

    }

    private String toISO8859_1String() throws IOException {
	if (method == null) return null;
	return method+" "+url+proto+"\r\n"+params+"\r\n"+postData;
    }

    public String getURL() {
	return url;
    }

    public void setURL(String newURL) {
	url=newURL;
    }

    public String getParam(String name) {
	try {
	    BufferedReader br= new BufferedReader(new StringReader(params));
	    String line;
	    while ((line = br.readLine()) != null) {
		if (line.startsWith(name)) {
		    return line.substring(name.length());
		}
	    }
	    return null;
	} catch (IOException ex) {
	    _log.error("Error getting parameter", ex);
	    return null;
	}
    }

    public void setParam(String name, String value) {
	try {
	    StringBuffer sb = new StringBuffer(params.length()+value.length());
	    BufferedReader br= new BufferedReader(new StringReader(params));
	    String line;
	    boolean replaced = false;
	    while((line=br.readLine()) != null) {
		if (line.startsWith(name)) {
		    replaced=true;
		    if (value == null) continue; // kill param
		    line = name+value;
		}
		sb.append(line).append("\r\n");
	    }
	    if (!replaced && value != null) {
		sb.append(name).append(value).append("\r\n");
	    }
	    params=sb.toString();
	} catch (IOException ex) {
	    _log.error("Error getting parameter", ex);
	}
    }
}
