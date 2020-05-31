package com.thetransactioncompany.jsonrpc2;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.simple.JsonObject;


/**
 * The base abstract class for JSON-RPC 2.0 requests, notifications and
 * responses. Provides common methods for parsing (from JSON string) and
 * serialisation (to JSON string) of these three message types.
 *
 * <p>Example parsing and serialisation back to JSON:
 *
 * <pre>
 * String jsonString = "{\"method\":\"progressNotify\",\"params\":[\"75%\"],\"jsonrpc\":\"2.0\"}";
 *
 * JSONRPC2Message message = null;
 *
 * // parse
 * try {
 *        message = JSONRPC2Message.parse(jsonString);
 * } catch (JSONRPC2ParseException e) {
 *        // handle parse exception
 * }
 *
 * if (message instanceof JSONRPC2Request)
 *        System.out.println("The message is a request");
 * else if (message instanceof JSONRPC2Notification)
 *        System.out.println("The message is a notification");
 * else if (message instanceof JSONRPC2Response)
 *        System.out.println("The message is a response");
 *
 * // serialise back to JSON string
 * System.out.println(message);
 *
 * </pre>
 *
 * <p id="map">The mapping between JSON and Java entities (as defined by the 
 * underlying JSON Smart library): 
 *
 * <pre>
 *     true|false  &lt;---&gt;  java.lang.Boolean
 *     number      &lt;---&gt;  java.lang.Number
 *     string      &lt;---&gt;  java.lang.String
 *     array       &lt;---&gt;  java.util.List
 *     object      &lt;---&gt;  java.util.Map
 *     null        &lt;---&gt;  null
 * </pre>
 * 
 * @author Vladimir Dzhuvinov
 */
public abstract class JSONRPC2Message {


	/**
	 * Map of non-standard JSON-RPC 2.0 message attributes, {@code null} if
	 * none.
	 */
	private Map <String,Object> nonStdAttributes = null;
	

