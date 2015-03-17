/*
 * Created on Nov 4, 2004
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
 * $Revision: 1.1 $
 */
package i2p.susi.webmail.pop3;

import i2p.susi.debug.Debug;
import i2p.susi.util.ReadBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Hashtable;

/**
 * @author susi23
 */
public class POP3MailBox {

	private static final int DEFAULT_BUFSIZE = 4096;

	private String host = null, user = null, pass = null;

	private String lastLine = "-ERR", lastError = null;

	private int port = 0, mails = 0, read = 0;

	private boolean connected = false;

	private Hashtable headerList = null, bodyList = null, sizes = null, uidlToID = null;
	private ArrayList uidlList = null;

	private Socket socket = null;

	private byte[] buffer = new byte[DEFAULT_BUFSIZE];

	private Object synchronizer = null;

	private Object[] uidls = null;

	/**
	 * @param host
	 * @param port
	 * @param user
	 * @param pass
	 */
	public POP3MailBox(String host, int port, String user, String pass) {
		Debug.debug(
			Debug.DEBUG,
			"Mailbox(" + host + "," + port + "," + user + ",password)");
		this.host = host;
		this.port = port;
		this.user = user;
		this.pass = pass;
		headerList = new Hashtable();
		bodyList = new Hashtable();
		uidlList = new ArrayList();
		uidlToID = new Hashtable();
		sizes = new Hashtable();
		synchronizer = new Object();
		connect();
	}

	/**
	 * 
	 * @param uidl
	 * @return Byte buffer containing header data.
	 */
	public ReadBuffer getHeader( String uidl ) {
		synchronized( synchronizer ) {
			return getHeader( getIDfromUIDL( uidl ) );
		}
	}

	/**
	 * retrieves header from pop3 server (with TOP command and RETR as fallback)
	 * 
	 * @param id message id
	 * @return Byte buffer containing header data.
	 */
	private ReadBuffer getHeader( int id ) {
		synchronized( synchronizer ) {
			Debug.debug(Debug.DEBUG, "getHeader(" + id + ")");
			Integer idObj = Integer.valueOf(id);
			ReadBuffer header = null;
			if (id >= 1 && id <= mails) {
				/*
				 * is data already cached?
				 */
				header = (ReadBuffer)headerList.get(idObj);
				if (header == null) {
					/*
					 * try 'TOP n 0' command
					 */
					header = sendCmdN("TOP " + id + " 0" );
				}
				if( header == null) {
					/*
					 * try 'RETR n' command
					 */
					header = sendCmdN("RETR " + id );
				}
				if( header != null ) {
					/*
					 * store result in hashtable
					 */
					headerList.put(idObj, header);
				}
			}
			else {
				lastError = "Message id out of range.";
			}
			return header;
		}
	}

	/**
	 * 
	 * @param uidl
	 * @return Byte buffer containing body data.
	 */
	public ReadBuffer getBody( String uidl ) {
		synchronized( synchronizer ) {
			return getBody( getIDfromUIDL( uidl ) );
		}
	}
	
	/**
	 * retrieve message body from pop3 server (via RETR command)
	 * 
	 * @param id message id
	 * @return Byte buffer containing body data.
	 */
	private ReadBuffer getBody(int id) {
		synchronized( synchronizer ) {
			Debug.debug(Debug.DEBUG, "getBody(" + id + ")");
			Integer idObj = Integer.valueOf(id);
			ReadBuffer body = null;
			if (id >= 1 && id <= mails) {
				body = (ReadBuffer)bodyList.get(idObj);
				if( body == null ) {
					body = sendCmdN( "RETR " + id );
					if (body != null) {
						bodyList.put(idObj, body);
					}
					else {
						Debug.debug( Debug.DEBUG, "sendCmdN returned null" );
					}
				}
			}
			else {
				lastError = "Message id out of range.";
			}
			return body;
		}
	}

	/**
	 * 
	 * @param uidl
	 * @return Success of delete operation: true if successful.
	 */
	public boolean delete( String uidl )
	{
		Debug.debug(Debug.DEBUG, "delete(" + uidl + ")");
		synchronized( synchronizer ) {
			return delete( getIDfromUIDL( uidl ) );
		}
	}
	
