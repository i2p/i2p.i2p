package net.minidev.json;

/*
 *    Copyright 2011 JSON-SMART authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.io.IOException;

/**
 * protected class used to stored Internal methods
 * 
 * @author Uriel Chemouni uchemouni@gmail.com
 */
class JStylerObj {

	public final static MPSimple MP_SIMPLE = new MPSimple();
	public final static MPTrue MP_TRUE = new MPTrue();
	public final static MPAgressive MP_AGGRESIVE = new MPAgressive();

	public final static EscapeLT ESCAPE_LT = new EscapeLT();
	public final static Escape4Web ESCAPE4Web = new Escape4Web();

	public static interface MustProtect {
		public boolean mustBeProtect(String s);
	}

	private static class MPTrue implements MustProtect {
		public boolean mustBeProtect(String s) {
			return true;
		}
	}

	private static class MPSimple implements MustProtect {
		/**
		 * can a String can be store without enclosing quotes. ie: should not
		 * contain any special json char
		 * 
		 * @param s
		 * @return
		 */
		public boolean mustBeProtect(String s) {
			if (s == null)
				return false;
			int len = s.length();
			if (len == 0)
				return true;
			if (s.trim() != s)
				return true;

			char ch = s.charAt(0);
			if (ch >= '0' && ch <= '9' || ch == '-')
				return true;

			for (int i = 0; i < len; i++) {
				ch = s.charAt(i);
				if (isSpace(ch))
					return true;
				if (isSpecial(ch))
					return true;
				if (isSpecialChar(ch))
					return true;
				if (isUnicode(ch))
					return true;
			}
			// keyword check
			if (isKeyword(s))
				return true;
			return false;
		}
	}

	private static class MPAgressive implements MustProtect {
		public boolean mustBeProtect(final String s) {
			if (s == null)
				return false;
			int len = s.length();
			// protect empty String
			if (len == 0)
				return true;
			
			// protect trimable String
			if (s.trim() != s)
				return true;

			// json special char
			char ch = s.charAt(0);
			if (isSpecial(ch) || isUnicode(ch))
				return true;

			for (int i = 1; i < len; i++) {
				ch = s.charAt(i);
				if (isSpecialClose(ch) || isUnicode(ch))
					return true;
			}
			// keyWord must be protect
			if (isKeyword(s))
				return true;
			// Digit like text must be protect
			ch = s.charAt(0);
			// only test String if First Ch is a digit
			if (ch >= '0' && ch <= '9' || ch == '-') {
				int p = 1;
				// skip first digits
				for (; p < len; p++) {
					ch = s.charAt(p);
					if (ch < '0' || ch > '9')
						break;
				}
				// int/long
				if (p == len)
					return true;
				// Floating point
				if (ch == '.') {
					p++;
				}
				// Skip digits
				for (; p < len; p++) {
					ch = s.charAt(p);
					if (ch < '0' || ch > '9')
						break;
				}
				if (p == len)
					return true; // can be read as an floating number
				// Double
				if (ch == 'E' || ch == 'e') {
					p++;
					if (p == len) // no power data not a digits
						return false;
					ch = s.charAt(p);
					if (ch == '+' || ch == '-')	{
						p++;
						ch = s.charAt(p);
					}
				}
				if (p == len) // no power data => not a digit
					return false;
				
				for (; p < len; p++) {
					ch = s.charAt(p);
					if (ch < '0' || ch > '9')
						break;
				}
				// floating point With power of data.
				if (p == len)
					return true;
				return false;
			}
			return false;
		}
	}

	public static boolean isSpace(char c) {
		return (c == '\r' || c == '\n' || c == '\t' || c == ' ');
	}

	public static boolean isSpecialChar(char c) {
		return (c == '\b' || c == '\f' || c == '\n');
	}

	public static boolean isSpecialOpen(char c) {
		return (c == '{' || c == '[' || c == ',' || c == ':');
	}

	public static boolean isSpecialClose(char c) {
		return (c == '}' || c == ']' || c == ',' || c == ':');
	}

