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

	public static String getFirstVersionNumber(String a_userAgent,
			int a_position, int numDigits) {
		String ver = getVersionNumber(a_userAgent, a_position);
		if (ver == null)
			return "";
		int i = 0;
		String res = "";
		while (i < ver.length() && i < numDigits) {
			res += String.valueOf(ver.charAt(i));
			i++;
		}
		return res;
	}

	public static String getVersionNumber(String a_userAgent, int a_position) {
		if (a_position < 0)
			return "";
		StringBuffer res = new StringBuffer();
		int status = 0;

		while (a_position < a_userAgent.length()) {
			char c = a_userAgent.charAt(a_position);
			switch (status) {
			case 0: // <SPAN class="codecomment"> No valid digits encountered
					// yet</span>
				if (c == ' ' || c == '/')
					break;
				if (c == ';' || c == ')')
					return "";
				status = 1;
			case 1: // <SPAN class="codecomment"> Version number in
					// progress</span>
				if (c == ';' || c == '/' || c == ')' || c == '(' || c == '[')
					return res.toString().trim();
				if (c == ' ')
					status = 2;
				res.append(c);
				break;
			case 2: // <SPAN class="codecomment"> Space encountered - Might need
					// to end the parsing</span>
				if ((Character.isLetter(c) && Character.isLowerCase(c))
						|| Character.isDigit(c)) {
					res.append(c);
					status = 1;
				} else
					return res.toString().trim();
				break;
			}
			a_position++;
		}
		return res.toString().trim();
	}

	public static String[] getArray(String a, String b, String c) {
		String[] res = new String[3];
		res[0] = a;
		res[1] = b;
		res[2] = c;
		return res;
	}

	public static String[] getBotName(String userAgent) {
		userAgent = userAgent.toLowerCase();
		int pos = 0;
		String res = null;
		if ((pos = userAgent.indexOf("help.yahoo.com/")) > -1) {
			res = "Yahoo";
			pos += 7;
		} else if ((pos = userAgent.indexOf("google/")) > -1) {
			res = "Google";
			pos += 7;
		} else if ((pos = userAgent.indexOf("msnbot/")) > -1) {
			res = "MSNBot";
			pos += 7;
		} else if ((pos = userAgent.indexOf("googlebot/")) > -1) {
			res = "Google";
			pos += 10;
		} else if ((pos = userAgent.indexOf("webcrawler/")) > -1) {
			res = "WebCrawler";
			pos += 11;
		} else if ((pos = userAgent.indexOf("inktomi")) > -1) {
			// <SPAN class="codecomment"> The following two bots don't have any
			// version number in their User-Agent strings.</span>
			res = "Inktomi";
			pos = -1;
		} else if ((pos = userAgent.indexOf("teoma")) > -1) {
			res = "Teoma";
			pos = -1;
		}
		if (res == null)
			return null;
		return getArray(res, res, res + getVersionNumber(userAgent, pos));
	}

	public static String[] getOS(String userAgent) {
		if (getBotName(userAgent) != null)
			return getArray("Bot", "Bot", "Bot");
		String[] res = null;
		int pos;
		if ((pos = userAgent.indexOf("Windows-NT")) > -1) {
			res = getArray("Win", "WinNT",
					"Win" + getVersionNumber(userAgent, pos + 8));
		} else if (userAgent.indexOf("Windows NT") > -1) {
			// <SPAN class="codecomment"> The different versions of Windows NT
			// are decoded in the verbosity level 2</span>
			// <SPAN class="codecomment"> ie: Windows NT 5.1 = Windows XP</span>
			if ((pos = userAgent.indexOf("Windows NT 5.1")) > -1) {
				res = getArray("Win", "WinXP",
						"Win" + getVersionNumber(userAgent, pos + 7));
			} else if ((pos = userAgent.indexOf("Windows NT 6.0")) > -1) {
				res = getArray("Win", "Vista",
						"Vista" + getVersionNumber(userAgent, pos + 7));
			} else if ((pos = userAgent.indexOf("Windows NT 6.1")) > -1) {
				res = getArray("Win", "Seven",
						"Seven " + getVersionNumber(userAgent, pos + 7));
			} else if ((pos = userAgent.indexOf("Windows NT 5.0")) > -1) {
				res = getArray("Win", "Win2000",
						"Win" + getVersionNumber(userAgent, pos + 7));
			} else if ((pos = userAgent.indexOf("Windows NT 5.2")) > -1) {
				res = getArray("Win", "Win2003",
						"Win" + getVersionNumber(userAgent, pos + 7));
			} else if ((pos = userAgent.indexOf("Windows NT 4.0")) > -1) {
				res = getArray("Win", "WinNT4",
						"Win" + getVersionNumber(userAgent, pos + 7));
			} else if ((pos = userAgent.indexOf("Windows NT)")) > -1) {
				res = getArray("Win", "WinNT", "WinNT");
			} else if ((pos = userAgent.indexOf("Windows NT;")) > -1) {
				res = getArray("Win", "WinNT", "WinNT");
			} else {
				res = getArray("Win", "WinNT?", "WinNT?");
			}
		} else if (userAgent.indexOf("Win") > -1) {
			if (userAgent.indexOf("Windows") > -1) {
				if ((pos = userAgent.indexOf("Windows 98")) > -1) {
					res = getArray("Win", "Win98",
							"Win" + getVersionNumber(userAgent, pos + 7));
				} else if ((pos = userAgent.indexOf("Windows_98")) > -1) {
					res = getArray("Win", "Win98",
							"Win" + getVersionNumber(userAgent, pos + 8));
				} else if ((pos = userAgent.indexOf("Windows 2000")) > -1) {
					res = getArray("Win", "Win2000",
							"Win" + getVersionNumber(userAgent, pos + 7));
				} else if ((pos = userAgent.indexOf("Windows 95")) > -1) {
					res = getArray("Win", "Win95",
							"Win" + getVersionNumber(userAgent, pos + 7));
				} else if ((pos = userAgent.indexOf("Windows 9x")) > -1) {
					res = getArray("Win", "Win9x",
							"Win" + getVersionNumber(userAgent, pos + 7));
				} else if ((pos = userAgent.indexOf("Windows ME")) > -1) {
					res = getArray("Win", "WinME",
							"Win" + getVersionNumber(userAgent, pos + 7));
				} else if ((pos = userAgent.indexOf("Windows CE;")) > -1) {
					res = getArray("Win", "WinCE", "WinCE");
				} else if ((pos = userAgent.indexOf("Windows 3.1")) > -1) {
					res = getArray("Win", "Win31",
							"Win" + getVersionNumber(userAgent, pos + 7));
				}
				// <SPAN class="codecomment"> If no version was found, rely on
				// the following code to detect "WinXX"</span>
				// <SPAN class="codecomment"> As some User-Agents include two
				// references to Windows</span>
				// <SPAN class="codecomment"> Ex: Mozilla/5.0 (Windows; U;
				// Win98; en-US; rv:1.5)</span>
			}
			if (res == null) {
				if ((pos = userAgent.indexOf("Win98")) > -1) {
					res = getArray("Win", "Win98",
							"Win" + getVersionNumber(userAgent, pos + 3));
				} else if ((pos = userAgent.indexOf("Win31")) > -1) {
					res = getArray("Win", "Win31",
							"Win" + getVersionNumber(userAgent, pos + 3));
				} else if ((pos = userAgent.indexOf("Win95")) > -1) {
					res = getArray("Win", "Win95",
							"Win" + getVersionNumber(userAgent, pos + 3));
				} else if ((pos = userAgent.indexOf("Win 9x")) > -1) {
					res = getArray("Win", "Win9x",
							"Win" + getVersionNumber(userAgent, pos + 3));
				} else if ((pos = userAgent.indexOf("WinNT4.0")) > -1) {
					res = getArray("Win", "WinNT4",
							"Win" + getVersionNumber(userAgent, pos + 3));
				} else if ((pos = userAgent.indexOf("WinNT")) > -1) {
					res = getArray("Win", "WinNT",
							"Win" + getVersionNumber(userAgent, pos + 3));
				}
			}
			if (res == null) {
				if ((pos = userAgent.indexOf("Windows")) > -1) {
					res = getArray("Win", "Win?",
							"Win?" + getVersionNumber(userAgent, pos + 7));
				} else if ((pos = userAgent.indexOf("Win")) > -1) {
					res = getArray("Win", "Win?",
							"Win?" + getVersionNumber(userAgent, pos + 3));
				} else
					// <SPAN class="codecomment"> Should not happen at this
					// point</span>
					res = getArray("Win", "Win?", "Win?");
			}
		} else if ((pos = userAgent.indexOf("Mac OS X")) > -1) {
			if ((userAgent.indexOf("iPhone")) > -1) {
				pos = userAgent.indexOf("iPhone OS");
				if ((userAgent.indexOf("iPod")) > -1) {
					res = getArray(
							"iOS",
							"iOS-iPod",
							"iOS-iPod "
									+ ((pos < 0) ? "" : getVersionNumber(
											userAgent, pos + 9)));
				} else {
					res = getArray(
							"iOS",
							"iOS-iPhone",
							"iOS-iPhone "
									+ ((pos < 0) ? "" : getVersionNumber(
											userAgent, pos + 9)));
				}
			} else if ((userAgent.indexOf("iPad")) > -1) {
				pos = userAgent.indexOf("CPU OS");
				res = getArray("iOS", "iOS-iPad", "iOS-iPad "
						+ ((pos < 0) ? ""
								: getVersionNumber(userAgent, pos + 6)));
			} else
				res = getArray("Mac", "MacOSX",
						"MacOS " + getVersionNumber(userAgent, pos + 8));
		} else if ((pos = userAgent.indexOf("Android")) > -1) {
			res = getArray("Linux", "Android",
					"Android " + getVersionNumber(userAgent, pos + 8));
		} else if ((pos = userAgent.indexOf("Mac_PowerPC")) > -1) {
			res = getArray("Mac", "MacPPC",
					"MacOS " + getVersionNumber(userAgent, pos + 3));
		} else if ((pos = userAgent.indexOf("Macintosh")) > -1) {
			if (userAgent.indexOf("PPC") > -1)
				res = getArray("Mac", "MacPPC", "Mac PPC");
			else
				res = getArray("Mac?", "Mac?", "MacOS?");
		} else if ((pos = userAgent.indexOf("FreeBSD")) > -1) {
			res = getArray("*BSD", "*BSD FreeBSD", "FreeBSD "
					+ getVersionNumber(userAgent, pos + 7));
		} else if ((pos = userAgent.indexOf("OpenBSD")) > -1) {
			res = getArray("*BSD", "*BSD OpenBSD", "OpenBSD "
					+ getVersionNumber(userAgent, pos + 7));
		} else if ((pos = userAgent.indexOf("Linux")) > -1) {
			String detail = "Linux " + getVersionNumber(userAgent, pos + 5);
			String med = "Linux";
			if ((pos = userAgent.indexOf("Ubuntu/")) > -1) {
				detail = "Ubuntu " + getVersionNumber(userAgent, pos + 7);
				med += " Ubuntu";
			}
			res = getArray("Linux", med, detail);
		} else if ((pos = userAgent.indexOf("CentOS")) > -1) {
			res = getArray("Linux", "Linux CentOS", "CentOS");
		} else if ((pos = userAgent.indexOf("NetBSD")) > -1) {
			res = getArray("*BSD", "*BSD NetBSD",
					"NetBSD " + getVersionNumber(userAgent, pos + 6));
		} else if ((pos = userAgent.indexOf("Unix")) > -1) {
			res = getArray("Linux", "Linux",
					"Linux " + getVersionNumber(userAgent, pos + 4));
		} else if ((pos = userAgent.indexOf("SunOS")) > -1) {
			res = getArray("Unix", "SunOS",
					"SunOS" + getVersionNumber(userAgent, pos + 5));
		} else if ((pos = userAgent.indexOf("IRIX")) > -1) {
			res = getArray("Unix", "IRIX",
					"IRIX" + getVersionNumber(userAgent, pos + 4));
		} else if ((pos = userAgent.indexOf("SonyEricsson")) > -1) {
			res = getArray("SonyEricsson", "SonyEricsson", "SonyEricsson"
					+ getVersionNumber(userAgent, pos + 12));
		} else if ((pos = userAgent.indexOf("Nokia")) > -1) {
			res = getArray("Nokia", "Nokia",
					"Nokia" + getVersionNumber(userAgent, pos + 5));
		} else if ((pos = userAgent.indexOf("BlackBerry")) > -1) {
			res = getArray("BlackBerry", "BlackBerry", "BlackBerry"
					+ getVersionNumber(userAgent, pos + 10));
		} else if ((pos = userAgent.indexOf("SymbianOS")) > -1) {
			res = getArray("SymbianOS", "SymbianOS", "SymbianOS"
					+ getVersionNumber(userAgent, pos + 10));
		} else if ((pos = userAgent.indexOf("BeOS")) > -1) {
			res = getArray("BeOS", "BeOS", "BeOS");
		} else if ((pos = userAgent.indexOf("Nintendo Wii")) > -1) {
			res = getArray("Nintendo Wii", "Nintendo Wii", "Nintendo Wii"
					+ getVersionNumber(userAgent, pos + 10));
		} else if ((pos = userAgent.indexOf("J2ME/MIDP")) > -1) {
			res = getArray("Java", "J2ME", "J2ME/MIDP");
		} else
			res = getArray("?", "?", "?");
		return res;
	}

	public static String[] getBrowser(String userAgent) {
		if (userAgent == null) {
			return getArray("?", "?", "?");
		}
		String[] botName;
		if ((botName = getBotName(userAgent)) != null)
			return botName;
		String[] res = null;
		int pos;
		if ((pos = userAgent.indexOf("Lotus-Notes/")) > -1) {
			res = getArray("LotusNotes", "LotusNotes", "LotusNotes"
					+ getVersionNumber(userAgent, pos + 12));
		} else if ((pos = userAgent.indexOf("Opera")) > -1) {
			String ver = getVersionNumber(userAgent, pos + 5);
			res = getArray("Opera",
					"Opera" + getFirstVersionNumber(userAgent, pos + 5, 1),
					"Opera" + ver);
			if ((pos = userAgent.indexOf("Opera Mini/")) > -1) {
				String ver2 = getVersionNumber(userAgent, pos + 11);
				res = getArray("Opera", "Opera Mini", "Opera Mini " + ver2);
			} else if ((pos = userAgent.indexOf("Opera Mobi/")) > -1) {
				String ver2 = getVersionNumber(userAgent, pos + 11);
				res = getArray("Opera", "Opera Mobi", "Opera Mobi " + ver2);
			}
		} else if (userAgent.indexOf("MSIE") > -1) {
			if ((pos = userAgent.indexOf("MSIE 6.0")) > -1) {
				res = getArray("MSIE", "MSIE6",
						"MSIE" + getVersionNumber(userAgent, pos + 4));
			} else if ((pos = userAgent.indexOf("MSIE 5.0")) > -1) {
				res = getArray("MSIE", "MSIE5",
						"MSIE" + getVersionNumber(userAgent, pos + 4));
			} else if ((pos = userAgent.indexOf("MSIE 5.5")) > -1) {
				res = getArray("MSIE", "MSIE5.5",
						"MSIE" + getVersionNumber(userAgent, pos + 4));
			} else if ((pos = userAgent.indexOf("MSIE 5.")) > -1) {
				res = getArray("MSIE", "MSIE5.x",
						"MSIE" + getVersionNumber(userAgent, pos + 4));
			} else if ((pos = userAgent.indexOf("MSIE 4")) > -1) {
				res = getArray("MSIE", "MSIE4",
						"MSIE" + getVersionNumber(userAgent, pos + 4));
			} else if ((pos = userAgent.indexOf("MSIE 7")) > -1
					&& userAgent.indexOf("Trident/4.0") < 0) {
				res = getArray("MSIE", "MSIE7",
						"MSIE" + getVersionNumber(userAgent, pos + 4));
			} else if ((pos = userAgent.indexOf("MSIE 8")) > -1
					|| userAgent.indexOf("Trident/4.0") > -1) {
				res = getArray("MSIE", "MSIE8",
						"MSIE" + getVersionNumber(userAgent, pos + 4));
			} else if ((pos = userAgent.indexOf("MSIE 9")) > -1
					|| userAgent.indexOf("Trident/4.0") > -1) {
				res = getArray("MSIE", "MSIE9",
						"MSIE" + getVersionNumber(userAgent, pos + 4));
			} else
				res = getArray(
						"MSIE",
						"MSIE?",
						"MSIE?"
								+ getVersionNumber(userAgent,
										userAgent.indexOf("MSIE") + 4));
		} else if ((pos = userAgent.indexOf("Gecko/")) > -1) {
			res = getArray("Gecko", "Gecko",
					"Gecko" + getFirstVersionNumber(userAgent, pos + 5, 4));
			if ((pos = userAgent.indexOf("Camino/")) > -1) {
				res[1] += "(Camino)";
				res[2] += "(Camino" + getVersionNumber(userAgent, pos + 7)
						+ ")";
			} else if ((pos = userAgent.indexOf("Chimera/")) > -1) {
				res[1] += "(Chimera)";
				res[2] += "(Chimera" + getVersionNumber(userAgent, pos + 8)
						+ ")";
			} else if ((pos = userAgent.indexOf("Firebird/")) > -1) {
				res[1] += "(Firebird)";
				res[2] += "(Firebird" + getVersionNumber(userAgent, pos + 9)
						+ ")";
			} else if ((pos = userAgent.indexOf("Phoenix/")) > -1) {
				res[1] += "(Phoenix)";
				res[2] += "(Phoenix" + getVersionNumber(userAgent, pos + 8)
						+ ")";
			} else if ((pos = userAgent.indexOf("Galeon/")) > -1) {
				res[1] += "(Galeon)";
				res[2] += "(Galeon" + getVersionNumber(userAgent, pos + 7)
						+ ")";
			} else if ((pos = userAgent.indexOf("Firefox/")) > -1) {
				res[1] += "(Firefox)";
				res[2] += "(Firefox" + getVersionNumber(userAgent, pos + 8)
						+ ")";
			} else if ((pos = userAgent.indexOf("Netscape/")) > -1) {
				if ((pos = userAgent.indexOf("Netscape/6")) > -1) {
					res[1] += "(NS6)";
					res[2] += "(NS" + getVersionNumber(userAgent, pos + 9)
							+ ")";
				} else if ((pos = userAgent.indexOf("Netscape/7")) > -1) {
					res[1] += "(NS7)";
					res[2] += "(NS" + getVersionNumber(userAgent, pos + 9)
							+ ")";
				} else if ((pos = userAgent.indexOf("Netscape/8")) > -1) {
					res[1] += "(NS8)";
					res[2] += "(NS" + getVersionNumber(userAgent, pos + 9)
							+ ")";
				} else if ((pos = userAgent.indexOf("Netscape/9")) > -1) {
					res[1] += "(NS9)";
					res[2] += "(NS" + getVersionNumber(userAgent, pos + 9)
							+ ")";
				} else {
					res[1] += "(NS?)";
					res[2] += "(NS?"
							+ getVersionNumber(userAgent,
									userAgent.indexOf("Netscape/") + 9) + ")";
				}
			}
		} else if ((pos = userAgent.indexOf("Netscape/")) > -1) {
			if ((pos = userAgent.indexOf("Netscape/4")) > -1) {
				res = getArray("NS", "NS4",
						"NS" + getVersionNumber(userAgent, pos + 9));
			} else
				res = getArray("NS", "NS?",
						"NS?" + getVersionNumber(userAgent, pos + 9));
		} else if ((pos = userAgent.indexOf("Chrome/")) > -1) {
			res = getArray("KHTML", "KHTML(Chrome)", "KHTML(Chrome"
					+ getVersionNumber(userAgent, pos + 6) + ")");
		} else if ((pos = userAgent.indexOf("Safari/")) > -1) {
			res = getArray("KHTML", "KHTML(Safari)", "KHTML(Safari"
					+ getVersionNumber(userAgent, pos + 6) + ")");
		} else if ((pos = userAgent.indexOf("Konqueror/")) > -1) {
			res = getArray("KHTML", "KHTML(Konqueror)", "KHTML(Konqueror"
					+ getVersionNumber(userAgent, pos + 9) + ")");
		} else if ((pos = userAgent.indexOf("KHTML")) > -1) {
			res = getArray("KHTML", "KHTML?",
					"KHTML?(" + getVersionNumber(userAgent, pos + 5) + ")");
		} else if ((pos = userAgent.indexOf("NetFront")) > -1) {
			res = getArray("NetFront", "NetFront", "NetFront "
					+ getVersionNumber(userAgent, pos + 8));
		} else if ((pos = userAgent.indexOf("BlackBerry")) > -1) {
			pos = userAgent.indexOf("/", pos + 2);
			res = getArray("BlackBerry", "BlackBerry", "BlackBerry"
					+ getVersionNumber(userAgent, pos + 1));
		} else if (userAgent.indexOf("Mozilla/4.") == 0
				&& userAgent.indexOf("Mozilla/4.0") < 0
				&& userAgent.indexOf("Mozilla/4.5 ") < 0) {
			// <SPAN class="codecomment"> We will interpret Mozilla/4.x as
			// Netscape Communicator is and only if x</span>
			// <SPAN class="codecomment"> is not 0 or 5</span>
			res = getArray("Communicator", "Communicator", "Communicator"
					+ getVersionNumber(userAgent, pos + 8));
		} else
			return getArray("?", "?", "?");
		return res;
	}
}
