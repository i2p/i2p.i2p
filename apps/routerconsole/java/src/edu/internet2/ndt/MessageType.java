package edu.internet2.ndt;

/* Class to define the NDTP control message types
 * */

public class MessageType {

	public static final byte COMM_FAILURE = 0;
	public static final byte SRV_QUEUE = 1;
	public static final byte MSG_LOGIN = 2;
	public static final byte TEST_PREPARE = 3;
	public static final byte TEST_START = 4;
	public static final byte TEST_MSG = 5;
	public static final byte TEST_FINALIZE = 6;
	public static final byte MSG_ERROR = 7;
	public static final byte MSG_RESULTS = 8;
	public static final byte MSG_LOGOUT = 9;
	public static final byte MSG_WAITING = 10;
    public static final byte MSG_EXTENDED_LOGIN = 11;

}
