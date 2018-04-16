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

import i2p.susi.webmail.Attachment;
import i2p.susi.webmail.Messages;
import i2p.susi.webmail.encoding.Encoding;
import i2p.susi.webmail.encoding.EncodingException;
import i2p.susi.webmail.encoding.EncodingFactory;
import i2p.susi.util.FilenameUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.InternalSocket;
import net.i2p.util.Log;

/**
 * @author susi
 */
public class SMTPClient {
	
	/**
	 *  31.84 MB
	 *  smtp.postman.i2p as of 2017-12.
	 *  @since 0.9.33
	 */
	public static final long DEFAULT_MAX_SIZE = 33388608;

	/**
	 *  About 23.25 MB.
	 *  Base64 encodes 57 chars to 76 + \r\n on a line
	 *  @since 0.9.33
	 */
	public static final long BINARY_MAX_SIZE = (long) ((DEFAULT_MAX_SIZE * 57.0d / 78) - 32*1024);

	private final Log _log;
	private Socket socket;
	public String error;
	private String lastResponse;
	private boolean supportsPipelining, eightBitMime;
	private long maxSize = DEFAULT_MAX_SIZE;
	
	private static final Encoding base64;
	
	static {
		base64 = EncodingFactory.getEncoding( "base64" );
	}

	public SMTPClient()
	{
		error = "";
		lastResponse = "";
		_log = I2PAppContext.getGlobalContext().logManager().getLog(SMTPClient.class);
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
			error += e + "\n";
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
		if (_log.shouldDebug()) _log.debug("SMTP sendCmd(" + cmd +")" );
		
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
			if (_log.shouldDebug()) _log.debug("SMTP pipelining " + cmds.size() + " commands");
			try {
				for (SendExpect cmd : cmds) {
					sendCmdNoWait(cmd.send);
				}
				socket.getOutputStream().flush();
			} catch (IOException ioe) {
				error += ioe + "\n";
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
		if (_log.shouldDebug()) _log.debug("SMTP success in " + rv + " of " + cmds.size() + " commands");
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
				if (_log.shouldDebug()) _log.debug("SMTP rcv \"" + buf.toString().trim() + '"');
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
			error += e + "\n";
			result = 0;
		}
		lastResponse = fullResponse.toString();
		return new Result(result, lastResponse);
	}

