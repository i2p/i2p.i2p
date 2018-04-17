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
import java.io.IOException;

import net.minidev.json.JSONStyle;
import net.minidev.json.JSONValue;

public class ContentHandlerCompressor implements ContentHandler {
	Appendable out;
	JSONStyle compression;

	int[] stack = new int[10];
	int pos;

	// push 0 = < Object
	// push 1 = < Array
	private void push(int type) {
		pos += 2;
		if (pos >= stack.length) {
			int[] tmp = new int[stack.length * 2];
			System.arraycopy(stack, 0, tmp, 0, stack.length);
			stack = tmp;
		}
		stack[pos] = type;
		stack[pos + 1] = 0;
	}

	private boolean isInObject() {
		return stack[pos] == 0;
	}

	private boolean isInArray() {
		return stack[pos] == 1;
	}

	public ContentHandlerCompressor(Appendable out, JSONStyle compression) {
		this.out = out;
		this.compression = compression;
	}

	// @Override JDK 1.5 compatibility change
	public void startJSON() throws ParseException, IOException {
	}

	// @Override JDK 1.5 compatibility change
	public void endJSON() throws ParseException, IOException {
	}

	// @Override JDK 1.5 compatibility change
	public boolean startObject() throws ParseException, IOException {
		if (isInArray() && stack[pos + 1]++ > 0)
			out.append(',');
		out.append('{');
		push(0);
		// stack.add(JsonStructure.newObj());
		return false;
	}

	// @Override JDK 1.5 compatibility change
	public boolean endObject() throws ParseException, IOException {
		out.append('}');
		pos -= 2;
		// stack.pop();
		return false;
	}

	// @Override JDK 1.5 compatibility change
	public boolean startObjectEntry(String key) throws ParseException, IOException {
		if (stack[pos + 1]++ > 0)
			out.append(',');
		if (key == null)
			out.append("null");
		else if (!compression.mustProtectKey(key))
			out.append(key);
		else {
			out.append('"');
			JSONValue.escape(key, out, compression);
			out.append('"');
		}
		out.append(':');
		return false;
	}

	// @Override JDK 1.5 compatibility change
	public boolean endObjectEntry() throws ParseException, IOException {
		return false;
	}

	// @Override JDK 1.5 compatibility change
	public boolean startArray() throws ParseException, IOException {
		if (isInArray() && stack[pos + 1]++ > 0)
			out.append(',');
		out.append('[');
		push(1);
		return false;
	}

	// @Override JDK 1.5 compatibility change
	public boolean endArray() throws ParseException, IOException {
		out.append(']');
		pos -= 2;
		return false;
	}

	// @Override JDK 1.5 compatibility change
	public boolean primitive(Object value) throws ParseException, IOException {
		if (!isInObject() && stack[pos + 1]++ > 0)
			out.append(',');

		if (value instanceof String) {
			compression.writeString(out, (String) value);
		} else
			JSONValue.writeJSONString(value, out, compression);
		return false;
	}
}
