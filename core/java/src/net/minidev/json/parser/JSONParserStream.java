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
import static net.minidev.json.parser.ParseException.ERROR_UNEXPECTED_TOKEN;

import java.io.IOException;

/**
 * Parser for JSON text. Please note that JSONParser is NOT thread-safe.
 * 
 * @author Uriel Chemouni uchemouni@gmail.com
 * @see JSONParserInputStream
 * @see JSONParserReader
 */
abstract class JSONParserStream extends JSONParserBase {
	// len
	//
	public JSONParserStream(int permissiveMode) {
		super(permissiveMode);
	}

	protected void readNQString(boolean[] stop) throws IOException {
		sb.clear();
		skipNQString(stop);
		xs = sb.toString().trim();
	}

	protected Object readNumber(boolean[] stop) throws ParseException, IOException {
		sb.clear();
		sb.append(c);// accept first char digit or -
		read();
		skipDigits();

		// Integer digit
		if (c != '.' && c != 'E' && c != 'e') {
			skipSpace();
			if (c >= 0 && c < MAX_STOP && !stop[c] && c != EOI) {
				// convert string
				skipNQString(stop);
				xs = sb.toString().trim();
				if (!acceptNonQuote)
					throw new ParseException(pos, ERROR_UNEXPECTED_TOKEN, xs);
				return xs;
			}
			xs = sb.toString().trim();
			return parseNumber(xs);
		}
		// floating point
		if (c == '.') {
			sb.append(c);
			read();
			skipDigits();
		}
		if (c != 'E' && c != 'e') {
			skipSpace();
			if (c >= 0 && c < MAX_STOP && !stop[c] && c != EOI) {
				// convert string
				skipNQString(stop);
				xs = sb.toString().trim();
				if (!acceptNonQuote)
					throw new ParseException(pos, ERROR_UNEXPECTED_TOKEN, xs);
				return xs;
			}
			xs = sb.toString().trim();
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
				xs = sb.toString().trim();
				if (!acceptNonQuote)
					throw new ParseException(pos, ERROR_UNEXPECTED_TOKEN, xs);
				return xs;
			}
			xs = sb.toString().trim();
			return extractFloat();
		} else {
			skipNQString(stop);
			xs = sb.toString().trim();
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
		sb.clear();
		//
		//
		//
		//
		//
		//
		//
		//
		//
		//
		/* assert (c == '\"' || c == '\'') */
		readString2();
	}

	//
	//
	//
	//
	//
	//
	//
	//
}