	public static boolean isSpecial(char c) {
		return (c == '{' || c == '[' || c == ',' || c == '}' || c == ']' || c == ':' || c == '\'' || c == '"');
	}

	public static boolean isUnicode(char c) {
		return ((c >= '\u0000' && c <= '\u001F') || (c >= '\u007F' && c <= '\u009F') || (c >= '\u2000' && c <= '\u20FF'));
	}

	public static boolean isKeyword(String s) {
		if (s.length() < 3)
			return false;
		char c = s.charAt(0);
		if (c == 'n')
			return s.equals("null");
		if (c == 't')
			return s.equals("true");
		if (c == 'f')
			return s.equals("false");
		if (c == 'N')
			return s.equals("NaN");
		return false;
	}

	public static interface StringProtector {
		public void escape(String s, Appendable out);
	}

	private static class EscapeLT implements StringProtector {
		/**
		 * Escape special chars form String except /
		 * 
		 * @param s
		 *            - Must not be null.
		 * @param out
		 */
		public void escape(String s, Appendable out) {
			try {
				int len = s.length();
				for (int i = 0; i < len; i++) {
					char ch = s.charAt(i);
					switch (ch) {
					case '"':
						out.append("\\\"");
						break;
					case '\\':
						out.append("\\\\");
						break;
					case '\b':
						out.append("\\b");
						break;
					case '\f':
						out.append("\\f");
						break;
					case '\n':
						out.append("\\n");
						break;
					case '\r':
						out.append("\\r");
						break;
					case '\t':
						out.append("\\t");
						break;
					default:
						// Reference:
						// http://www.unicode.org/versions/Unicode5.1.0/
						if ((ch >= '\u0000' && ch <= '\u001F') || (ch >= '\u007F' && ch <= '\u009F')
								|| (ch >= '\u2000' && ch <= '\u20FF')) {
							out.append("\\u");
							String hex = "0123456789ABCDEF";
							out.append(hex.charAt(ch >> 12 & 0x000F));
							out.append(hex.charAt(ch >> 8 & 0x000F));
							out.append(hex.charAt(ch >> 4 & 0x000F));
							out.append(hex.charAt(ch >> 0 & 0x000F));
						} else {
							out.append(ch);
						}
					}
				}
			} catch (IOException e) {
				throw new RuntimeException("Impossible Exeption");
			}
		}
	}

	private static class Escape4Web implements StringProtector {

		/**
		 * Escape special chars form String including /
		 * 
		 * @param s
		 *            - Must not be null.
		 * @param sb
		 */
		public void escape(String s, Appendable sb) {
			try {
				int len = s.length();
				for (int i = 0; i < len; i++) {
					char ch = s.charAt(i);
					switch (ch) {
					case '"':
						sb.append("\\\"");
						break;
					case '\\':
						sb.append("\\\\");
						break;
					case '\b':
						sb.append("\\b");
						break;
					case '\f':
						sb.append("\\f");
						break;
					case '\n':
						sb.append("\\n");
						break;
					case '\r':
						sb.append("\\r");
						break;
					case '\t':
						sb.append("\\t");
						break;
					case '/':
						sb.append("\\/");
						break;
					default:
						// Reference:
						// http://www.unicode.org/versions/Unicode5.1.0/
						if ((ch >= '\u0000' && ch <= '\u001F') || (ch >= '\u007F' && ch <= '\u009F')
								|| (ch >= '\u2000' && ch <= '\u20FF')) {
							sb.append("\\u");
							String hex = "0123456789ABCDEF";
							sb.append(hex.charAt(ch >> 12 & 0x0F));
							sb.append(hex.charAt(ch >> 8 & 0x0F));
							sb.append(hex.charAt(ch >> 4 & 0x0F));
							sb.append(hex.charAt(ch >> 0 & 0x0F));
						} else {
							sb.append(ch);
						}
					}
				}
			} catch (IOException e) {
				throw new RuntimeException("Impossible Error");
			}
		}
	}
}
