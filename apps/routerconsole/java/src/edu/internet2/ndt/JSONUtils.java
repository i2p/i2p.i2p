package edu.internet2.ndt;

import org.json.simple.JsonObject;
import org.json.simple.Jsoner;
import java.util.Iterator;
import java.util.Map;

/**
 * Created by Sebastian Malecki on 13.05.14.
 */
public class JSONUtils {

	/**
	 * Function that return value from json object represented by jsontext containing a single message
	 * which is assigned to "msg" key.
	 * @param jsonTxt {String} JSON object
	 * @return {int} obtained value from JSON object
	 */
	public static String getSingleMessage(String jsonTxt) {
		return getValueFromJsonObj(jsonTxt, "msg");
	}

	/**
	 * Function that return value for given key from json object represented by jsontext
	 * @param jsonTxt {String} JSON object
	 * @param key {int} key by which value should be obtained from JSON map
	 * @return {int} obtained value from JSON map
	 */
	public static String getValueFromJsonObj(String jsonTxt, String key) {
		Map json = (Map)Jsoner.deserialize(new String(jsonTxt), (JsonObject)null);
		if (json == null)
			return null;
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
	 * @param jsonTxt {String} JSON object
	 * @param key {String} key by which value should be assigned to JSON map
	 * @param value {String} value for given key
	 * @return {String} json object with added value.
	 */
	public static String addValueToJsonObj(String jsonTxt, String key, String value) {
		JsonObject json = Jsoner.deserialize(new String(jsonTxt), (JsonObject)null);
		if (json == null)
			json = new JsonObject();
		json.put(key, value);

		return json.toJson();
	}

	/**
	 * Function that return json object represented by jsontext and included
	 * single message assigned to "msg" key
	 * @param msg {byte[]} message which should be assigned to json object
	 * @return {byte[]} json object represented by jsontext and encodes into a sequence of bytes
	 */
	public static byte[] createJsonObj(byte[] msg) {
		JsonObject obj = new JsonObject();
		obj.put("msg", new String(msg));

		return obj.toJson().getBytes();
	}

	/**
	 * Function that return json object represented by jsontext and included
	 * single message assigned to "msg" key
	 * @param msg {byte[]} message which should be assigned to json object
	 * @return {byte[]} json object represented by jsontext and encodes into a sequence of bytes
	 * @since 0.9.45
	 */
	public static byte[] createJsonLoginObj(byte[] msg, byte tests) {
		JsonObject obj = new JsonObject();
		obj.put("msg", new String(msg));
		obj.put("tests", Integer.toString(tests & 0xff));
		return obj.toJson().getBytes();
	}
}
