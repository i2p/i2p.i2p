package edu.internet2.ndt;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import java.util.Iterator;
import java.util.Map;

/**
 * Created by Sebastian Malecki on 13.05.14.
 */
public class JSONUtils {

	/**
	 * Function that return value from json object represented by jsontext containing a single message
	 * which is assigned to "msg" key.
	 * @param {String} JSON object
	 * @return {int} obtained value from JSON object
	 */
	public static String getSingleMessage(String jsonTxt) {
		return getValueFromJsonObj(jsonTxt, "msg");
	}

	/**
	 * Function that return value for given key from json object represented by jsontext
	 * @param {String} JSON object
	 * @param {int} key by which value should be obtained from JSON map
	 * @return {int} obtained value from JSON map
	 */
	public static String getValueFromJsonObj(String jsonTxt, String key) {
		JSONValue jsonParser = new JSONValue();
		Map json = (Map)jsonParser.parse(new String(jsonTxt));
		Iterator iter = json.entrySet().iterator();
		while(iter.hasNext()){
			Map.Entry entry = (Map.Entry)iter.next();
			if (entry.getKey().equals(key)) {
				return entry.getValue().toString();
			}
		}
		return null;
	}

	/**
	 * Function that add new value to JSON map
	 * @param {String} JSON object
	 * @param {String} key by which value should be assigned to JSON map
	 * @param {String} value for given key
	 * @return {String} json object with added value.
	 */
	public static String addValueToJsonObj(String jsonTxt, String key, String value) {
		JSONValue jsonParser = new JSONValue();
		JSONObject json = (JSONObject)jsonParser.parse(new String(jsonTxt));
		json.put(key, value);

		return json.toJSONString();
	}

	/**
	 * Function that return json object represented by jsontext and included
	 * single message assigned to "msg" key
	 * @param {byte[]} message which should be assigned to json object
	 * @return {byte[]} json object represented by jsontext and encodes into a sequence of bytes
	 */
	public static byte[] createJsonObj(byte[] msg) {
		JSONObject obj = new JSONObject();
		obj.put("msg", new String(msg));

		return obj.toJSONString().getBytes();
	}
}