	/**
	 * delete message on pop3 server
	 * 
	 * @param id message id
	 * @return Success of delete operation: true if successful.
	 */
	private boolean delete(int id)
	{
		Debug.debug(Debug.DEBUG, "delete(" + id + ")");
		
		boolean result = false;
		
		synchronized( synchronizer ) {
			
			try {
				result = sendCmd1a( "DELE " + id );
			}
			catch (IOException e) {
			}
		}
		return result;
	}

	/**
	 * 
	 * @param uidl
	 * @return Message size in bytes.
	 */
	public int getSize( String uidl ) {
		synchronized( synchronizer ) {
			return getSize( getIDfromUIDL( uidl ) );
		}
	}
	
	/**
	 * get size of a message (via LIST command)
	 * 
	 * @param id message id
	 * @return Message size in bytes.
	 */
	private int getSize(int id) {
		synchronized( synchronizer ) {
			Debug.debug(Debug.DEBUG, "getSize(" + id + ")");
			int result = 0;
			/*
			 * find value in hashtable
			 */
			Integer resultObj = (Integer) sizes.get(Integer.valueOf(id));
			if (resultObj != null)
				result = resultObj.intValue();
			return result;
		}
	}

	/**
	 * check whether connection is still alive
	 * 
	 * @return true or false
	 */
	public boolean isConnected() {
		Debug.debug(Debug.DEBUG, "isConnected()");

		if (socket == null
			|| !socket.isConnected()
			|| socket.isInputShutdown()
			|| socket.isOutputShutdown()
			|| socket.isClosed()) {
			connected = false;
		}
		return connected;
	}

	/**
	 * 
	 * @throws IOException
	 */
	private void updateUIDLs() throws IOException
	{
		synchronized (synchronizer) {

			ReadBuffer readBuffer = null;
			
			uidlToID.clear();
			uidlList.clear();
			uidls = null;
			
			readBuffer = sendCmdNa( "UIDL", DEFAULT_BUFSIZE );
			if( readBuffer != null ) {
				String[] lines = readBuffer.toString().split( "\r\n" );
				
				for( int i = 0; i < lines.length; i++ ) {
					int j = lines[i].indexOf( " " );
					if( j != -1 ) {
						try {
							int n = Integer.parseInt( lines[i].substring( 0, j ) );
							String uidl = lines[i].substring( j+1 );
							uidlToID.put( uidl, Integer.valueOf( n ) );
							uidlList.add( n-1, uidl );
						}
						catch( NumberFormatException nfe ) {
							
						}
					}
				}
				uidls = uidlList.toArray();
			}
			else {
				System.err.println( "Error getting UIDL list from pop3 server.");
			}
		}
	}
	/**
	 * 
	 * @throws IOException
	 */
	private void updateSizes() throws IOException {
		/*
		 * try LIST
		 */
		sizes.clear();
		ReadBuffer readBuffer = sendCmdNa("LIST", DEFAULT_BUFSIZE );
		if(readBuffer != null) {
			String[] lines = new String( readBuffer.content, 0, readBuffer.length ).split( "\r\n" );
			if (lines != null) {
				sizes = new Hashtable();
				for (int i = 0; i < lines.length; i++) {
					int j = lines[i].indexOf(" ");
					if (j != -1) {
						int key = Integer.parseInt(lines[i].substring(0, j));
						int value =	Integer.parseInt(lines[i].substring(j + 1));
						sizes.put(Integer.valueOf(key), Integer.valueOf(value));
					}
				}
			}
		}
		else {
			System.err.println( "Error getting size LIST from pop3 server.");
		}
	}

