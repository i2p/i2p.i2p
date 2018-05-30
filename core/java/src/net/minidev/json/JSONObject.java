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
import java.util.HashMap;
import java.util.Map;

import net.minidev.json.reader.JsonWriter;

/**
 * A JSON object. Key value pairs are unordered. JSONObject supports
 * java.util.Map interface.
 * 
 * @author FangYidong fangyidong@yahoo.com.cn
 * @author Uriel Chemouni uchemouni@gmail.com
 */
public class JSONObject extends HashMap<String, Object> implements JSONAware, JSONAwareEx, JSONStreamAwareEx {
	private static final long serialVersionUID = -503443796854799292L;

	public JSONObject() {
		super();
	}

	// /**
	// * Allow simply casting to Map<String, XXX>
	// */
	// @SuppressWarnings("unchecked")
	// public <T> T cast() {
	// return (T) this;
	// }

	/**
	 * Escape quotes, \, /, \r, \n, \b, \f, \t and other control characters
	 * (U+0000 through U+001F). It's the same as JSONValue.escape() only for
	 * compatibility here.
	 * 
	 * @see JSONValue#escape(String)
	 */
	public static String escape(String s) {
		return JSONValue.escape(s);
	}

	public static String toJSONString(Map<String, ? extends Object> map) {
		return toJSONString(map, JSONValue.COMPRESSION);
	}

	/**
	 * Convert a map to JSON text. The result is a JSON object. If this map is
	 * also a JSONAware, JSONAware specific behaviours will be omitted at this
	 * top level.
	 * 
	 * @see net.minidev.json.JSONValue#toJSONString(Object)
	 * 
	 * @param map
	 * @return JSON text, or "null" if map is null.
	 */
	public static String toJSONString(Map<String, ? extends Object> map, JSONStyle compression) {
		StringBuilder sb = new StringBuilder();
		try {
			writeJSON(map, sb, compression);
		} catch (IOException e) {
			// can not append on a StringBuilder
		}
		return sb.toString();
	}

	// /**
	// * return a Key:value entry as stream
	// */
	// public static String toString(String key, Object value) {
	// return toString(key, value, JSONValue.COMPRESSION);
	// }

	// /**
	// * return a Key:value entry as stream
	// */
	// public static String toString(String key, Object value, JSONStyle
	// compression) {
	// StringBuilder sb = new StringBuilder();
	// try {
	// writeJSONKV(key, value, sb, compression);
	// } catch (IOException e) {
	// // can not append on a StringBuilder
	// }
	// return sb.toString();
	// }

	/**
	 * Allows creation of a JSONObject from a Map. After that, both the
	 * generated JSONObject and the Map can be modified independently.
	 */
	public JSONObject(Map<String, ?> map) {
		super(map);
	}

	public static void writeJSON(Map<String, Object> map, Appendable out) throws IOException {
		writeJSON(map, out, JSONValue.COMPRESSION);
	}

	/**
	 * Encode a map into JSON text and write it to out. If this map is also a
	 * JSONAware or JSONStreamAware, JSONAware or JSONStreamAware specific
	 * behaviours will be ignored at this top level.
	 * 
	 * @see JSONValue#writeJSONString(Object, Appendable)
	 */
	public static void writeJSON(Map<String, ? extends Object> map, Appendable out, JSONStyle compression)
			throws IOException {
		if (map == null) {
			out.append("null");
			return;
		}
		JsonWriter.JSONMapWriter.writeJSONString(map, out, compression);
	}

	/**
	 * serialize Object as json to an stream
	 */
	public void writeJSONString(Appendable out) throws IOException {
		writeJSON(this, out, JSONValue.COMPRESSION);
	}

	/**
	 * serialize Object as json to an stream
	 */
	public void writeJSONString(Appendable out, JSONStyle compression) throws IOException {
		writeJSON(this, out, compression);
	}

	public void merge(Object o2) {
		merge(this, o2);
	}

	protected static JSONObject merge(JSONObject o1, Object o2) {
		if (o2 == null)
			return o1;
		if (o2 instanceof JSONObject)
			return merge(o1, (JSONObject) o2);
		throw new RuntimeException("JSON megre can not merge JSONObject with " + o2.getClass());
	}

	private static JSONObject merge(JSONObject o1, JSONObject o2) {
		if (o2 == null)
			return o1;
		for (String key : o1.keySet()) {
			Object value1 = o1.get(key);
			Object value2 = o2.get(key);
			if (value2 == null)
				continue;
			if (value1 instanceof JSONArray) {
				o1.put(key, merge((JSONArray) value1, value2));
				continue;
			}
			if (value1 instanceof JSONObject) {
				o1.put(key, merge((JSONObject) value1, value2));
				continue;
			}
			if (value1.equals(value2))
				continue;
			if (value1.getClass().equals(value2.getClass()))
				throw new RuntimeException("JSON merge can not merge two " + value1.getClass().getName()
						+ " Object together");
			throw new RuntimeException("JSON merge can not merge " + value1.getClass().getName() + " with "
					+ value2.getClass().getName());
		}
		for (String key : o2.keySet()) {
			if (o1.containsKey(key))
				continue;
			o1.put(key, o2.get(key));
		}
		return o1;
	}

	protected static JSONArray merge(JSONArray o1, Object o2) {
		if (o2 == null)
			return o1;
		if (o1 instanceof JSONArray)
			return merge(o1, (JSONArray) o2);
		o1.add(o2);
		return o1;
	}

	private static JSONArray merge(JSONArray o1, JSONArray o2) {
		o1.addAll(o2);
		return o1;
	}

	public String toJSONString() {
		return toJSONString(this, JSONValue.COMPRESSION);
	}

	public String toJSONString(JSONStyle compression) {
		return toJSONString(this, compression);
	}

	public String toString(JSONStyle compression) {
		return toJSONString(this, compression);
	}

	public String toString() {
		return toJSONString(this, JSONValue.COMPRESSION);
	}
}
