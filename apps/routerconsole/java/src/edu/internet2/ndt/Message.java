package edu.internet2.ndt;
/**
 * Class to define Message. Messages are composed of a "type" and a body. Some
 * examples of message types are : COMM_FAILURE, SRV_QUEUE, MSG_LOGIN,
 * TEST_PREPARE. Messages are defined to have a "length" field too. Currently, 2
 * bytes of the message "body" byte array are often used to store length (For
 * example, second/third array positions)
 * 
 * <p>
 * TODO for a later release: It may be worthwhile exploring whether MessageTypes
 * could be merged here instead of being located in NDTConstants. Message/Type
 * could also be made into an enumeration and checks for the current MessageType
 * being assigned could be incorporated.
 * 
 * @see MessageType for more Message Types.
 * 
 */
public class Message {

	// TODO: Could make these private and test changes in Protocol class. For
	// later release
	byte _yType;
	byte[] _yaBody;

	/**
	 * Get Message Type
	 * 
	 * @return byte indicating Message Type
	 * */
	public byte getType() {
		return _yType;
	}

	/**
	 * Set Message Type
	 * 
	 * @param bParamType
	 *            byte indicating Message Type
	 * */
	public void setType(byte bParamType) {
		this._yType = bParamType;
	}

	/**
	 * Get Message body as array
	 * 
	 * @return byte array message body
	 * */
	public byte[] getBody() {
		return _yaBody;
	}

	/**
	 * Set Message body, given a byte array input
	 * 
	 * @param baParamBody
	 *            message body byte array
	 * 
	 * */
	public void setBody(byte[] baParamBody) {
		int iParamSize = 0;
		if (baParamBody != null) {
			iParamSize = baParamBody.length;
		}
		_yaBody = new byte[iParamSize];
		System.arraycopy(baParamBody, 0, _yaBody, 0, iParamSize);
	}

	/**
	 * Set Message body, given a byte array and a size parameter. This may be
	 * useful if user wants to initialize the message, and then continue to
	 * populate it later. This method is unused currently.
	 * 
	 * @param iParamSize
	 *            byte array size
	 * @param baParamBody
	 *            message body byte array
	 * 
	 * */
	public void setBody(byte[] baParamBody, int iParamSize) {
		_yaBody = new byte[iParamSize];
		System.arraycopy(baParamBody, 0, _yaBody, 0, iParamSize);
	}

	/**
	 * Utility method to initialize Message body
	 * 
	 * @param iParamSize
	 *            byte array size
	 * 
	 * */
	public void initBodySize(int iParamSize) {
		this._yaBody = new byte[iParamSize];
	}

}