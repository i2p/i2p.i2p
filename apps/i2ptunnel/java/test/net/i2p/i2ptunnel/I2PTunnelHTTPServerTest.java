package net.i2p.i2ptunnel;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.Map;
import java.util.List;

import junit.framework.TestCase;

public class I2PTunnelHTTPServerTest extends TestCase {
	
	public InputStream fillInputStream(String headers) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(baos));
		bw.write(headers);
		bw.flush();
		byte[] bytes = baos.toByteArray();
		return new ByteArrayInputStream(bytes);
	}
	
	public void testSimpleHeader() throws IOException {
		String headerString = "GET /blah HTTP/1.1\r\n";
		headerString += "BLAH: something\r\n";
		headerString += "\r\n";
		InputStream in = fillInputStream(headerString);
		Map<String, List<String>> headers = I2PTunnelHTTPServer.readHeaders(in, new StringBuilder(128), new String[0], null);
		assertEquals(headers.size(), 1); //One header
	}
	
	public void testDuplicateHeader() throws IOException {
		String headerString = "GET /something HTTP/1.1\r\n";
		headerString += "someHeader: blabla bla bloooo\r\n";
		headerString += "someHeader: oh my, duplication!\r\n";
		headerString += "\r\n";
		InputStream in = fillInputStream(headerString);
		Map<String, List<String>> headers = I2PTunnelHTTPServer.readHeaders(in, new StringBuilder(128), new String[0], null);
		assertEquals(headers.size(), 1);
		assertEquals(headers.get("someHeader").size(), 2);
	}
	
	public void testDuplicateHeadersFormat() throws IOException {
		String headerString = "GET /something HTTP/1.1\r\n";
		headerString += "abc: def\r\n";
		headerString += "abc: blaaah\r\n";
		headerString += "manamana: toe toe toedoedoe\r\n";
		headerString += "\r\n";
		InputStream in = fillInputStream(headerString);
		StringBuilder builder = new StringBuilder(128);
		Map<String, List<String>> headers = I2PTunnelHTTPServer.readHeaders(in, builder, new String[0], null);
		String result = I2PTunnelHTTPServer.formatHeaders(headers, builder);
		int first = result.indexOf("abc");
		assertTrue(first >= 0);
		int second = result.indexOf("abc", first);
		assertTrue(second >= 0);
	}

}
