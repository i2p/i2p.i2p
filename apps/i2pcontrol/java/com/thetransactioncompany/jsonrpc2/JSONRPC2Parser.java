package com.thetransactioncompany.jsonrpc2;


import java.util.List;
import java.util.Map;

import org.json.simple.Jsoner;
import org.json.simple.DeserializationException;


/**
 * Parses JSON-RPC 2.0 request, notification and response messages. 
 *
 * <p>Parsing of batched requests / notifications is not supported.
 *
 * <p>This class is not thread-safe. A parser instance should not be used by 
 * more than one thread unless properly synchronised. Alternatively, you may 
 * use the thread-safe {@link JSONRPC2Message#parse} and its sister methods.
 *
 * <p>Example:
 *
 * <pre>
 * String jsonString = "{\"method\":\"makePayment\"," +
 *                      "\"params\":{\"recipient\":\"Penny Adams\",\"amount\":175.05}," +
 *                      "\"id\":\"0001\","+
 *                      "\"jsonrpc\":\"2.0\"}";
 *  
 *  JSONRPC2Request req = null;
 *
 * JSONRPC2Parser parser = new JSONRPC2Parser();
 *  
 *  try {
 *          req = parser.parseJSONRPC2Request(jsonString);
 * 
 *  } catch (JSONRPC2ParseException e) {
 *          // handle exception
 *  }
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
public class JSONRPC2Parser {


	/**
	 * If {@code true} the order of the parsed JSON object members must be
	 * preserved.
	 */
	private boolean preserveOrder;
	
	
	/**
	 * If {@code true} the {@code "jsonrpc":"2.0"} version attribute in the 
	 * JSON-RPC 2.0 message must be ignored during parsing.
	 */
	private boolean ignoreVersion;
	
	
	/**
	 * If {@code true} non-standard JSON-RPC 2.0 message attributes must be
	 * parsed too.
	 */
	private boolean parseNonStdAttributes;
	
	
	/**
	 * Creates a new JSON-RPC 2.0 message parser.
	 *
	 * <p>The member order of parsed JSON objects in parameters and results
	 * will not be preserved; strict checking of the 2.0 JSON-RPC version 
	 * attribute will be enforced; non-standard message attributes will be 
	 * ignored. Check the other constructors if you want to specify 
	 * different behaviour.
	 */
	public JSONRPC2Parser() {
	
		this(false, false, false);
	}
	
	
	/**
	 * Creates a new JSON-RPC 2.0 message parser.
	 *
	 * <p>Strict checking of the 2.0 JSON-RPC version attribute will be 
	 * enforced; non-standard message attributes will be ignored. Check the 
	 * other constructors if you want to specify different behaviour.
	 *
	 * @param preserveOrder If {@code true} the member order of JSON objects
	 *                      in parameters and results will be preserved.
	 */
	public JSONRPC2Parser(final boolean preserveOrder) {
	
		this(preserveOrder, false, false);
	}
	
	
	/**
	 * Creates a new JSON-RPC 2.0 message parser.
	 *
	 * <p>Non-standard message attributes will be ignored. Check the other 
	 * constructors if you want to specify different behaviour.
	 *
	 * @param preserveOrder If {@code true} the member order of JSON objects
	 *                      in parameters and results will be preserved.
	 * @param ignoreVersion If {@code true} the {@code "jsonrpc":"2.0"}
	 *                      version attribute in the JSON-RPC 2.0 message 
	 *                      will not be checked.
	 */
	public JSONRPC2Parser(final boolean preserveOrder, 
		              final boolean ignoreVersion) {
	
		this(preserveOrder, ignoreVersion, false);
	}
	
	
	/**
	 * Creates a new JSON-RPC 2.0 message parser.
	 *
	 * <p>This constructor allows full specification of the available 
	 * JSON-RPC message parsing properties.
	 *
	 * @param preserveOrder         If {@code true} the member order of JSON 
	 *                              objects in parameters and results will 
	 *                              be preserved.
	 * @param ignoreVersion         If {@code true} the 
	 *                              {@code "jsonrpc":"2.0"} version 
	 *                              attribute in the JSON-RPC 2.0 message 
	 *                              will not be checked.
	 * @param parseNonStdAttributes If {@code true} non-standard attributes 
	 *                              found in the JSON-RPC 2.0 messages will 
	 *                              be parsed too.
	 */
	public JSONRPC2Parser(final boolean preserveOrder, 
		              final boolean ignoreVersion, 
		              final boolean parseNonStdAttributes) {

		this.preserveOrder = preserveOrder;
		this.ignoreVersion = ignoreVersion;
		this.parseNonStdAttributes = parseNonStdAttributes;
	}
	
	
	/**
	 * Parses a JSON object string. Provides the initial parsing of 
	 * JSON-RPC 2.0 messages. The member order of JSON objects will be 
	 * preserved if {@link #preserveOrder} is set to {@code true}.
	 *
	 * @param jsonString The JSON string to parse. Must not be 
	 *                   {@code null}.
	 *
	 * @return The parsed JSON object.
	 *
	 * @throws JSONRPC2ParseException With detailed message if parsing 
	 *                                failed.
	 */
	@SuppressWarnings("unchecked")
	private Map<String,Object> parseJSONObject(final String jsonString)
		throws JSONRPC2ParseException {
		
		if (jsonString.trim().length()==0)
			throw new JSONRPC2ParseException("Invalid JSON: Empty string", 
				                         JSONRPC2ParseException.JSON, 
				                         jsonString);
		
		Object json;
		
		// Parse the JSON string
		try {
			json = Jsoner.deserialize(jsonString);
				
		} catch (DeserializationException e) {

			// Terse message, do not include full parse exception message
			throw new JSONRPC2ParseException("Invalid JSON", 
				                         JSONRPC2ParseException.JSON, 
				                         jsonString);
		}
		
		if (json instanceof List)
			throw new JSONRPC2ParseException("JSON-RPC 2.0 batch requests/notifications not supported", jsonString);
			
		if (! (json instanceof Map))
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 message: Message must be a JSON object", jsonString);
		
		return (Map<String,Object>)json;
	}
	
	
	/**
	 * Ensures the specified parameter is a {@code String} object set to
	 * "2.0". This method is intended to check the "jsonrpc" attribute 
	 * during parsing of JSON-RPC messages.
	 *
	 * @param version    The version parameter. Must not be {@code null}.
	 * @param jsonString The original JSON string.
	 *
	 * @throws JSONRPC2ParseException If the parameter is not a string that
	 *                                equals "2.0".
	 */
	private static void ensureVersion2(final Object version, final String jsonString)
		throws JSONRPC2ParseException {
	
		if (version == null)
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0: Version string missing", jsonString);
			
		else if (! (version instanceof String))
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0: Version not a JSON string", jsonString);
			
		else if (! version.equals("2.0"))
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0: Version must be \"2.0\"", jsonString);
	}
	
	
	/** 
	 * Provides common parsing of JSON-RPC 2.0 requests, notifications 
	 * and responses. Use this method if you don't know which type of 
	 * JSON-RPC message the input string represents.
	 *
	 * <p>If a particular message type is expected use the dedicated 
	 * {@link #parseJSONRPC2Request}, {@link #parseJSONRPC2Notification} 
	 * and {@link #parseJSONRPC2Response} methods. They are more efficient 
	 * and would provide you with more detailed parse error reporting.
	 *
	 * @param jsonString A JSON string representing a JSON-RPC 2.0 request, 
	 *                   notification or response, UTF-8 encoded. Must not
	 *                   be {@code null}.
	 *
	 * @return An instance of {@link JSONRPC2Request}, 
	 *         {@link JSONRPC2Notification} or {@link JSONRPC2Response}.
	 *
	 * @throws JSONRPC2ParseException With detailed message if the parsing 
	 *                                failed.
	 */
	public JSONRPC2Message parseJSONRPC2Message(final String jsonString)
		throws JSONRPC2ParseException {
	
		// Try each of the parsers until one succeeds (or all fail)
		
		try {
			return parseJSONRPC2Request(jsonString);

		} catch (JSONRPC2ParseException e) {
		
			// throw on JSON error, ignore on protocol error
			if (e.getCauseType() == JSONRPC2ParseException.JSON)
				throw e;
		}
		
		try {
			return parseJSONRPC2Notification(jsonString);
			
		} catch (JSONRPC2ParseException e) {
			
			// throw on JSON error, ignore on protocol error
			if (e.getCauseType() == JSONRPC2ParseException.JSON)
				throw e;
		}
		
		try {
			return parseJSONRPC2Response(jsonString);
			
		} catch (JSONRPC2ParseException e) {
			
			// throw on JSON error, ignore on protocol error
			if (e.getCauseType() == JSONRPC2ParseException.JSON)
				throw e;
		}
		
		throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 message", 
			                         JSONRPC2ParseException.PROTOCOL, 
			                         jsonString);
	}
	
	
	/** 
	 * Parses a JSON-RPC 2.0 request string.
	 *
	 * @param jsonString The JSON-RPC 2.0 request string, UTF-8 encoded. 
	 *                   Must not be {@code null}.
	 *
	 * @return The corresponding JSON-RPC 2.0 request object.
	 *
	 * @throws JSONRPC2ParseException With detailed message if parsing 
	 *                                failed.
	 */
	@SuppressWarnings("unchecked")
	public JSONRPC2Request parseJSONRPC2Request(final String jsonString)
		throws JSONRPC2ParseException {
	
		// Initial JSON object parsing
		Map<String,Object> jsonObject = parseJSONObject(jsonString);
		
		
		// Check for JSON-RPC version "2.0"
		Object version = jsonObject.remove("jsonrpc");
		
		if (! ignoreVersion)
			ensureVersion2(version, jsonString);
			
		
		// Extract method name
		Object method = jsonObject.remove("method");
		
		if (method == null)
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 request: Method name missing", jsonString);

		else if (! (method instanceof String))
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 request: Method name not a JSON string", jsonString);

		else if (((String)method).length() == 0)
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 request: Method name is an empty string", jsonString);
		
		
		// Extract ID
		if (! jsonObject.containsKey("id"))
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 request: Missing identifier", jsonString);
		
		Object id = jsonObject.remove("id");
		
		if (  id != null             &&
		    !(id instanceof Number ) &&
		    !(id instanceof Boolean) &&
		    !(id instanceof String )    )
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 request: Identifier not a JSON scalar", jsonString);
		
		
		// Extract params
		Object params = jsonObject.remove("params");
		
		
		JSONRPC2Request request;
		
		if (params == null)
			request = new JSONRPC2Request((String)method, id);

		else if (params instanceof List)
			request = new JSONRPC2Request((String)method, (List<Object>)params, id);

		else if (params instanceof Map)
			request = new JSONRPC2Request((String)method, (Map<String,Object>)params, id);

		else
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 request: Method parameters have unexpected JSON type", jsonString);
		
		
		// Extract remaining non-std params?
		if (parseNonStdAttributes) {
		
			for (Map.Entry<String,Object> entry: jsonObject.entrySet()) {

				request.appendNonStdAttribute(entry.getKey(), entry.getValue());
			}
		}
		
		return request;
	}
	
	
	/** 
	 * Parses a JSON-RPC 2.0 notification string.
	 *
	 * @param jsonString The JSON-RPC 2.0 notification string, UTF-8 
	 *                   encoded. Must not be {@code null}.
	 *
	 * @return The corresponding JSON-RPC 2.0 notification object.
	 *
	 * @throws JSONRPC2ParseException With detailed message if parsing 
	 *                                failed.
	 */
	@SuppressWarnings("unchecked")
	public JSONRPC2Notification parseJSONRPC2Notification(final String jsonString)
		throws JSONRPC2ParseException {
	
		// Initial JSON object parsing
		Map<String,Object> jsonObject = parseJSONObject(jsonString);
		
		
		// Check for JSON-RPC version "2.0"
		Object version = jsonObject.remove("jsonrpc");
		
		if (! ignoreVersion)
			ensureVersion2(version, jsonString);
		
		
		// Extract method name
		Object method = jsonObject.remove("method");
		
		if (method == null)
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 notification: Method name missing", jsonString);

		else if (! (method instanceof String))
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 notification: Method name not a JSON string", jsonString);

		else if (((String)method).length() == 0)
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 notification: Method name is an empty string", jsonString);
		
				
		// Extract params
		Object params = jsonObject.get("params");
		
		JSONRPC2Notification notification;
		
		if (params == null)
			notification = new JSONRPC2Notification((String)method);

		else if (params instanceof List)
			notification = new JSONRPC2Notification((String)method, (List<Object>)params);

		else if (params instanceof Map)
			notification = new JSONRPC2Notification((String)method, (Map<String,Object>)params);
		else
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 notification: Method parameters have unexpected JSON type", jsonString);
	
		// Extract remaining non-std params?
		if (parseNonStdAttributes) {
		
			for (Map.Entry<String,Object> entry: jsonObject.entrySet()) {

				notification.appendNonStdAttribute(entry.getKey(), entry.getValue());
			}
		}
		
		return notification;
	}
	
	
	/** 
	 * Parses a JSON-RPC 2.0 response string.
	 *
	 * @param jsonString The JSON-RPC 2.0 response string, UTF-8 encoded.
	 *                   Must not be {@code null}.
	 *
	 * @return The corresponding JSON-RPC 2.0 response object.
	 *
	 * @throws JSONRPC2ParseException With detailed message if parsing 
	 *                                failed.
	 */
	@SuppressWarnings("unchecked")
	public JSONRPC2Response parseJSONRPC2Response(final String jsonString)
		throws JSONRPC2ParseException {
	
		// Initial JSON object parsing
		Map<String,Object> jsonObject = parseJSONObject(jsonString);
		
		// Check for JSON-RPC version "2.0"
		Object version = jsonObject.remove("jsonrpc");
		
		if (! ignoreVersion)
			ensureVersion2(version, jsonString);
		
		
		// Extract request ID
		Object id = jsonObject.remove("id");
		
		if (   id != null             &&
		    ! (id instanceof Boolean) &&
		    ! (id instanceof Number ) &&
		    ! (id instanceof String )    )
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 response: Identifier not a JSON scalar", jsonString);
		
		
		// Extract result/error and create response object
		// Note: result and error are mutually exclusive
		
		JSONRPC2Response response;
		
		if (jsonObject.containsKey("result") && ! jsonObject.containsKey("error")) {
			
			// Success
			Object res = jsonObject.remove("result");
			
			response = new JSONRPC2Response(res, id);
					
		}
		else if (! jsonObject.containsKey("result") && jsonObject.containsKey("error")) {
		
			// Error JSON object
			Object errorJSON = jsonObject.remove("error");

			if (errorJSON == null)
				throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 response: Missing error object", jsonString);


			if (! (errorJSON instanceof Map))
				throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 response: Error object not a JSON object");


			Map<String,Object> error = (Map<String,Object>)errorJSON;
			
			
			int errorCode;

			try {
				errorCode = ((Number)error.get("code")).intValue();

			} catch (Exception e) {

				throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 response: Error code missing or not an integer", jsonString);
			}
			
			String errorMessage;

			try {
				errorMessage = (String)error.get("message");

			} catch (Exception e) {

				throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 response: Error message missing or not a string", jsonString);
			}
			
			Object errorData = error.get("data");
			
			response = new JSONRPC2Response(new JSONRPC2Error(errorCode, errorMessage, errorData), id);
			
		}
		else if (jsonObject.containsKey("result") && jsonObject.containsKey("error")) {

			// Invalid response
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 response: You cannot have result and error at the same time", jsonString);
		}
		else if (! jsonObject.containsKey("result") && ! jsonObject.containsKey("error")){

			// Invalid response
			throw new JSONRPC2ParseException("Invalid JSON-RPC 2.0 response: Neither result nor error specified", jsonString);
		}
		else {
			throw new AssertionError();
		}
		
		
		// Extract remaining non-std params?
		if (parseNonStdAttributes) {
		
			for (Map.Entry<String,Object> entry: jsonObject.entrySet()) {

				response.appendNonStdAttribute(entry.getKey(), entry.getValue());
			}
		}
		
		return response;
	}
	
	
	/**
	 * Controls the preservation of JSON object member order in parsed
	 * JSON-RPC 2.0 messages.
	 *
	 * @param preserveOrder {@code true} to preserve the order of JSON 
	 *                      object members, else {@code false}.
	 */
	public void preserveOrder(final boolean preserveOrder) {
	
		this.preserveOrder = preserveOrder;
	}
	
	
	/**
	 * Returns {@code true} if the order of JSON object members in parsed
	 * JSON-RPC 2.0 messages is preserved, else {@code false}.
	 *
	 * @return {@code true} if order is preserved, else {@code false}.
	 */
	public boolean preservesOrder() {
	
		return preserveOrder;
	}
	
	
	/**
	 * Specifies whether to ignore the {@code "jsonrpc":"2.0"} version 
	 * attribute during parsing of JSON-RPC 2.0 messages.
	 *
	 * <p>You may with to disable strict 2.0 version checking if the parsed 
	 * JSON-RPC 2.0 messages don't include a version attribute or if you 
	 * wish to achieve limited compatibility with older JSON-RPC protocol 
	 * versions.
	 *
	 * @param ignore {@code true} to skip checks of the 
	 *               {@code "jsonrpc":"2.0"} version attribute in parsed 
	 *               JSON-RPC 2.0 messages, else {@code false}.
	 */
	public void ignoreVersion(final boolean ignore) {
	
		ignoreVersion = ignore;
	}
	
	
	/**
	 * Returns {@code true} if the {@code "jsonrpc":"2.0"} version 
	 * attribute in parsed JSON-RPC 2.0 messages is ignored, else 
	 * {@code false}.
	 *
	 * @return {@code true} if the {@code "jsonrpc":"2.0"} version 
	 *         attribute in parsed JSON-RPC 2.0 messages is ignored, else 
	 *         {@code false}.
	 */
	public boolean ignoresVersion() {
	
		return ignoreVersion;
	}
	
	
	/**
	 * Specifies whether to parse non-standard attributes found in JSON-RPC 
	 * 2.0 messages.
	 *
	 * @param enable {@code true} to parse non-standard attributes, else
	 *               {@code false}.
	 */
	public void parseNonStdAttributes(final boolean enable) {
	
		parseNonStdAttributes = enable;
	}
	
	
	/**
	 * Returns {@code true} if non-standard attributes in JSON-RPC 2.0
	 * messages are parsed.
	 *
	 * @return {@code true} if non-standard attributes are parsed, else 
	 *         {@code false}.
	 */
	public boolean parsesNonStdAttributes() {
	
		return parseNonStdAttributes;
	}
}