	/** 
	 * Provides common parsing of JSON-RPC 2.0 requests, notifications 
	 * and responses. Use this method if you don't know which type of 
	 * JSON-RPC message the input JSON string represents.
	 *
	 * <p>Batched requests / notifications are not supported.
	 *
	 * <p>This method is thread-safe.
	 *
	 * <p>If you are certain about the message type use the dedicated 
	 * {@link JSONRPC2Request#parse}, {@link JSONRPC2Notification#parse} 
	 * or {@link JSONRPC2Response#parse} methods. They are more efficient 
	 * and provide a more detailed parse error reporting.
	 *
	 * <p>The member order of parsed JSON objects will not be preserved 
	 * (for efficiency reasons) and the JSON-RPC 2.0 version field must be 
	 * set to "2.0". To change this behaviour check the optional {@link 
	 * #parse(String,boolean,boolean)} method.
	 *
	 * @param jsonString A JSON string representing a JSON-RPC 2.0 request, 
	 *                   notification or response, UTF-8 encoded. Must not
	 *                   be {@code null}.
	 *
	 * @return An instance of {@link JSONRPC2Request}, 
	 *         {@link JSONRPC2Notification} or {@link JSONRPC2Response}.
	 *
	 * @throws JSONRPC2ParseException With detailed message if parsing 
	 *                                failed.
	 */
	public static JSONRPC2Message parse(final String jsonString)
		throws JSONRPC2ParseException {

		return parse(jsonString, false, false);
	}
	
	
	/** 
	 * Provides common parsing of JSON-RPC 2.0 requests, notifications 
	 * and responses. Use this method if you don't know which type of 
	 * JSON-RPC message the input string represents.
	 *
	 * <p>Batched requests / notifications are not supported.
	 *
	 * <p>This method is thread-safe.
	 *
	 * <p>If you are certain about the message type use the dedicated 
	 * {@link JSONRPC2Request#parse}, {@link JSONRPC2Notification#parse} 
	 * or {@link JSONRPC2Response#parse} methods. They are more efficient 
	 * and provide a more detailed parse error reporting.
	 *
	 * @param jsonString    A JSON string representing a JSON-RPC 2.0 
	 *                      request, notification or response, UTF-8
	 *                      encoded. Must not be {@code null}.
	 * @param preserveOrder If {@code true} the member order of JSON objects
	 *                      in parameters and results must be preserved.
	 * @param ignoreVersion If {@code true} the {@code "jsonrpc":"2.0"}
	 *                      version field in the JSON-RPC 2.0 message will 
	 *                      not be checked.
	 *
	 * @return An instance of {@link JSONRPC2Request}, 
	 *         {@link JSONRPC2Notification} or {@link JSONRPC2Response}.
	 *
	 * @throws JSONRPC2ParseException With detailed message if parsing 
	 *                                failed.
	 */
	public static JSONRPC2Message parse(final String jsonString, final boolean preserveOrder, final boolean ignoreVersion)
		throws JSONRPC2ParseException {
		
		JSONRPC2Parser parser = new JSONRPC2Parser(preserveOrder, ignoreVersion);
		
		return parser.parseJSONRPC2Message(jsonString);
	}
	
	
	/**
	 * Appends a non-standard attribute to this JSON-RPC 2.0 message. This is 
	 * done by adding a new member (key / value pair) to the top level JSON 
	 * object representing the message.
	 *
	 * <p>You may use this method to add meta and debugging attributes, 
	 * such as the request processing time, to a JSON-RPC 2.0 message.
	 *
	 * @param name  The attribute name. Must not conflict with the existing
	 *              "method", "id", "params", "result", "error" and "jsonrpc"
	 *              attributes reserved by the JSON-RPC 2.0 protocol, else 
	 *              an {@code IllegalArgumentException} will be thrown. Must
	 *              not be {@code null} either.
	 * @param value The attribute value. Must be of type String, boolean,
	 *              number, List, Map or null, else an
	 *              {@code IllegalArgumentException} will be thrown.
	 */
	public void appendNonStdAttribute(final String name, final Object value) {
	
		// Name check
		if (name == null          ||
		    name.equals("method") ||
		    name.equals("id")     ||
		    name.equals("params") ||
		    name.equals("result") ||
		    name.equals("error")  ||
		    name.equals("jsonrpc")   )
	
			throw new IllegalArgumentException("Non-standard attribute name violation");
	
		// Value check
		if ( value != null                &&
		     ! (value instanceof Boolean) &&
		     ! (value instanceof Number)  &&
		     ! (value instanceof String)  &&
		     ! (value instanceof List)    &&
		     ! (value instanceof Map)        )
		     
			throw new IllegalArgumentException("Illegal non-standard attribute value, must map to a valid JSON type");
		
		
		if (nonStdAttributes == null)
			nonStdAttributes = new HashMap<String,Object>();
		
		nonStdAttributes.put(name, value);
	}
	
	
	/**
	 * Retrieves a non-standard JSON-RPC 2.0 message attribute.
	 *
	 * @param name The name of the non-standard attribute to retrieve. Must
	 *             not be {@code null}.
	 *
	 * @return The value of the non-standard attribute (may also be 
	 *         {@code null}, {@code null} if not found.
	 */
	public Object getNonStdAttribute(final String name) {
	
		if (nonStdAttributes == null)
			return null;
		
		return nonStdAttributes.get(name);
	}
	
	
	/**
	 * Retrieves the non-standard JSON-RPC 2.0 message attributes.
	 *
	 * @return The non-standard attributes as a map, {@code null} if none.
	 */
	public Map<String,Object> getNonStdAttributes() {
	
		return nonStdAttributes;
	}
	
	
	/** 
	 * Returns a JSON object representing this JSON-RPC 2.0 message.
	 *
	 * @return The JSON object.
	 */
	public abstract JsonObject toJSONObject();
	
	
	/**
	 * Returns a JSON string representation of this JSON-RPC 2.0 message.
	 *
	 * @see #toString
	 *
	 * @return The JSON object string representing this JSON-RPC 2.0 
	 *         message.
	 */
	public String toJSONString() {
	
		return toString();
	}
	
	
	/** 
	 * Serialises this JSON-RPC 2.0 message to a JSON object string.
	 *
	 * @return The JSON object string representing this JSON-RPC 2.0 
	 *         message.
	 */
	@Override
	public String toString() {
		
		return toJSONObject().toJson();
	}
}
