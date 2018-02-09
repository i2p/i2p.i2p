package i2p.susi.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;

import net.i2p.data.DataHelper;

/**
 * File name encoding methods
 *
 * @since 0.9.34 pulled out of WebMail
 */
public class FilenameUtil {

	/**
	 * Convert the UTF-8 to ASCII suitable for inclusion in a header
	 * and for use as a cross-platform filename.
	 * Replace chars likely to be illegal in filenames,
	 * and non-ASCII chars, with _
	 *
	 * Ref: RFC 6266, RFC 5987, i2psnark Storage.ILLEGAL
	 *
	 * @since 0.9.18
	 */
	public static String sanitizeFilename(String name) {
		name = name.trim();
		StringBuilder buf = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			// illegal filename chars
			if (c <= 32 || c >= 0x7f ||
			    c == '<' || c == '>' || c == ':' || c == '"' ||
			    c == '/' || c == '\\' || c == '|' || c == '?' ||
			    c == '*')
				buf.append('_');
			else
				buf.append(c);
		}
		return buf.toString();
	}

	/**
	 * Encode the UTF-8 suitable for inclusion in a header
	 * as a RFC 5987/6266 filename* value, and for use as a cross-platform filename.
	 * Replace chars likely to be illegal in filenames with _
	 *
	 * Ref: RFC 6266, RFC 5987, i2psnark Storage.ILLEGAL
	 *
	 * This does NOT do multiline, e.g. filename*0* (RFC 2231)
	 *
	 * ref: https://blog.nodemailer.com/2017/01/27/the-mess-that-is-attachment-filenames/
	 * ref: RFC 2231
	 *
	 * @since 0.9.33
	 */
	public static String encodeFilenameRFC5987(String name) {
		name = name.trim();
		StringBuilder buf = new StringBuilder(name.length());
		buf.append("utf-8''");
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			// illegal filename chars
			if (c < 32 || (c >= 0x7f && c <= 0x9f) ||
			    c == '<' || c == '>' || c == ':' || c == '"' ||
			    c == '/' || c == '\\' || c == '|' || c == '?' ||
			    c == '*' ||
			    // unicode newlines
			    c == 0x2028 || c == 0x2029) {
				buf.append('_');
			} else if (c == ' ' || c == '\'' || c == '%' ||          // not in 5987 attr-char
			           c == '(' || c == ')' || c == '@' ||           // 2616 separators
			           c == ',' || c == ';' || c == '[' || c == ']' ||
			           c == '=' || c == '{' || c == '}') {
				// single byte encoding
				buf.append(HexTable.table[c].replace('=', '%'));
			} else if (c < 0x7f) {
				// single byte char, as-is
				buf.append(c);
			} else {
				// multi-byte encoding
				byte[] utf = DataHelper.getUTF8(String.valueOf(c));
				for (int j = 0; j < utf.length; j++) {
					int b = utf[j] & 0xff;
					buf.append(HexTable.table[b].replace('=', '%'));
				}
			}
		}
		return buf.toString();
	}

	/**
	 * Modified from QuotedPrintable.decode()
	 *
	 * @return name on error
	 * @since 0.9.34
	 */
	public static String decodeFilenameRFC5987(String name) {
		int idx = name.indexOf('\'');
		if (idx <= 0)
			return name;
		String enc = name.substring(0, idx).toUpperCase(Locale.US);
		idx = name.indexOf('\'', idx + 1);
		if (idx <= 0)
			return name;
		String n = name.substring(idx + 1);
		StringReader in = new StringReader(n);
		ByteArrayOutputStream out = new ByteArrayOutputStream(n.length());
		try {
			while (true) {
				int c = in.read();
				if (c < 0)
					break;
				if( c == '%' ) {
						int a = in.read();
						if (a < 0) {
							out.write(c);
							break;
						}
						int b = in.read();
						if (b < 0) {
							out.write(c);
							out.write(a);
							break;
						}
						if (((a >= '0' && a <= '9') || (a >= 'A' && a <= 'F') || (a >= 'a' && a <= 'f')) &&
						    ((b >= '0' && b <= '9') || (b >= 'A' && b <= 'F') || (b >= 'a' && b <= 'f'))) {
							if( a >= '0' && a <= '9' )
								a -= '0';
							else if( a >= 'A' && a <= 'F' )
								a = (byte) (a - 'A' + 10);
							else if(a >= 'a' && a <= 'f')
								a = (byte) (a - 'a' + 10);
	
							if( b >= '0' && b <= '9' )
								b -= '0';
							else if( b >= 'A' && b <= 'F' )
								b = (byte) (b - 'A' + 10);
							else if(b >= 'a' && b <= 'f')
								b = (byte) (b - 'a' + 10);
							
							out.write(a*16 + b);
						}
						else if( a == '\r' && b == '\n' ) {
							// ignore, shouldn't happen
						} else {
							// FAIL
							out.write(c);
							out.write(a);
							out.write(b);
						}
				} else {
					// print out everything else literally
					out.write(c);
				}
			}
			return new String(out.toByteArray(), enc);
		} catch (IOException ioe) {
			ioe.printStackTrace();
			return n;
		}
	}

/****
	public static void main(String[] args) {
		String in = "2018年01月25-26日(深圳)";
		String enc = encodeFilenameRFC5987(in);
		String dec = decodeFilenameRFC5987(enc);
		System.out.println("in:  " + in + "\nenc: " + enc + "\ndec: " + dec +
		                   "\nPass? " + in.equals(dec));
	}
****/
}
