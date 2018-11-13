package edu.internet2.ndt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Class aggregating operations that can be performed for
 * sending/receiving/reading Protocol messages
 * 
 * */

public class Protocol {
	private InputStream _ctlInStream;
	private OutputStream _ctlOutStream;
	private boolean jsonSupport = true;

	/**
	 * Constructor that accepts socket over which to communicate as parameter
	 * 
	 * @param ctlSocketParam
	 *            socket used to send the protocol messages over
	 * @throws IOException
	 *             if Input/Output streams cannot be read from/written into
	 *             correctly
	 */
	public Protocol(Socket ctlSocketParam) throws IOException {
		_ctlInStream = ctlSocketParam.getInputStream();
		_ctlOutStream = ctlSocketParam.getOutputStream();
	}

	/**
	 * Send message given its Type and data byte
	 * 
	 * @param bParamType
	 *            Control Message Type
	 * @param bParamToSend
	 *            Data value to send
	 * @throws IOException
	 *             If data cannot be successfully written to the Output Stream
	 * 
	 * */
	public void send_msg(byte bParamType, byte bParamToSend) throws IOException {
		byte[] tab = new byte[] { bParamToSend };
		send_msg(bParamType, tab);
	}

	/**
	 * Send message given its Type and data byte
	 *
	 * @param bParamType
	 *            Control Message Type
	 * @param bParamToSend
	 *            Data value to send
	 * @throws IOException
	 *             If data cannot be successfully written to the Output Stream
	 *
	 * */
	public void send_json_msg(byte bParamType, byte bParamToSend) throws IOException {
		byte[] tab = new byte[] { bParamToSend };
		send_json_msg(bParamType, tab);
	}

	/**
	 * Send protocol messages given their type and data byte array
	 *
	 * @param bParamType
	 *            Control Message Type
	 * @param bParamToSend
	 *            Data value array to send
	 * @throws IOException
	 *             If data cannot be successfully written to the Output Stream
	 *
	 * */
	public void send_json_msg(byte bParamType, byte[] bParamToSend)
			throws IOException {
		if (jsonSupport) {
			send_msg(bParamType, JSONUtils.createJsonObj(bParamToSend));
		} else {
			send_msg(bParamType, bParamToSend);
		}
	}

	/**
	 * Send protocol messages given their type and data byte array
	 * 
	 * @param bParamType
	 *            Control Message Type
	 * @param bParamToSend
	 *            Data value array to send
	 * @throws IOException
	 *             If data cannot be successfully written to the Output Stream
	 * 
	 * */
	public void send_msg(byte bParamType, byte[] bParamToSend)
			throws IOException {
		byte[] header = new byte[3];
		header[0] = bParamType;

		// 2 bytes are used to hold data length. Thus, max(data length) = 65535
		header[1] = (byte) (bParamToSend.length >> 8);
		header[2] = (byte) bParamToSend.length;

		// Write data to outputStream
		_ctlOutStream.write(header);
		_ctlOutStream.write(bParamToSend);
	}

	/**
	 * Populate Message byte array with specific number of bytes of data from
	 * socket input stream
	 * 
	 * @param msgParam
	 *            Message object to be populated
	 * @param iParamAmount
	 *            specified number of bytes to be read
	 * @return integer number of bytes populated
	 * @throws IOException
	 *             If data cannot be successfully read from the Input Stream
	 */
	public int readn(Message msgParam, int iParamAmount) throws IOException {
		int read = 0;
		int tmp;
		msgParam.initBodySize(iParamAmount);
		while (read != iParamAmount) {			
			tmp = _ctlInStream
					.read(msgParam._yaBody, read, iParamAmount - read);
			if (tmp <= 0) {
				return read;
			}
			read += tmp;
		}
		return read;
	}

	/**
	 * Receive message at end-point of socket
	 * 
	 * @param msgParam
	 *            Message object
	 * @return integer with values:
	 *         <p>
	 *         a) Success:
	 *         <ul>
	 *         <li>
	 *         value=0 : successfully read expected number of bytes.</li>
	 *         </ul>
	 *         <p>
	 *         b) Error:
	 *         <ul>
	 *         <li>value= 1 : Error reading ctrl-message length and data type
	 *         itself, since NDTP-control packet has to be at the least 3 octets
	 *         long</li>
	 *         <li>value= 3 : Error, mismatch between "length" field of
	 *         ctrl-message and actual data read</li>
	 *         </ul>
	 * */
	public int recv_msg(Message msgParam) throws IOException {
		int length;
		if (readn(msgParam, 3) != 3) {
			return 1;
		}
		
		byte[] yaMsgBody = msgParam.getBody();
		msgParam.setType(yaMsgBody[0]);

		// Get data length
		length = ((int) yaMsgBody[1] & 0xFF) << 8;
		length += (int) yaMsgBody[2] & 0xFF;

		if (readn(msgParam, length) != length) {
			return 3;
		}
		return 0;
	}

	/**
	 * Method to close open Streams
	 */
	public void close() {
		try {
			_ctlInStream.close();
			_ctlOutStream.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void setJsonSupport(boolean jsonSupport) {
		this.jsonSupport = jsonSupport;
	}

} // end class Protocol
