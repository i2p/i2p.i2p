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
/**
 * ParseException explains why and where the error occurs in source JSON text.
 * 
 * @author Uriel Chemouni uchemouni@gmail.com
 */
public class ParseException extends Exception {
	private static final long serialVersionUID = 8879024178584091857L;

	public static final int ERROR_UNEXPECTED_CHAR = 0;
	public static final int ERROR_UNEXPECTED_TOKEN = 1;
	public static final int ERROR_UNEXPECTED_EXCEPTION = 2;
	public static final int ERROR_UNEXPECTED_EOF = 3;
	public static final int ERROR_UNEXPECTED_UNICODE = 4;
	public static final int ERROR_UNEXPECTED_DUPLICATE_KEY = 5;
	public static final int ERROR_UNEXPECTED_LEADING_0 = 6;

	private int errorType;
	private Object unexpectedObject;
	private int position;

	public ParseException(int position, int errorType, Object unexpectedObject) {
		super(toMessage(position, errorType, unexpectedObject));
		this.position = position;
		this.errorType = errorType;
		this.unexpectedObject = unexpectedObject;
	}

	public ParseException(int position, Throwable cause) {
		super(toMessage(position, ERROR_UNEXPECTED_EXCEPTION, cause), cause);
		this.position = position;
		this.errorType = ERROR_UNEXPECTED_EXCEPTION;
		this.unexpectedObject = cause;
	}

	public int getErrorType() {
		return errorType;
	}

	/**
	 * @return The character position (starting with 0) of the input where the
	 *         error occurs.
	 */
	public int getPosition() {
		return position;
	}

	/**
	 * @return One of the following base on the value of errorType:
	 *         ERROR_UNEXPECTED_CHAR java.lang.Character ERROR_UNEXPECTED_TOKEN
	 *         ERROR_UNEXPECTED_EXCEPTION java.lang.Exception
	 */
	public Object getUnexpectedObject() {
		return unexpectedObject;
	}

	private static String toMessage(int position, int errorType, Object unexpectedObject) {
		StringBuilder sb = new StringBuilder();

		if (errorType == ERROR_UNEXPECTED_CHAR) {
			sb.append("Unexpected character (");
			sb.append(unexpectedObject);
			sb.append(") at position ");
			sb.append(position);
			sb.append(".");
		} else if (errorType == ERROR_UNEXPECTED_TOKEN) {
			sb.append("Unexpected token ");
			sb.append(unexpectedObject);
			sb.append(" at position ");
			sb.append(position);
			sb.append(".");
		} else if (errorType == ERROR_UNEXPECTED_EXCEPTION) {
			sb.append("Unexpected exception ");
			sb.append(unexpectedObject);
			sb.append(" occur at position ");
			sb.append(position);
			sb.append(".");
		} else if (errorType == ERROR_UNEXPECTED_EOF) {
			sb.append("Unexpected End Of File position ");
			sb.append(position);
			sb.append(": ");
			sb.append(unexpectedObject);
		} else if (errorType == ERROR_UNEXPECTED_UNICODE) {
			sb.append("Unexpected unicode escape sequence ");
			sb.append(unexpectedObject);
			sb.append(" at position ");
			sb.append(position);
			sb.append(".");
		} else if (errorType == ERROR_UNEXPECTED_DUPLICATE_KEY) {
			sb.append("Unexpected duplicate key:");
			sb.append(unexpectedObject);
			sb.append(" at position ");
			sb.append(position);
			sb.append(".");
		} else if (errorType == ERROR_UNEXPECTED_LEADING_0) {
			sb.append("Unexpected leading 0 in digit for token:");
			sb.append(unexpectedObject);
			sb.append(" at position ");
			sb.append(position);
			sb.append(".");
		} else {
			sb.append("Unkown error at position ");
			sb.append(position);
			sb.append(".");
		}
		return sb.toString();
	}

}
