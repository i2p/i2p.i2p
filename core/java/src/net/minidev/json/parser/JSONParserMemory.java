package net.minidev.json.parser;

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
import static net.minidev.json.parser.ParseException.ERROR_UNEXPECTED_CHAR;
import static net.minidev.json.parser.ParseException.ERROR_UNEXPECTED_EOF;
import static net.minidev.json.parser.ParseException.ERROR_UNEXPECTED_TOKEN;

import java.io.IOException;

/**
 * Parser for JSON text. Please note that JSONParser is NOT thread-safe.
 * 
 * @author Uriel Chemouni uchemouni@gmail.com
 * @see JSONParserString
 * @see JSONParserByteArray
 */
abstract class JSONParserMemory extends JSONParserBase {
	protected int len;

	public JSONParserMemory(int permissiveMode) {
		super(permissiveMode);
	}

	protected void readNQString(boolean[] stop) throws IOException {
		int start = pos;
		skipNQString(stop);
		extractStringTrim(start, pos);
	}

	protected Object readNumber(boolean[] stop) throws ParseException, IOException {
		int start = pos;
		// accept first char digit or -
		read();
		skipDigits();

		// Integer digit
		if (c != '.' && c != 'E' && c != 'e') {
			skipSpace();
			if (c >= 0 && c < MAX_STOP && !stop[c] && c != EOI) {
				// convert string
				skipNQString(stop);
				extractStringTrim(start, pos);
				if (!acceptNonQuote)
					throw new ParseException(pos, ERROR_UNEXPECTED_TOKEN, xs);
				return xs;
			}
			extractStringTrim(start, pos);
			return parseNumber(xs);
		}
		// floating point
		if (c == '.') {
			//
			read();
			skipDigits();
		}
		if (c != 'E' && c != 'e') {
			skipSpace();
			if (c >= 0 && c < MAX_STOP && !stop[c] && c != EOI) {
				// convert string
				skipNQString(stop);
				extractStringTrim(start, pos);
				if (!acceptNonQuote)
					throw new ParseException(pos, ERROR_UNEXPECTED_TOKEN, xs);
				return xs;
			}
			extractStringTrim(start, pos);
			return extractFloat();
		}
		sb.append('E');
		read();
		if (c == '+' || c == '-' || c >= '0' && c <= '9') {
			sb.append(c);
			read(); // skip first char
			skipDigits();
			skipSpace();
			if (c >= 0 && c < MAX_STOP && !stop[c] && c != EOI) {
				// convert string
				skipNQString(stop);
				extractStringTrim(start, pos);
				if (!acceptNonQuote)
					throw new ParseException(pos, ERROR_UNEXPECTED_TOKEN, xs);
				return xs;
			}
			extractStringTrim(start, pos);
			return extractFloat();
		} else {
			skipNQString(stop);
			extractStringTrim(start, pos);
			if (!acceptNonQuote)
				throw new ParseException(pos, ERROR_UNEXPECTED_TOKEN, xs);
			if (!acceptLeadinZero)
				checkLeadinZero();
			return xs;
		}
		// throw new ParseException(pos - 1, ERROR_UNEXPECTED_CHAR, null);
	}

	protected void readString() throws ParseException, IOException {
		if (!acceptSimpleQuote && c == '\'') {
			if (acceptNonQuote) {
				readNQString(stopAll);
				return;
			}
			throw new ParseException(pos, ERROR_UNEXPECTED_CHAR, c);
		}
		int tmpP = indexOf(c, pos + 1);
		if (tmpP == -1)
			throw new ParseException(len, ERROR_UNEXPECTED_EOF, null);
		extractString(pos + 1, tmpP);
		if (xs.indexOf('\\') == -1) {
			checkControleChar();
			pos = tmpP;
			read();
			// handler.primitive(tmp);
			return;
		}
		sb.clear();
		readString2();
	}

	abstract protected void extractString(int start, int stop);

	abstract protected int indexOf(char c, int pos);

	protected void extractStringTrim(int start, int stop) {
		extractString(start, stop);
		xs = xs.trim();
	}
}
