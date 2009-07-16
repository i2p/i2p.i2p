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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * UDP IO on I2P
 *
 *  FIX ME: Untested, and incomplete!
 *  I have no personal need to UDP yet,
 *  however alot of p2p apps pretty much demand it.
 *  The skeletal frame is here, just needs to be finished.
 *
 * @author sponge
 */
public class UDPIOthread implements I2PSessionListener, Runnable {

	private NamedDB info;
	private Log _log;
	private Socket socket;
	private DataInputStream in;
	private DataOutputStream out;
	private I2PSession _session;
	private Destination _peerDestination;
	private boolean up;

	/**
	 * Constructor
	 * @param info
	 * @param _log
	 * @param socket
	 * @param _session
	 */
	UDPIOthread(NamedDB info, Log _log, Socket socket, I2PSession _session) {
		this.info = info;
		this._log = _log;
		this.socket = socket;
		this._session = _session;

	}

	/**
	 *
	 */
	public void run() {
		byte data[] = new byte[1024];
		up = true;
		try {
			in = new DataInputStream(socket.getInputStream());
			out = new DataOutputStream(socket.getOutputStream());
			while (up) {
				int c = in.read(data);
				// Note: could do a loopback test here with a wrapper.
				boolean ok = _session.sendMessage(_peerDestination, data, 0, c);

				if (!ok) {
					up = false; // Is this the right thing to do??
				}
			}
		} catch (IOException ioe) {
			_log.error("Error running", ioe);
		} catch (I2PSessionException ise) {
			_log.error("Error communicating", ise);
		//		} catch(DataFormatException dfe) {
		//			_log.error("Peer destination file is not valid", dfe);
		} finally {
			if (_session != null) {
				try {
					_session.destroySession();
				} catch (I2PSessionException ise) {
					// ignored
				}
			}
		}
	}

	/**
	 *
	 * @param session
	 * @param msgId
	 * @param size
	 */
	public void messageAvailable(I2PSession session, int msgId, long size) {
//		_log.debug("Message available: id = " + msgId + " size = " + size);
		try {
			byte msg[] = session.receiveMessage(msgId);
			out.write(msg);
			out.flush();
		} catch (I2PSessionException ise) {
			up = false;
		} catch (IOException ioe) {
			up = false;
		}
	}

	// Great, can these be used to kill ourselves.
	/** required by {@link I2PSessionListener I2PSessionListener} to notify of disconnect */
	public void disconnected(I2PSession session) {
		_log.debug("Disconnected");
	// up = false;
	}

	/** required by {@link I2PSessionListener I2PSessionListener} to notify of error */
	public void errorOccurred(I2PSession session, String message, Throwable error) {
		_log.debug("Error occurred: " + message, error);
	// up = false;
	}

	/** required by {@link I2PSessionListener I2PSessionListener} to notify of abuse */
	public void reportAbuse(I2PSession session, int severity) {
		_log.debug("Abuse reported of severity " + severity);
	// up = false;
	}
}
