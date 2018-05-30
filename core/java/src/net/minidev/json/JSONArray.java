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
import java.util.ArrayList;
import java.util.List;

import net.minidev.json.reader.JsonWriter;

/**
 * A JSON array. JSONObject supports java.util.List interface.
 * 
 * @author FangYidong fangyidong@yahoo.com.cn
 * @author Uriel Chemouni uchemouni@gmail.com
 */
public class JSONArray extends ArrayList<Object> implements List<Object>, JSONAwareEx, JSONStreamAwareEx {
	private static final long serialVersionUID = 9106884089231309568L;

	public static String toJSONString(List<? extends Object> list) {
		return toJSONString(list, JSONValue.COMPRESSION);
	}

	/**
	 * Convert a list to JSON text. The result is a JSON array. If this list is
	 * also a JSONAware, JSONAware specific behaviours will be omitted at this
	 * top level.
	 * 
	 * @see net.minidev.json.JSONValue#toJSONString(Object)
	 * 
	 * @param list
	 * @param compression
	 *            Indicate compression level
	 * @return JSON text, or "null" if list is null.
	 */
	public static String toJSONString(List<? extends Object> list, JSONStyle compression) {
		StringBuilder sb = new StringBuilder();
		try {
			writeJSONString(list, sb, compression);
		} catch (IOException e) {
			// Can not append on a string builder
		}
		return sb.toString();
	}

	/**
	 * Encode a list into JSON text and write it to out. If this list is also a
	 * JSONStreamAware or a JSONAware, JSONStreamAware and JSONAware specific
	 * behaviours will be ignored at this top level.
	 * 
	 * @see JSONValue#writeJSONString(Object, Appendable)
	 * 
	 * @param list
	 * @param out
	 */
	public static void writeJSONString(Iterable<? extends Object> list, Appendable out, JSONStyle compression)
			throws IOException {
		if (list == null) {
			out.append("null");
			return;
		}
		JsonWriter.JSONIterableWriter.writeJSONString(list, out, compression);
	}

	public static void writeJSONString(List<? extends Object> list, Appendable out) throws IOException {
		writeJSONString(list, out, JSONValue.COMPRESSION);
	}

	public void merge(Object o2) {
		JSONObject.merge(this, o2);
	}

	/**
	 * Explicitely Serialize Object as JSon String
	 */
	public String toJSONString() {
		return toJSONString(this, JSONValue.COMPRESSION);
	}

	public String toJSONString(JSONStyle compression) {
		return toJSONString(this, compression);
	}

	/**
	 * Override natif toStirng()
	 */
	public String toString() {
		return toJSONString();
	}

	/**
	 * JSONAwareEx inferface
	 * 
	 * @param compression
	 *            compression param
	 */
	public String toString(JSONStyle compression) {
		return toJSONString(compression);
	}

	public void writeJSONString(Appendable out) throws IOException {
		writeJSONString(this, out, JSONValue.COMPRESSION);
	}

	public void writeJSONString(Appendable out, JSONStyle compression) throws IOException {
		writeJSONString(this, out, compression);
	}
}