	/**
	 *  @param body headers and body, without the attachments
	 *  @param attachments may be null
	 *  @param boundary non-null if attachments is non-null
	 *  @return success
	 */
	public boolean sendMail(String host, int port, String user, String pass, String sender,
	                        String[] recipients, StringBuilder body,
	                        List<Attachment> attachments, String boundary)
	{
		boolean mailSent = false;
		boolean ok = true;
		Writer out = null;
		
		try {
			socket = InternalSocket.getSocket(host, port);
		} catch (IOException e) {
			error += _t("Cannot connect") + " (" + host + ':' + port + ") : " + e.getMessage() + '\n';
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
					error += _t("Error sending mail") + '\n';
					if (result != 0)
						error += _t("Server refused connection") + " (" + result + ")\n";
					else
						error += _t("Cannot connect") + " (" + host + ':' + port + ")\n";
					ok = false;
				}
			}
			if (ok) {
				sendCmdNoWait( "EHLO localhost" );
				socket.getOutputStream().flush();
				socket.setSoTimeout(60*1000);
				Result r = getFullResult();
				if (r.result == 250) {
					String[] caps = DataHelper.split(r.recv, "\r");
					for (String c : caps) {
						if (c.equals("PIPELINING")) {
							supportsPipelining = true;
							if (_log.shouldDebug()) _log.debug("Server supports pipelining");
						} else if (c.startsWith("SIZE ")) {
							try {
								maxSize = Long.parseLong(c.substring(5));
								if (_log.shouldDebug()) _log.debug("Server max size: " + maxSize);
							} catch (NumberFormatException nfe) {}
						} else if (c.equals("8BITMIME")) {
							// unused, see encoding/EightBit.java
							eightBitMime = true;
							if (_log.shouldDebug()) _log.debug("Server supports 8bitmime");
						}
					}
				} else {
					error += _t("Server refused connection") + " (" + r +  ")\n";
					ok = false;
				}
			}
			if (ok && maxSize < DEFAULT_MAX_SIZE) {
				if (_log.shouldDebug()) _log.debug("Rechecking with new max size");
				// recalculate whether we'll fit
				// copied from WebMail
				long total = body.length();
				if (attachments != null && !attachments.isEmpty()) {
					for(Attachment a : attachments) {
						total += a.getSize();
					}
				}
				long binaryMax = (long) ((maxSize * 57.0d / 78) - 32*1024);
				if (total > binaryMax) {
					ok = false;
					error += _t("Email is too large, max is {0}",
				                    DataHelper.formatSize2(binaryMax, false) + 'B') + '\n';
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
				// in-memory replace, no copies
                                DataHelper.replace(body, "\r\n.\r\n", "\r\n..\r\n");
				//socket.getOutputStream().write(DataHelper.getUTF8(body));
				//socket.getOutputStream().write(DataHelper.getASCII("\r\n.\r\n"));
				// Do it this way so we don't double the memory
				out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "ISO-8859-1"));
				writeMail(out, body, attachments, boundary);
				out.write("\r\n.\r\n");
				out.flush();
				socket.setSoTimeout(0);
				int result = sendCmd(null);
				if (result == 250)
					mailSent = true;
				else
					error += _t("Error sending mail") + " (" + result +  ")\n";
			}
		} catch (IOException e) {
			error += _t("Error sending mail") + ": " + e.getMessage() + '\n';
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
			if (out != null) try { out.close(); } catch (IOException ioe) {}
		}
		return mailSent;
	}

	/**
	 *  Caller must close out
	 *
	 *  @param body headers and body, without the attachments
	 *  @param attachments may be null
	 *  @param boundary non-null if attachments is non-null
	 */
	public static void writeMail(Writer out, StringBuilder body,
	                             List<Attachment> attachments, String boundary) throws IOException {
		out.write(body.toString());
		// moved from WebMail so we don't bring the attachments into memory
		// Also TODO use the 250 service extension responses to pick the best encoding
		// and check the max total size
		if (attachments != null && !attachments.isEmpty()) {
			for(Attachment attachment : attachments) {
				String encodeTo = attachment.getTransferEncoding();
				Encoding encoding = EncodingFactory.getEncoding(encodeTo);
				if (encoding == null)
					throw new EncodingException( _t("No Encoding found for {0}", encodeTo));
				// ref: https://blog.nodemailer.com/2017/01/27/the-mess-that-is-attachment-filenames/
				// ref: RFC 2231
				// split Content-Disposition into 3 lines to maximize room
				// TODO filename*0* for long names...
				String name = attachment.getFileName();
				String name2 = FilenameUtil.sanitizeFilename(name);
				String name3 = FilenameUtil.encodeFilenameRFC5987(name);
				out.write("\r\n--" + boundary +
				          "\r\nContent-type: " + attachment.getContentType() +
				          "\r\nContent-Disposition: attachment;\r\n\tfilename=\"" + name2 +
				          "\";\r\n\tfilename*=" + name3 +
				          "\r\nContent-Transfer-Encoding: " + attachment.getTransferEncoding() +
				          "\r\n\r\n");
				InputStream in = null;
				try {
					in = attachment.getData();
				 	encoding.encode(in, out);
				} finally {
					if (in != null) try { in.close(); } catch (IOException ioe) {}
				}
			}
			out.write( "\r\n--" + boundary + "--\r\n" );
		}
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

		/** @param t non-null */
		public Result(int r, String t) {
			result = r;
			recv = t;
		}

		/** @since 0.9.33 */
		@Override
		public String toString() {
			StringBuilder buf = new StringBuilder();
			buf.append(result);
			if (recv.length() > 0)
				buf.append(' ').append(recv);
			return buf.toString();
		}
	}

	/** translate */
	private static String _t(String s) {
		return Messages.getString(s);
	}

	/** translate */
	private static String _t(String s, Object o) {
		return Messages.getString(s, o);
	}
}
