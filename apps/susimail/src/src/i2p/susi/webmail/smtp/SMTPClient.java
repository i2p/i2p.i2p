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
import i2p.susi.webmail.encoding.Encoding;
import i2p.susi.webmail.encoding.EncodingException;
import i2p.susi.webmail.encoding.EncodingFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * @author susi
 */
public class SMTPClient {
	
	Socket socket;
	byte buffer[];
	public String error;
	String lastResponse;
	
	private static Encoding base64 = null;
	
	static {
		base64 = EncodingFactory.getEncoding( "base64" );
	}
	public SMTPClient()
	{
		socket = null;
		buffer = new byte[10240];
		error = "";
		lastResponse = "";
	}
	
	public int sendCmd( String cmd )
	{
		Debug.debug( Debug.DEBUG, "sendCmd(" + cmd +")" );
		
		if( socket == null )
			return 0;

		int result = 0;
		lastResponse = "";
		
		try {
			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();
		
			if( cmd != null ) {
				cmd += "\r\n";
				out.write( cmd.getBytes() );
			}
			String str = "";
			boolean doContinue = true;
			while( doContinue ) {
				if( in.available() > 0 ) {
					int read = in.read( buffer );
					str += new String( buffer, 0, read );
					lastResponse += str;
					while( true ) {
						int i = str.indexOf( "\r\n" );
						if( i == -1 )
							break;
						if( result == 0 ) {
							try {
								result = Integer.parseInt( str.substring( 0, 3 ) );
							}
							catch( NumberFormatException nfe ) {
								result = 0;
								doContinue = false;
								break;
							}
						}
						if( str.substring( 3, 4 ).compareTo( " " ) == 0 ) {
							doContinue = false;
							break;
						}
						str = str.substring( i + 2 );
					}
				}
			}
		}
		catch (IOException e) {
			error += "IOException occured.<br>";
			result = 0;
		}
		return result;
	}
	public boolean sendMail( String host, int port, String user, String pass, String sender, Object[] recipients, String body )
	{
		boolean mailSent = false;
		boolean ok = true;
		
		try {
			socket = new Socket( host, port );
		}
		catch (Exception e) {
			error += "Cannot connect: " + e.getMessage() + "<br>";
			ok = false;
		}
		try {
			if( ok && sendCmd( null ) == 220 &&
					sendCmd( "EHLO localhost" ) == 250 &&
					sendCmd( "AUTH LOGIN" ) == 334 &&
					sendCmd( base64.encode( user ) ) == 334 &&
					sendCmd( base64.encode( pass ) ) == 235 &&
					sendCmd( "MAIL FROM: " + sender ) == 250 ) {
				
				for( int i = 0; i < recipients.length; i++ ) {
					if( sendCmd( "RCPT TO: " + recipients[i] ) != 250 ) {
						ok = false;
					}
				}
				if( ok ) {
					if( sendCmd( "DATA" ) == 354 ) {
						if( body.indexOf( "\r\n.\r\n" ) != -1 )
							body = body.replaceAll( "\r\n.\r\n", "\r\n..\r\n" );
						body += "\r\n.\r\n";
						try {
							socket.getOutputStream().write( body.getBytes() );
							if( sendCmd( null ) == 250 ) {
								mailSent = true;
							}
						}
						catch (Exception e) {
							ok = false;
							error += "Error while sending mail: " + e.getMessage() + "<br>";
						}
					}
				}
			}
		} catch (EncodingException e) {
			ok = false;
			error += e.getMessage();
		}
		if( !mailSent && lastResponse.length() > 0 ) {
			String[] lines = lastResponse.split( "\r\n" );
			for( int i = 0; i < lines.length; i++ )
				error += lines[i] + "<br>";			
		}
		sendCmd( "QUIT" );
		if( socket != null ) {
			try {
				socket.close();
			}
			catch (IOException e1) {
				// ignore
			}
		}
		return mailSent;
	}
}
