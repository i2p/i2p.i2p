package edu.internet2.ndt;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ResourceBundle;

/**
 * Class that defines utility methods used by the NDT code
 */
 public class NDTUtils {
	
	/**
	 * Utility method to print double value up to the hundredth place.
	 * 
	 * @param paramDblToFormat
	 *            Double numbers to format
	 * @return String value of double number
	 */
	public static String prtdbl(double paramDblToFormat) {
		String str = null;
		int i;

		if (paramDblToFormat == 0) {
			return ("0");
		}
		str = Double.toString(paramDblToFormat);
		i = str.indexOf(".");
		i = i + 3;
		if (i > str.length()) {
			i = i - 1;
		}
		if (i > str.length()) {
			i = i - 1;
		}
		return (str.substring(0, i));
	} // prtdbl() method ends

	
	/**
	 * Utility method to print Text values for data speed related keys.
	 * 
	 * @param paramIntVal
	 *            integer parameter for which we find text value
	 * @return String Textual name for input parameter
	 */
	public static String prttxt(int paramIntVal, ResourceBundle paramResBundObj) {
		String strNameTxt = null;

		switch (paramIntVal) {
		case (NDTConstants.DATA_RATE_SYSTEM_FAULT):
			strNameTxt = paramResBundObj
					.getString(NDTConstants.SYSTEM_FAULT_STR); 
			break;
		case NDTConstants.DATA_RATE_RTT:
			strNameTxt = paramResBundObj.getString(NDTConstants.RTT_STR); 
			break;
		case NDTConstants.DATA_RATE_DIAL_UP:
			strNameTxt = paramResBundObj.getString(NDTConstants.DIALUP_STR); 
			break;
		case NDTConstants.DATA_RATE_T1:
			strNameTxt = NDTConstants.T1_STR;
			break;
		case NDTConstants.DATA_RATE_ETHERNET:
			strNameTxt = NDTConstants.ETHERNET_STR;
			break;
		case NDTConstants.DATA_RATE_T3:
			strNameTxt = NDTConstants.T3_STR;
			break;
		case NDTConstants.DATA_RATE_FAST_ETHERNET:
			strNameTxt = NDTConstants.FAST_ETHERNET; 
			break;
		case NDTConstants.DATA_RATE_OC_12:
			strNameTxt = NDTConstants.OC_12_STR;
			break;
		case NDTConstants.DATA_RATE_GIGABIT_ETHERNET:
			strNameTxt = NDTConstants.GIGABIT_ETHERNET_STR; 
			break;
		case NDTConstants.DATA_RATE_OC_48:
			strNameTxt = NDTConstants.OC_48_STR;
			break;
		case NDTConstants.DATA_RATE_10G_ETHERNET:
			strNameTxt = NDTConstants.TENGIGABIT_ETHERNET_STR; 
			break;
		} // end switch
		return (strNameTxt);
	} // prttxt() method ends

	/**
	 * Utility method to check if the given string is empty ("") or null.
	 *
	 * @param str
	 *            String to check
	 * @return true is the given string is empty; otherwise false
	 */
	public static boolean isEmpty(String str) {
		return str == null || str.length() == 0;
	} // isEmpty() method ends

	/**
	 * Utility method to check if the given string is not empty ("") or null.
	 *
	 * @param str
	 *            String to check
	 * @return true is the given string is not empty; otherwise false
	 */
	public static boolean isNotEmpty(String str) {
	    return !isEmpty(str);
	} // isNotEmpty() method ends


	/**
	 * Utility method to create mailTo link
	 *
	 * @param name
	 *            user identifier
	 * @param host
	 *            fully qualified domain name
	 * @param subject
	 *            email subject
	 * @param body
	 *            email body
	 * @return created mailTo link with the encoded parameters
	 */
	public static String mailTo(final String name, final String host,
								final String subject, final String body) {
		return String.format(
				"mailto:%s@%s?subject=%s&body=%s",
				new Object[]{
						urlEncode(name), urlEncode(host),
						urlEncode(subject), urlEncode(body)
				}
		);
	} // mailTo() method ends

	/**
	 * Utility method to encode the given string using UTF-8 encoding
	 *
	 * @param str
	 *            String to encode
	 * @return encoded string with replacing '+' to '%20'
	 */
	public static String urlEncode(String str) {
		try {
			return URLEncoder.encode(str, "utf-8").replace("+", "%20");
		} catch (UnsupportedEncodingException e) {
			throw new IllegalArgumentException(e);
		}
	} // urlEncode() method ends

}