	/**
	 * 
	 *
	 */
	public void refresh() {
		synchronized( synchronizer ) {
			close();
			connect();
		}
	}
	/**
	 * 
	 *
	 */
	private void clear()
	{
		uidlList.clear();
		uidlToID.clear();
		sizes.clear();
		uidls = null;
		mails = 0;
	}
	/**
	 * connect to pop3 server, login with USER and PASS and try STAT then
	 */
	private void connect() {
		Debug.debug(Debug.DEBUG, "connect()");

		clear();
		
		if (socket != null && socket.isConnected())
			close();
		
		try {
			socket = new Socket(host, port);
		} catch (UnknownHostException e) {
			lastError = e.getMessage();
			return;
		} catch (IOException e) {
			lastError = e.getMessage();
			return;
		}
		if (socket != null) {
			try {
				if (sendCmd1a("USER " + user)
					&& sendCmd1a("PASS " + pass)
					&& sendCmd1a("STAT") ) {

					int i = lastLine.indexOf(" ", 5);
					mails =
						Integer.parseInt(
							i != -1
								? lastLine.substring(4, i)
								: lastLine.substring(4));

					connected = true;
					updateUIDLs();
					updateSizes();
				}
				else {
					lastError = lastLine;
					close();
				}
			}
			catch (NumberFormatException e1) {
				lastError = "Error getting number of messages: " + e1.getCause();
			}
			catch (IOException e1) {
				lastError = "Error while opening mailbox: " + e1.getCause();
			}
		}
	}
	
	/**
	 * send command to pop3 server (and expect single line answer)
	 * 
	 * @param cmd command to send
	 * @return true if command was successful (+OK)
	 * @throws IOException
	 */
	private boolean sendCmd1a(String cmd) throws IOException {
		/*
		 * dont log password
		 */
		boolean result = false;
		String msg = cmd;
		if (msg.startsWith("PASS"))
			msg = "PASS provided";
		Debug.debug(Debug.DEBUG, "sendCmd1a(" + msg + ")");

		cmd += "\r\n";
		socket.getOutputStream().write(cmd.getBytes());
		read = socket.getInputStream().read(buffer);
		// Debug.debug(Debug.DEBUG, "sendCmd1a: read " + read + " bytes");
		if (read > 0) {
			lastLine = new String(buffer, 0, read);
			// Debug.debug( Debug.DEBUG, "sendCmd1a: READBUFFER: '" + lastLine + "'" );
			if (lastLine.startsWith("+OK")) {
				result = true;
			}
			else {
				lastError = lastLine;
			}
		}

		return result;
	}

