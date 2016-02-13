package com.docuverse.identicon;

import java.net.InetAddress;
import java.security.MessageDigest;

/**
 * Utility methods useful for implementing identicon functionality. Methods are
 * class methods for convenience.
 * <p>
 * Key method of interest is {@link #getIdenticonCode} which converts IP address
 * into identicon code.<br>
 * <strong>IMPORTANT</strong>: <code>inetSalt</code> value must be set to
 * reasonably long random string prior to invoking this method.
 * </p>
 * 
 * @author don
 */
public class IdenticonUtil {
	private static final int DEFAULT_IDENTICON_SIZE = 16;

	private static final int MINIMUM_IDENTICON_SIZE = 15;

	private static final int MAXIMUM_IDENTICON_SIZE = 64;

	private static final int DEFAULT_INET_MASK = 0xffffffff;

	private static int inetMask = DEFAULT_INET_MASK;

	private static String inetSalt;

	/**
	 * Returns current IP address mask. Default is 0xffffffff.
	 * 
	 * @return current IP address mask
	 */
	public static int getInetMask() {
		return inetMask;
	}

	/**
	 * Sets current IP address mask. Default is 0xffffffff.
	 * 
	 * @param inetMask
	 */
	public static void setInetMask(int inetMask) {
		IdenticonUtil.inetMask = inetMask;
	}

	/**
	 * Returns current inetSalt value.
	 * 
	 * @return the value
	 */
	public static String getInetSalt() {
		return inetSalt;
	}

	/**
	 * Sets current inetSalt value.
	 * 
	 * @param inetSalt
	 */
	public static void setInetSalt(String inetSalt) {
		IdenticonUtil.inetSalt = inetSalt;
	}

	/**
	 * Returns identicon code for given IP address.
	 * <p>
	 * Current implementation uses first four bytes of SHA1(int(mask(ip))+salt)
	 * where mask(ip) uses inetMask to remove unwanted bits from IP address.
	 * Also, since salt is a string for convenience sake, int(mask(ip)) is
	 * converetd into a string and combined with inetSalt prior to hashing.
	 * </p>
	 * 
	 * @param inetAddr
	 *            IP address
	 * @return identicon code for <code>inetAddr</code>
	 * @throws Exception
	 */
	public static int getIdenticonCode(InetAddress inetAddr) throws Exception {
		if (inetSalt == null)
			throw new Exception(
					"inetSalt must be set prior to retrieving identicon code");

		byte[] ip = inetAddr.getAddress();
		int ipInt = (((ip[0] & 0xFF) << 24) | ((ip[1] & 0xFF) << 16)
				| ((ip[2] & 0xFF) << 8) | (ip[3] & 0xFF))
				& inetMask;
		StringBuilder s = new StringBuilder();
		s.append(ipInt);
		s.append('+');
		s.append(inetSalt);
		MessageDigest md;
		md = MessageDigest.getInstance("SHA1");
		byte[] hashedIp = md.digest(s.toString().getBytes("UTF-8"));
		int code = ((hashedIp[0] & 0xFF) << 24) | ((hashedIp[1] & 0xFF) << 16)
				| ((hashedIp[2] & 0xFF) << 8) | (hashedIp[3] & 0xFF);
		return code;
	}

	/**
	 * Returns identicon code specified as an input parameter or derived from an
	 * IP address.
	 * <p>
	 * This method is a convenience method intended to be used by servlets like
	 * below:
	 * </p>
	 * 
	 * <pre>
	 * int code = IdenticonUtil.getIdenticonCode(request.getParameter(&quot;code&quot;), request
	 * 		.getRemoteAddr());
	 * </pre>
	 * 
	 * @param codeParam
	 *            code parameter, if <code>null</code> remoteAddr parameter
	 *            will be used to determine the value.
	 * @param remoteAddr
	 *            HTTP requester's IP address. Optional if code was specified.
	 * @return the code
	 */
	public static int getIdenticonCode(String codeParam, String remoteAddr) {
		int code = 0;
		try {
			if (codeParam != null) {
				code = Integer.parseInt(codeParam);
			} else {
				code = IdenticonUtil.getIdenticonCode(InetAddress
						.getByName(remoteAddr));
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return code;
	}

	public static int getIdenticonSize(String param) {
		int size = DEFAULT_IDENTICON_SIZE;
		try {
			String sizeParam = param;
			if (sizeParam != null) {
				size = Integer.parseInt(sizeParam);
				if (size < MINIMUM_IDENTICON_SIZE)
					size = MINIMUM_IDENTICON_SIZE;
				else if (size > MAXIMUM_IDENTICON_SIZE)
					size = MAXIMUM_IDENTICON_SIZE;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return size;
	}

	public static String getIdenticonETag(int code, int size, int version) {
		StringBuilder s = new StringBuilder("W/\"");
		s.append(Integer.toHexString(code));
		s.append('@');
		s.append(size);
		s.append('v');
		s.append(version);
		s.append('\"');
		return s.toString();
	}
}
