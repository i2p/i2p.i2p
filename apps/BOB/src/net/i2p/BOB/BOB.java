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
package net.i2p.BOB;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;
import net.i2p.client.I2PClient;
import net.i2p.client.streaming.RetransmissionTimer;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 *
 * BOB, main command socket listener, launches the command parser engine.
 * 
 * @author sponge
 */
public class BOB {

	private final static Log _log = new Log(BOB.class);
	public final static String PROP_CONFIG_LOCATION = "BOB.config";
	public final static String PROP_BOB_PORT = "BOB.port";
	public final static String PROP_BOB_HOST = "BOB.host";
	private static int maxConnections = 0;
	private static nickname database;

	/**
	 * Log a warning
	 * 
	 * @param arg
	 */
	public static void warn(String arg) {
		System.out.println(arg);
		_log.warn(arg);
	}

	/**
	 * Log an error
	 * 
	 * @param arg
	 */
	public static void error(String arg) {
		System.out.println(arg);
		_log.error(arg);
	}

	/**
	 * Listen for incoming connections and handle them
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		database = new nickname();
		int i = 0;
		boolean save = false;
		// Set up all defaults to be passed forward to other threads.
		// Re-reading the config file in each thread is pretty damn stupid.
		// I2PClient client = I2PClientFactory.createClient();
		Properties props = new Properties();
		String configLocation = System.getProperty(PROP_CONFIG_LOCATION, "bob.config");

		// This is here just to ensure there is no interference with our threadgroups.
		SimpleTimer Y = RetransmissionTimer.getInstance();
		i = Y.hashCode();

		try {
			props.load(new FileInputStream(configLocation));
		} catch(FileNotFoundException fnfe) {
			warn("Unable to load up the BOB config file " + configLocation + ", Using defaults.");
			warn(fnfe.toString());
			save = true;
		} catch(IOException ioe) {
			warn("IOException on BOB config file " + configLocation + ", using defaults.");
			warn(ioe.toString());
		}
		// Global router and client API configurations that are missing are set to defaults here.
		if(!props.containsKey(I2PClient.PROP_TCP_HOST)) {
			props.setProperty(I2PClient.PROP_TCP_HOST, "localhost");
		}
		if(!props.containsKey(I2PClient.PROP_TCP_PORT)) {
			props.setProperty(I2PClient.PROP_TCP_PORT, "7654");
		}
		if(!props.containsKey(I2PClient.PROP_RELIABILITY)) {
			props.setProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_BEST_EFFORT);
		}
		if(!props.containsKey(PROP_BOB_PORT)) {
			props.setProperty(PROP_BOB_PORT, "2827"); // 0xB0B
		}
		if(!props.containsKey("inbound.length")) {
			props.setProperty("inbound.length", "1");
		}
		if(!props.containsKey("outbound.length")) {
			props.setProperty("outbound.length", "1");
		}
		if(!props.containsKey("inbound.lengthVariance")) {
			props.setProperty("inbound.lengthVariance", "0");
		}
		if(!props.containsKey("outbound.lengthVariance")) {
			props.setProperty("outbound.lengthVariance", "0");
		}
		if(!props.containsKey(PROP_BOB_HOST)) {
			props.setProperty(PROP_BOB_HOST, "localhost");
		}
		if(save) {
			try {
				warn("Writing new defaults file " + configLocation);
				props.store(new FileOutputStream(configLocation), configLocation);
			} catch(IOException ioe) {
				warn("IOException on BOB config file " + configLocation + ", " + ioe);
			}
		}

		try {
			warn("BOB is now running.");
			ServerSocket listener = new ServerSocket(Integer.parseInt(props.getProperty(PROP_BOB_PORT)), 10, InetAddress.getByName(props.getProperty(PROP_BOB_HOST)));
			Socket server;

			while((i++ < maxConnections) || (maxConnections == 0)) {
				//doCMDS connection;

				server = listener.accept();
				doCMDS conn_c = new doCMDS(server, props, database, _log);
				Thread t = new Thread(conn_c);
				t.start();
			}
		} catch(IOException ioe) {
			warn("IOException on socket listen: " + ioe);
			ioe.printStackTrace();
		}
	}
}
