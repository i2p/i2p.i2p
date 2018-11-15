package edu.internet2.ndt;

/* This class has code taken from
 *   http://nerds.palmdrive.net/useragent/code.html
 *
 * Class used to obtain information about who is accessing a web-server.
 *
 * When a web browser accesses a web-server, it usually transmits a "User-Agent" string. 
 * This is expected to include the name and versions of the browser and 
 * the underlying Operating System. Though the information inside a user-agent string is not restricted to
 * these alone, currently, NDT uses this to get Browser OS only. 
 * 
 */

public class UserAgentTools {

	public static String[] getArray(String a, String b, String c) {
		String[] res = new String[3];
		res[0] = a;
		res[1] = b;
		res[2] = c;
		return res;
	}

	public static String[] getBrowser(String userAgent) {
			return getArray("?", "?", "?");
	}
}