	/**
	 * 
	 * @param cmd
	 * @return
	 */
	private ReadBuffer sendCmdN(String cmd )
	{
		return sendCmdN( cmd, DEFAULT_BUFSIZE );
	}
	/**
	 * @param cmd
	 * @return
	 */
	private ReadBuffer sendCmdN(String cmd, int bufSize )
	{
		ReadBuffer result = null;

		synchronized (synchronizer) {

			if (!isConnected())
				connect();

			try {
				result = sendCmdNa(cmd, bufSize);
			}
			catch (IOException e) {
				lastError = e.getMessage();
				Debug.debug( Debug.DEBUG, "sendCmdNa throws IOException: " + lastError );
				result = null;
			}
			
			if( result == null ) {
				connect();
				if (connected) {
					try {
						result = sendCmdNa(cmd, bufSize);
					}
					catch (IOException e2) {
						lastError = e2.getMessage();
						Debug.debug( Debug.DEBUG, "2nd sendCmdNa throws IOException: " + lastError );
						result = null;
					}
				}
				else {
					Debug.debug( Debug.DEBUG, "not connected after reconnect" );					
				}
			}
		}
		return result;
	}
	/**
	 * 
	 * @param src
	 * @param newSize
	 * @return
	 */
	private byte[] resizeArray( byte src[], int newSize )
	{
		byte dest[] = new byte[newSize];
		for( int i = 0; i < src.length; i++ )
			dest[i] = src[i];
		return dest;
	}
	/**
	 * 
	 * @param src
	 * @param srcOffset
	 * @param len
	 * @param dest
	 * @param destOffset
	 */
	private void copy( byte[] src, int srcOffset, int len, byte[] dest, int destOffset )
	{
		while( len-- > 0 ) {
			dest[destOffset++] = src[srcOffset++];
		}
	}
	/**
	 * @param id
	 * @return @throws
	 *         IOException
	 */
	private ReadBuffer sendCmdNa(String cmd, int bufSize ) throws IOException
	{
		Debug.debug(Debug.DEBUG, "sendCmdNa(" + cmd + ")");
		
		ReadBuffer readBuffer = null;
		long timeOut = 60000;
		byte result[] = new byte[bufSize];
		int written = 0;
		
		if (sendCmd1a(cmd)) {
			int offset = 0;
			while( offset < read - 1 && buffer[offset] != '\r' && buffer[offset+1] != '\n' )
				offset++;
			offset += 2;
			if( read - offset > result.length )
				result = resizeArray( result, result.length + result.length );
			if( read - offset > 0 )
				copy( buffer, offset, read - offset, result, 0 );
			written = read - offset;
			boolean doRead = true;
			// Debug.debug( Debug.DEBUG, "READBUFFER: '" + new String( result, 0, written ) + "'" );
			if( written >= 5 && result[ written - 5 ] == '\r' &&
					result[ written - 4 ] == '\n' &&
					result[ written - 3 ] == '.' &&
					result[ written - 2 ] == '\r' &&
					result[ written - 1 ] == '\n' ) {
				written -= 3;
				doRead = false;
			}
			InputStream input = socket.getInputStream();
			long startTime = System.currentTimeMillis();
			while (doRead) {
				int len = input.available();
				if( len == 0 ) {
					if( System.currentTimeMillis() - startTime > timeOut )
						throw new IOException( "Timeout while waiting on server response." );
					try {
						Thread.sleep( 500 );
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				else {
					while (len > 0) {
						read = socket.getInputStream().read(buffer, 0, len > buffer.length ? buffer.length : len );
						// Debug.debug(Debug.DEBUG, "read " + read + " bytes");
						if( written + read > result.length )
							result = resizeArray( result, result.length + result.length );
						copy( buffer, 0, read, result, written );
						written += read;
						// Debug.debug( Debug.DEBUG, "READBUFFER: '" + new String( result, 0, written ) + "'" );
						if( result[ written - 5 ] == '\r' &&
								result[ written - 4 ] == '\n' &&
								result[ written - 3 ] == '.' &&
								result[ written - 2 ] == '\r' &&
								result[ written - 1 ] == '\n' ) {
							written -= 3;
							doRead = false;
						}
						len -= read;
					}					
				}
			}
			readBuffer = new ReadBuffer();
			readBuffer.content = result;
			readBuffer.offset = 0;
			readBuffer.length = written;
		}
		else {
			Debug.debug( Debug.DEBUG, "sendCmd1a returned false" );
		}

		return readBuffer;
	}

	/**
	 * @return The amount of e-mails available.
	 */
	public int getNumMails() {
		synchronized( synchronizer ) {
			Debug.debug(Debug.DEBUG, "getNumMails()");

			if (!isConnected())
				connect();

			return connected ? mails : 0;
		}
	}

	/**
	 * @return The most recent error message.
	 */
	public String lastError() {
		Debug.debug(Debug.DEBUG, "lastError()");
		return this.lastError;
	}

	/**
	 * 
	 *  
	 */
	public void close() {
		synchronized( synchronizer ) {
			Debug.debug(Debug.DEBUG, "close()");
			if (socket != null && socket.isConnected()) {
				try {
					sendCmd1a("QUIT");
					socket.close();
				} catch (IOException e) {
					System.err.println(
							"Error while closing connection: " + e.getCause());
				}
			}
			socket = null;
			connected = false;
		}
	}

	/**
	 * returns number of message with given UIDL
	 * 
	 * @param uidl
	 * @return Message number.
	 */
	private int getIDfromUIDL( String uidl )
	{
		int result = -1;
		Integer intObject = (Integer)uidlToID.get( uidl );
		if( intObject != null ) {
			result = intObject.intValue();
		}
		return result;
	}
	/**
	 * 
	 * @param id
	 * @return UIDL.
	 */
	public String getUIDLfromID( int id )
	{
		return (String)uidlList.get( id );
	}
	/**
	 * 
	 * @return A list of the available UIDLs.
	 */
	public Object[] getUIDLs()
	{
		return uidls;
	}
	/**
	 * 
	 * @param args
	 */
	public static void main( String[] args )
	{
		Debug.setLevel( Debug.DEBUG );
		POP3MailBox mailbox = new POP3MailBox( "localhost", 7660 , "test", "test");
		ReadBuffer readBuffer = mailbox.sendCmdN( "LIST" );
		System.out.println( "list='" + readBuffer + "'" );
	}

	/**
	 * 
	 */
	public void performDelete()
	{
		close();
		connect();
	}
}
