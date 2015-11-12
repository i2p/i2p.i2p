/*
 * Created on Nov 9, 2004
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
package i2p.susi.webmail.smtp;

import i2p.susi.debug.Debug;
import i2p.susi.webmail.Messages;
import i2p.susi.webmail.encoding.Encoding;
import i2p.susi.webmail.encoding.EncodingException;
import i2p.susi.webmail.encoding.EncodingFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import net.i2p.data.DataHelper;

/**
 * @author susi
 */
public class SMTPClient {
	
	private Socket socket;
	private final byte buffer[];
	public String error;
	private String lastResponse;
	private boolean supportsPipelining;
	
	private static final Encoding base64;
	
	static {
		base64 = EncodingFactory.getEncoding( "base64" );
	}

	public SMTPClient()
	{
		buffer = new byte[10240];
		error = "";
		lastResponse = "";
	}
	
	/**
	 *  Wait for response
	 *  @param cmd may be null
	 *  @return result code or 0 for failure
	 */
	private int sendCmd( String cmd ) {
		return sendCmd(cmd, true);
	}
	
	/**
	 *  @param cmd may be null
	 *  @param shouldWait if false, don't wait for response, and return 100
	 *  @return result code or 0 for failure
	 *  @since 0.9.13
	 */
	private int sendCmd(String cmd, boolean shouldWait)
	{
		if( socket == null )
			return 0;
		try {
			if (cmd != null)
				sendCmdNoWait(cmd);
			if (!shouldWait)
				return 100;
			socket.getOutputStream().flush();
			return getResult();
		} catch (IOException e) {
			error += "IOException occured.\n";
			return 0;
		}
	}
	
	/**
	 *  Does not flush, wait, or read
	 *
	 *  @param cmd non-null
	 *  @since 0.9.13
	 */
	private void sendCmdNoWait(String cmd) throws IOException
	{
		Debug.debug( Debug.DEBUG, "SMTP sendCmd(" + cmd +")" );
		
		if( socket == null )
			throw new IOException("no socket");
		OutputStream out = socket.getOutputStream();
		cmd += "\r\n";
		out.write(DataHelper.getASCII(cmd));
	}
	
	/**
	 *  Pipeline if supported
	 *
	 *  @param cmds non-null
	 *  @return number of successful commands
	 *  @since 0.9.13
	 */
	private int sendCmds(List<SendExpect> cmds)
	{
		int rv = 0;
		if (supportsPipelining) {
			Debug.debug(Debug.DEBUG, "SMTP pipelining " + cmds.size() + " commands");
			try {
				for (SendExpect cmd : cmds) {
					sendCmdNoWait(cmd.send);
				}
				socket.getOutputStream().flush();
			} catch (IOException ioe) {
				return 0;
			}
			for (SendExpect cmd : cmds) {
				int r = getResult();
				// stop only on EOF
				if (r == 0)
					break;
				if (r == cmd.expect)
					rv++;
			}
		} else {
			for (SendExpect cmd : cmds) {
				int r = sendCmd(cmd.send);
				// stop at first error
				if (r != cmd.expect)
					break;
				rv++;
			}
		}
		Debug.debug(Debug.DEBUG, "SMTP success in " + rv + " of " + cmds.size() + " commands");
		return rv;
	}

	/**
	 *  @return result code or 0 for failure
	 *  @since 0.9.13
	 */
	private int getResult() {
		return getFullResult().result;
	}

	/**
	 *  @return result code and string, all lines combined with \r separators,
         *          first 3 bytes are the ASCII return code or "000" for failure
	 *          Result and Result.recv non null
	 *  @since 0.9.13
	 */
	private Result getFullResult() {
		int result = 0;
		StringBuilder fullResponse = new StringBuilder(512);
		try {
			InputStream in = socket.getInputStream();
			StringBuilder buf = new StringBuilder(128);
			while (DataHelper.readLine(in, buf)) {
				Debug.debug(Debug.DEBUG, "SMTP rcv \"" + buf.toString().trim() + '"');
				int len = buf.length();
				if (len < 4) {
					result = 0;
					break; // huh? no nnn\r?
				}
				if( result == 0 ) {
					try {
						String r = buf.substring(0, 3);
						result = Integer.parseInt(r);
					} catch ( NumberFormatException nfe ) {
						break;
					}
				}
				fullResponse.append(buf.substring(4));
				if (buf.charAt(3) == ' ')
					break;
				buf.setLength(0);
			}
		} catch (IOException e) {
			error += "IOException occured.\n";
			result = 0;
		}
		lastResponse = fullResponse.toString();
		return new Result(result, lastResponse);
	}

