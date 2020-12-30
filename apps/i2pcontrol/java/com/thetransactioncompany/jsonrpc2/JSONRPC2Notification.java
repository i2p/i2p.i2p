package com.thetransactioncompany.jsonrpc2;


import java.util.List;
import java.util.Map;

import org.json.simple.JsonObject;


/** 
 * Represents a JSON-RPC 2.0 notification. 
 *
 * <p>Notifications provide a mean for calling a remote procedure without 
 * generating a response. Note that notifications are inherently unreliable 
 * as no confirmation is sent back to the caller.
 *
 * <p>Notifications have the same JSON structure as requests, except that they
 * lack an identifier:
 * <ul>
 *     <li>{@code method} The name of the remote method to call.
 *     <li>{@code params} The required method parameters (if any), which can 
 *         be packed into a JSON array or object.
 *     <li>{@code jsonrpc} A string indicating the JSON-RPC protocol version 
 *         set to "2.0".
 * </ul>
 *
 * <p>Here is a sample JSON-RPC 2.0 notification string:
 *
 * <pre>
 * {  
 *    "method"  : "progressNotify",
 *    "params"  : ["75%"],
 *    "jsonrpc" : "2.0"
 * }
 * </pre>
 *
 * <p>This class provides two methods to obtain a request object:
 * <ul>
 *     <li>Pass a JSON-RPC 2.0 notification string to the static 
 *         {@link #parse} method, or 
 *     <li>Invoke one of the constructors with the appropriate arguments.
 * </ul>
 *
 * <p>Example 1: Parsing a notification string:
 *
 * <pre>
 * String jsonString = "{\"method\":\"progressNotify\",\"params\":[\"75%\"],\"jsonrpc\":\"2.0\"}";
 * 
 * JSONRPC2Notification notification = null;
 * 
 * try {
 *         notification = JSONRPC2Notification.parse(jsonString);
 *
 * } catch (JSONRPC2ParseException e) {
 *         // handle exception
 * }
 * </pre>
 *
 * <p>Example 2: Recreating the above request:
 * 
 * <pre>
 * String method = "progressNotify";
 * List&lt;Object&gt; params = new Vector&lt;Object&gt;();
 * params.add("75%");
 *
 * JSONRPC2Notification notification = new JSONRPC2Notification(method, params);
 *
 * System.out.println(notification);
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
public class JSONRPC2Notification extends JSONRPC2Message {


	/** 
	 * The requested method name. 
	 */
	private String method;

	
	/**
	 * The positional parameters, {@code null} if none.
	 */
	private List<Object> positionalParams;


	/**
	 * The named parameters, {@code null} if none.
	 */
	private Map<String,Object> namedParams;
	
	
	/** 
	 * Parses a JSON-RPC 2.0 notification string. This method is 
	 * thread-safe.
	 *
	 * @param jsonString The JSON-RPC 2.0 notification string, UTF-8 
	 *                   encoded. Must not be {@code null}.
	 *
	 * @return The corresponding JSON-RPC 2.0 notification object.
	 *
	 * @throws JSONRPC2ParseException With detailed message if parsing 
	 *                                failed.
	 */
	public static JSONRPC2Notification parse(final String jsonString)
		throws JSONRPC2ParseException {
	
		return parse(jsonString, false, false, false);
	}
	
	
	/** 
	 * Parses a JSON-RPC 2.0 notification string. This method is 
	 * thread-safe.
	 *
	 * @param jsonString    The JSON-RPC 2.0 notification string, UTF-8 
	 *                      encoded. Must not be {@code null}.
	 * @param preserveOrder {@code true} to preserve the order of JSON 
	 *                      object members in parameters.
	 *
	 * @return The corresponding JSON-RPC 2.0 notification object.
	 *
	 * @throws JSONRPC2ParseException With detailed message if parsing 
	 *                                failed.
	 */
	public static JSONRPC2Notification parse(final String jsonString, 
		                                 final boolean preserveOrder)
		throws JSONRPC2ParseException {
		
		return parse(jsonString, preserveOrder, false, false);
	}
	
	
	/** 
	 * Parses a JSON-RPC 2.0 notification string. This method is 
	 * thread-safe.
	 *
	 * @param jsonString    The JSON-RPC 2.0 notification string, UTF-8 
	 *                      encoded. Must not be {@code null}.
	 * @param preserveOrder {@code true} to preserve the order of JSON 
	 *                      object members in parameters.
	 * @param ignoreVersion {@code true} to skip a check of the 
	 *                      {@code "jsonrpc":"2.0"} version attribute in the 
	 *                      JSON-RPC 2.0 message.
	 *
	 * @return The corresponding JSON-RPC 2.0 notification object.
	 *
	 * @throws JSONRPC2ParseException With detailed message if parsing 
	 *                                failed.
	 */
	public static JSONRPC2Notification parse(final String jsonString, 
		                                 final boolean preserveOrder, 
		                                 final boolean ignoreVersion)
		throws JSONRPC2ParseException {
		
		return parse(jsonString, preserveOrder, ignoreVersion, false);
	}
	
	
	/** 
	 * Parses a JSON-RPC 2.0 notification string. This method is 
	 * thread-safe.
	 *
	 * @param jsonString            The JSON-RPC 2.0 notification string, 
	 *                              UTF-8 encoded. Must not be {@code null}.
	 * @param preserveOrder         {@code true} to preserve the order of
	 *                              JSON object members in parameters.
	 * @param ignoreVersion         {@code true} to skip a check of the 
	 *                              {@code "jsonrpc":"2.0"} version 
	 *                              attribute in the JSON-RPC 2.0 message.
	 * @param parseNonStdAttributes {@code true} to parse non-standard
	 *                              attributes found in the JSON-RPC 2.0 
	 *                              message.
	 *
	 * @return The corresponding JSON-RPC 2.0 notification object.
	 *
	 * @throws JSONRPC2ParseException With detailed message if parsing 
	 *                                failed.
	 */
	public static JSONRPC2Notification parse(final String jsonString, 
	                                         final boolean preserveOrder, 
						 final boolean ignoreVersion, 
						 final boolean parseNonStdAttributes)
		throws JSONRPC2ParseException {
		
		JSONRPC2Parser parser = new JSONRPC2Parser(preserveOrder, ignoreVersion, parseNonStdAttributes);
		
		return parser.parseJSONRPC2Notification(jsonString);
	}
	
	
	/** 
	 * Constructs a new JSON-RPC 2.0 notification with no parameters.
	 *
	 * @param method The name of the requested method. Must not be 
	 *               {@code null}.
	 */
	public JSONRPC2Notification(final String method) {
		
		setMethod(method);
		setParams(null);
	}


	/** 
	 * Constructs a new JSON-RPC 2.0 notification with positional (JSON 
	 * array) parameters.
	 *
	 * @param method           The name of the requested method. Must not 
	 *                         be {@code null}.
	 * @param positionalParams The positional (JSON array) parameters, 
	 *                         {@code null} if none.
	 */
	public JSONRPC2Notification(final String method, 
		                    final List<Object> positionalParams) {
		
		setMethod(method);
		setPositionalParams(positionalParams);
	}
		
	
	/** 
	 * Constructs a new JSON-RPC 2.0 notification with named (JSON object)
	 * parameters.
	 *
	 * @param method      The name of the requested method.
	 * @param namedParams The named (JSON object) parameters, {@code null} 
	 *                    if none.
	 */
	public JSONRPC2Notification(final String method, 
		                    final Map <String,Object> namedParams) {
		
		setMethod(method);
		setNamedParams(namedParams);
	}
	
	
	/** 
	 * Gets the name of the requested method.
	 *
	 * @return The method name.
	 */
	public String getMethod() {
		
		return method;
	}
	
	
	/**
	 * Sets the name of the requested method.
	 *
	 * @param method The method name. Must not be {@code null}.
	 */
	public void setMethod(final String method) {
		
		// The method name is mandatory
		if (method == null)
			throw new IllegalArgumentException("The method name must not be null");

		this.method = method;
	}
	
	
	/** 
	 * Gets the parameters type ({@link JSONRPC2ParamsType#ARRAY positional}, 
	 * {@link JSONRPC2ParamsType#OBJECT named} or 
	 * {@link JSONRPC2ParamsType#NO_PARAMS none}).
	 *
	 * @return The parameters type.
	 */
	public JSONRPC2ParamsType getParamsType() {
	
		if (positionalParams == null && namedParams == null)
			return JSONRPC2ParamsType.NO_PARAMS;

		if (positionalParams != null)
			return JSONRPC2ParamsType.ARRAY;

		if (namedParams != null)
			return JSONRPC2ParamsType.OBJECT;

		else
			return JSONRPC2ParamsType.NO_PARAMS;
	}
	
	/** 
	 * Gets the notification parameters.
	 *
	 * <p>This method was deprecated in version 1.30. Use
	 * {@link #getPositionalParams} or {@link #getNamedParams} instead.
	 *
	 * @return The parameters as {@code List&lt;Object&gt;} for positional
	 *         (JSON array), {@code Map&lt;String,Object&gt;} for named
	 *         (JSON object), or {@code null} if none.
	 */
	@Deprecated
	public Object getParams() {
		
		switch (getParamsType()) {

			case ARRAY:
				return positionalParams;

			case OBJECT:
				return namedParams;

			default:
				return null;
		}
	}


	/**
	 * Gets the positional (JSON array) parameters.
	 *
	 * @since 1.30
	 *
	 * @return The positional (JSON array) parameters, {@code null} if none
	 *         or named.
	 */
	public List<Object> getPositionalParams() {

		return positionalParams;
	}


	/**
	 * Gets the named parameters.
	 *
	 * @since 1.30
	 *
	 * @return The named (JSON object) parameters, {@code null} if none or 
	 *         positional.
	 */
	public Map<String,Object> getNamedParams() {

		return namedParams;
	}
	
	
	/**
	 * Sets the notification parameters.
	 *
	 * <p>This method was deprecated in version 1.30. Use
	 * {@link #setPositionalParams} or {@link #setNamedParams} instead.
	 *
	 * @param params The parameters. For positional (JSON array) pass a 
	 *               {@code List&lt;Object&gt;}. For named (JSON object)
	 *               pass a {@code Map&lt;String,Object&gt;}. If there are
	 *               no parameters pass {@code null}.
	 */
	@Deprecated
	@SuppressWarnings("unchecked")
	public void setParams(final Object params) {
	
		if (params == null) {
			positionalParams = null;
			namedParams = null;
		} else if (params instanceof List) {
			positionalParams = (List<Object>) params;
		} else if (params instanceof Map) {
			namedParams = (Map<String, Object>) params;
		} else {
			throw new IllegalArgumentException("The notification parameters must be of type List, Map or null");
		}
	}


	/**
	 * Sets the positional (JSON array) request parameters.
	 *
	 * @since 1.30
	 *
	 * @param positionalParams The positional (JSON array) request 
	 *                         parameters, {@code null} if none.
	 */
	public void setPositionalParams(final List<Object> positionalParams) {

		if (positionalParams == null)
			return;

		this.positionalParams = positionalParams;
	}


	/**
	 * Sets the named (JSON object) request parameters.
	 *
	 * @since 1.30
	 *
	 * @param namedParams The named (JSON object) request parameters,
	 *                    {@code null} if none.
	 */
	public void setNamedParams(final Map<String,Object> namedParams) {

		if (namedParams == null)
			return;

		this.namedParams = namedParams;
	}
	
	
	@Override
	public JsonObject toJSONObject() {
	
		JsonObject notf = new JsonObject();
		
		notf.put("method", method);
		
		// The params can be omitted if none
		switch (getParamsType()) {

			case ARRAY:
				notf.put("params", positionalParams);
				break;

			case OBJECT:
				notf.put("params", namedParams);
				break;
		}
		
		notf.put("jsonrpc", "2.0");
		
		
		Map <String,Object> nonStdAttributes = getNonStdAttributes();
		
		if (nonStdAttributes != null) {
		
			for (final Map.Entry<String,Object> attr: nonStdAttributes.entrySet())
				notf.put(attr.getKey(), attr.getValue());
		}
		
		return notf;
	}
}
