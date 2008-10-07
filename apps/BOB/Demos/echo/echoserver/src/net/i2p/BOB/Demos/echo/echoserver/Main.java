/**
 *            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
 *                    Version 2, December 2004
 *
 * Copyright (C) sponge
 *   Planet Earth
 * Everyone is permitted to copy and distribute verbatim or modified
 * copies of this license document, and changing it is allowed as long
 * as the name is changed.
 *
 *            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
 *   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION
 *
 *  0. You just DO WHAT THE FUCK YOU WANT TO.
 *
 * See...
 *
 *	http://sam.zoy.org/wtfpl/
 *	and
 *	http://en.wikipedia.org/wiki/WTFPL
 *
 * ...for any additional details and liscense questions.
 */
package net.i2p.BOB.Demos.echo.echoserver;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author sponge
 */
public class Main {
	public static String Lread(InputStream in) throws IOException {
		String S;
		int b;
		char c;

		S = new String();

		while(true) {
			b = in.read();
			if(b < 20) {
				break;
			}
			c = (char)(b & 0x7f); // We only really give a fuck about ASCII
			S = new String(S + c);
		}
		return S;
	}

	/**
	 * Check for "ERROR" and if so, throw RuntimeException
	 * @param line
	 * @throws java.lang.RuntimeException
	 */
	static void checkline(String line) throws RuntimeException {
		System.out.println(line); // print status
		if(line.startsWith("ERROR")) {
			throw new RuntimeException(line);
		}
	}

	static void wrtxt(OutputStream CMDout, String s) throws IOException {
		CMDout.write(s.getBytes());
		CMDout.write('\n');
		CMDout.flush();
	}

	static void setupconn(String[] args) throws UnknownHostException, IOException, RuntimeException {
		String line;
		Socket CMDsock = new Socket("localhost", 0xB0B);
		InputStream CMDin = CMDsock.getInputStream();
		OutputStream CMDout = CMDsock.getOutputStream();
		// setup the tunnel.
		line = Lread(CMDin);
		System.out.println(line); // print the banner
		line = Lread(CMDin);
		System.out.println(line); // print initial status, should always be "OK"
		try {
			wrtxt(CMDout, "status " + args[2]);
			line =Lread(CMDin); // get the status of this nickname, if it's an error, create it
			checkline(line);
		} catch(RuntimeException rte) {
			wrtxt(CMDout, "setnick " + args[2]);
			line =Lread(CMDin); // create a new nickname
			checkline(line);
			wrtxt(CMDout, "newkeys ");
			line =Lread(CMDin); // set up new keys
			checkline(line);
			wrtxt(CMDout, "outport " + args[1]);
			line = Lread(CMDin); // set the port we connect out on
			checkline(line);
		}
		wrtxt(CMDout, "getnick " + args[2]);
		line = Lread(CMDin); // Set to our nick
		checkline(line);
		wrtxt(CMDout, "start ");
		line = Lread(CMDin); // an error here is OK
		System.out.println(line); // print status
		CMDsock.close(); // we no longer need this particular socket

	}

	static void deleteconn(String[] args) throws UnknownHostException, IOException, RuntimeException {
		String line;
		Socket CMDsock = new Socket("localhost", 0xB0B);
		InputStream CMDin = CMDsock.getInputStream();
		OutputStream CMDout = CMDsock.getOutputStream();
		// delete the tunnel.
		line = Lread(CMDin);
		System.out.println(line); // print the banner
		line = Lread(CMDin);
		System.out.println(line); // print initial status, should always be "OK"
		wrtxt(CMDout, "getnick " + args[2]); // Set to our nick
		line = Lread(CMDin);
		checkline(line);
		wrtxt(CMDout, "stop");
		line = Lread(CMDin);
		checkline(line);
		try {
			Thread.sleep(2000); //sleep for 2000 ms (Two seconds)
		} catch(Exception e) {
			// nop
		}

		wrtxt(CMDout, "clear");
		line = Lread(CMDin);
		while(line.startsWith("ERROR")) {
			wrtxt(CMDout, "clear");
			line = Lread(CMDin);
		}
		System.out.println(line); // print status
		CMDsock.close(); // we no longer need this particular socket

	}

	static void chatter(Socket sock) throws UnknownHostException, IOException, RuntimeException {
		String line;
		InputStream in = sock.getInputStream();
		OutputStreamWriter out = new OutputStreamWriter(new BufferedOutputStream(sock.getOutputStream()));

		line = Lread(in); // get remote I2P address
		System.out.println("Connect from: " + line); // show user

		out.write("Hello, You are connecting from " + line + "\n"); // send greeting
		out.flush(); // make sure it's sent.
		line = Lread(in); // get test text from client
		System.out.println("Got "+line); // show user
		sock.close(); // done.
	}

	private static void serverlistener(String[] args) throws UnknownHostException, IOException, RuntimeException {
		ServerSocket listener = new ServerSocket(Integer.parseInt(args[1]), 10, InetAddress.getByName("localhost"));
		Socket server;

		while(true) {
			server = listener.accept();
			chatter(server);
		}
	}

	/**
	 *
	 * @param args tunnelport tunnelnickname
	 */
	public static void main(String[] args) {
		// I'm lazy, and want to exit on any failures.
		try {
			setupconn(args); // talk to BOB, set up an inbound port
			serverlistener(args);  // talk over the connection

		} catch(UnknownHostException ex) {
			Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
		} catch(IOException ex) {
			Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
		}
		try {
			deleteconn(args);
		} catch(UnknownHostException ex) {
			Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
		} catch(IOException ex) {
			Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
		} catch(RuntimeException ex) {
			Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
}