	/**
	 *  @return success
	 */
	public boolean sendMail( String host, int port, String user, String pass, String sender, Object[] recipients, String body )
	{
		boolean mailSent = false;
		boolean ok = true;
		
		try {
			socket = new Socket( host, port );
		} catch (IOException e) {
			error += _t("Cannot connect") + ": " + e.getMessage() + '\n';
			ok = false;
		}
		try {
			// SMTP ref: RFC 821
			// Pipelining ref: RFC 2920
			// AUTH ref: RFC 4954
			if (ok) {
				socket.setSoTimeout(120*1000);
				int result = sendCmd(null);
				if (result != 220) {
					error += _t("Server refused connection") + " (" + result +  ")\n";
					ok = false;
				}
			}
			if (ok) {
				sendCmdNoWait( "EHLO localhost" );
				socket.getOutputStream().flush();
				socket.setSoTimeout(60*1000);
				Result r = getFullResult();
				if (r.result == 250) {
					supportsPipelining = r.recv.contains("PIPELINING");
				} else {
					error += _t("Server refused connection") + " (" + r.result +  ")\n";
					ok = false;
				}
			}
			if (ok) {
				// RFC 4954 says AUTH must be the last but let's assume
				// that includes the user/pass on following lines
				List<SendExpect> cmds = new ArrayList<SendExpect>();
				cmds.add(new SendExpect("AUTH LOGIN", 334));
				cmds.add(new SendExpect(base64.encode(user), 334));
				cmds.add(new SendExpect(base64.encode(pass), 235));
				if (sendCmds(cmds) != 3) {
					error += _t("Login failed") + '\n';
					ok = false;
				}
			}
			if (ok) {
				List<SendExpect> cmds = new ArrayList<SendExpect>();
				cmds.add(new SendExpect("MAIL FROM: " + sender, 250));
				for( int i = 0; i < recipients.length; i++ ) {
					cmds.add(new SendExpect("RCPT TO: " + recipients[i], 250));
				}
				cmds.add(new SendExpect("DATA", 354));
				if (sendCmds(cmds) != cmds.size()) {
					// TODO which recipient?
					error += _t("Mail rejected") + '\n';
					ok = false;
				}
			}
			if (ok) {
				if( body.indexOf( "\r\n.\r\n" ) != -1 )
					body = body.replace( "\r\n.\r\n", "\r\n..\r\n" );
				socket.getOutputStream().write(DataHelper.getUTF8(body));
				socket.getOutputStream().write(DataHelper.getASCII("\r\n.\r\n"));
				socket.setSoTimeout(0);
				int result = sendCmd(null);
				if (result == 250)
					mailSent = true;
				else
					error += _t("Error sending mail") + " (" + result +  ")\n";
			}
		} catch (IOException e) {
			error += _t("Error sending mail") + ": " + e.getMessage() + '\n';

		} catch (EncodingException e) {
			error += e.getMessage();
		}
		if( !mailSent && lastResponse.length() > 0 ) {
			String[] lines = DataHelper.split(lastResponse, "\r");
			for( int i = 0; i < lines.length; i++ )
				error += lines[i] + '\n';			
		}
		sendCmd("QUIT", false);
		if( socket != null ) {
			try {
				socket.close();
			} catch (IOException e1) {}
		}
		return mailSent;
	}

	/**
	 *  A command to send and a result code to expect
	 *  @since 0.9.13
	 */
	private static class SendExpect {
		public final String send;
		public final int expect;

		public SendExpect(String s, int e) {
			send = s;
			expect = e;
		}
	}

	/**
	 *  A result string and code
	 *  @since 0.9.13
	 */
	private static class Result {
		public final int result;
		public final String recv;

		public Result(int r, String t) {
			result = r;
			recv = t;
		}
	}

	/** translate */
	private static String _t(String s) {
		return Messages.getString(s);
	}
}
