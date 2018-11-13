package edu.internet2.ndt;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * OsfwWorker creates a thread that listens for a message from the server. It
 * functions to check if the server has sent a message that is valid and
 * sufficient to determine if the server->client direction has a fire-wall.
 * 
 * <p>
 * As part of the simple firewall test, the Server must try to connect to the
 * Client's ephemeral port and send a TEST_MSG message containing a pre-defined
 * string "Simple firewall test" of 20 chars using this newly created
 * connection. This class implements this functionality.
 * 
 * The result of the test is set back into the Tcpbw100._iS2cSFWResult variable
 * (using setter methods) for the test results to be interpreted later
 * */

public class OsfwWorker implements Runnable {

	private ServerSocket _srvSocket;
	private int _iTestTime;
	private boolean _iFinalized = false;
	// local Tcpbw100 Applet reference
	Tcpbw100 _localTcpAppObj;

	/**
	 * Constructor
	 * 
	 * @param Socket
	 *            srvSocketParam Socket used to transmit protocol messages
	 * 
	 * @param iParamTestTime
	 *            Test time duration to wait for message from server
	 */
	OsfwWorker(ServerSocket srvSocketParam, int iParamTestTime) {
		this._srvSocket = srvSocketParam;
		this._iTestTime = iParamTestTime;
	}

	/**
	 * Constructor accepting Tcpbw100 parameter
	 * 
	 * @param ServerSocket
	 *            Socket on which to accept connections
	 * @param iParamTestTime
	 *            Test time duration to wait for message from server
	 * @param _localParam
	 *            Applet object used to set the result of the S->C firewall test
	 */
	OsfwWorker(ServerSocket srvSocketParam, int iParamTestTime,
			Tcpbw100 _localParam) {
		this._srvSocket = srvSocketParam;
		this._iTestTime = iParamTestTime;
		this._localTcpAppObj = _localParam;
	}

	/**
	 * Make current thread sleep for 1000 ms
	 * 
	 * */
	public void finalize() {
		// If test is not already complete/terminated, then sleep
		while (!_iFinalized) {
			try {
				Thread.currentThread().sleep(1000);
			} catch (InterruptedException e) {
				// do nothing.
			}
		}
	}

	/**
	 * run() method of this SFW Worker thread. This thread listens on the socket
	 * from the server for a given time period, and checks to see if the server
	 * has sent a message that is valid and sufficient to determine if the S->C
	 * direction has a fire-wall.
	 * */
	public void run() {

		Message msg = new Message();
		Socket socketObj = null;

		try {
			// set timeout to given value in ms
			_srvSocket.setSoTimeout(_iTestTime * 1000);
			try {

				// Blocking call trying to create connection to socket and
				// accept it
				socketObj = _srvSocket.accept();
			} catch (Exception e) {
				e.printStackTrace();

				// The "accept" call has failed, and indicates a firewall
				// possibility
				this._localTcpAppObj
						.setS2cSFWTestResults(NDTConstants.SFW_POSSIBLE);
				_srvSocket.close();
				_iFinalized = true;
				return;
			}
			Protocol sfwCtl = new Protocol(socketObj);

			// commented out sections indicate move to outer class
			if (sfwCtl.recv_msg(msg) != 0) {

				// error, msg read/received incorrectly. Hence set status as
				// unknown
				System.out
						.println("Simple firewall test: unrecognized message");
				this._localTcpAppObj
						.setS2cSFWTestResults(NDTConstants.SFW_UNKNOWN);
				// close socket objects and wrap up
				socketObj.close();
				_srvSocket.close();
				_iFinalized = true;
				return;
			}

			// The server sends a TEST_MSG type packet. Any other message-type
			// is not expected at this point, and hence an error
			if (msg.getType() != MessageType.TEST_MSG) {
				this._localTcpAppObj
						.setS2cSFWTestResults(NDTConstants.SFW_UNKNOWN);
				// close socket objects and wrap up
				socketObj.close();
				_srvSocket.close();
				_iFinalized = true;
				return;
			}

			
			// The server is expected to send a 20 char message that
			// says "Simple firewall test" . Every other message string
			// indicates an unknown firewall status
			
			if (!new String(msg.getBody())
					.equals(NDTConstants.SFW_PREDEFINED_TEST_MESSAGE)) {
				System.out.println("Simple firewall test: Improper message");
				this._localTcpAppObj
						.setS2cSFWTestResults(NDTConstants.SFW_UNKNOWN);
				// close socket objects and wrap up
				socketObj.close();
				_srvSocket.close();
				_iFinalized = true;
				return;
			}

			// If none of the above conditions were met, then, the server
			// message has been received correctly, and there seems to be no
			// firewall
			this._localTcpAppObj
					.setS2cSFWTestResults(NDTConstants.SFW_NOFIREWALL);

		} catch (IOException ex) {
			// Status of firewall could not be determined before concluding
			this._localTcpAppObj.setS2cSFWTestResults(NDTConstants.SFW_UNKNOWN);
		}

		// finalize and close connections
		try {
			socketObj.close();
			_srvSocket.close();
		} catch (IOException e) {
			System.err.println("OsfwWorker: Exception trying to close sockets"
					+ e);
			// log exception occurence
		}
		_iFinalized = true;
	}
}
