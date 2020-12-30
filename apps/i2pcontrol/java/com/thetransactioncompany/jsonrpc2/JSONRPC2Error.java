package com.thetransactioncompany.jsonrpc2;


import org.json.simple.JsonObject;


/** 
 * Represents a JSON-RPC 2.0 error that occurred during the processing of a 
 * request. This class is immutable.
 *
 * <p>The protocol expects error objects to be structured like this:
 *
 * <ul>
 *     <li>{@code code} An integer that indicates the error type.
 *     <li>{@code message} A string providing a short description of the 
 *         error. The message should be limited to a concise single sentence.
 *     <li>{@code data} Additional information, which may be omitted. Its 
 *         contents is entirely defined by the application.
 * </ul>
 * 
 * <p>Note that the "Error" word in the class name was put there solely to
 * comply with the parlance of the JSON-RPC spec. This class doesn't inherit 
 * from {@code java.lang.Error}. It's a regular subclass of 
 * {@code java.lang.Exception} and, if thrown, it's to indicate a condition 
 * that a reasonable application might want to catch.
 *
 * <p>This class also includes convenient final static instances for all 
 * standard JSON-RPC 2.0 errors:
 *
 * <ul>
 *     <li>{@link #PARSE_ERROR} JSON parse error (-32700)
 *     <li>{@link #INVALID_REQUEST} Invalid JSON-RPC 2.0 Request (-32600)
 *     <li>{@link #METHOD_NOT_FOUND} Method not found (-32601)
 *     <li>{@link #INVALID_PARAMS} Invalid parameters (-32602)
 *     <li>{@link #INTERNAL_ERROR} Internal error (-32603)
 * </ul>
 *
 * <p>Note that the range -32099..-32000 is reserved for additional server 
 * errors.
 *
 * <p id="map">The mapping between JSON and Java entities (as defined by the 
 * underlying JSON Smart library): 
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
public class JSONRPC2Error extends Exception {
	
	
	/**
	 * Serial version UID.
	 */
	private static final long serialVersionUID = 4682571044532698806L;


	/** 
	 * JSON parse error (-32700).
	 */
	public static final JSONRPC2Error PARSE_ERROR = new JSONRPC2Error(-32700, "JSON parse error");
	
	
	/** 
	 * Invalid JSON-RPC 2.0 request error (-32600).
	 */
	public static final JSONRPC2Error INVALID_REQUEST = new JSONRPC2Error(-32600, "Invalid request");
	
	
	/** 
	 * Method not found error (-32601). 
	 */
	public static final JSONRPC2Error METHOD_NOT_FOUND = new JSONRPC2Error(-32601, "Method not found");
	
	
	/** 
	 * Invalid parameters error (-32602).
	 */
	public static final JSONRPC2Error INVALID_PARAMS = new JSONRPC2Error(-32602, "Invalid parameters");
	
	
	/** 
	 * Internal JSON-RPC 2.0 error (-32603).
	 */
	public static final JSONRPC2Error INTERNAL_ERROR = new JSONRPC2Error(-32603, "Internal error");
	
	
	/**
	 * The error code.
	 */
	private final int code;
	
	
	/**
	 * The optional error data.
	 */
	private final Object data;


	/**
	 * Appends the specified string to the message of a JSON-RPC 2.0 error.
	 *
	 * @param err The JSON-RPC 2.0 error. Must not be {@code null}.
	 * @param apx The string to append to the original error message.
	 *
	 * @return A new JSON-RPC 2.0 error with the appended message.
	 */
	@Deprecated
	public static JSONRPC2Error appendMessage(final JSONRPC2Error err, final String apx) {

		return new JSONRPC2Error(err.getCode(), err.getMessage() + apx, err.getData());
	}


	/**
	 * Sets the specified data to a JSON-RPC 2.0 error.
	 *
	 * @param err  The JSON-RPC 2.0 error to have its data field set. Must
	 *             not be {@code null}.
	 * @param data Optional error data, must <a href="#map">map</a> to a 
	 *             valid JSON type.
	 *
	 * @return A new JSON-RPC 2.0 error with the set data.
	 */
	@Deprecated
	public static JSONRPC2Error setData(final JSONRPC2Error err, final Object data) {

		return new JSONRPC2Error(err.getCode(), err.getMessage(), data);
	}
	
	
	/** 
	 * Creates a new JSON-RPC 2.0 error with the specified code and 
	 * message. The optional data is omitted.
	 * 
	 * @param code    The error code (standard pre-defined or
	 *                application-specific).
	 * @param message The error message.
	 */
	public JSONRPC2Error(int code, String message) {
		
		this(code, message, null);
	}
	
	
	/** 
	 * Creates a new JSON-RPC 2.0 error with the specified code,
	 * message and data.
	 * 
	 * @param code    The error code (standard pre-defined or
	 *                application-specific).
	 * @param message The error message.
	 * @param data    Optional error data, must <a href="#map">map</a>
	 *                to a valid JSON type.
	 */
	public JSONRPC2Error(int code, String message, Object data) {
		
		super(message);
		this.code = code;
		this.data = data;
	}
	
	
	/** 
	 * Gets the JSON-RPC 2.0 error code.
	 *
	 * @return The error code.
	 */
	public int getCode() {
		
		return code;
	}
	
	
	/**
	 * Gets the JSON-RPC 2.0 error data.
	 *
	 * @return The error data, {@code null} if none was specified.
	 */
	public Object getData() {
		
		return data;	
	}


	/**
	 * Sets the specified data to a JSON-RPC 2.0 error.
	 *
	 * @param data Optional error data, must <a href="#map">map</a> to a 
	 *             valid JSON type.
	 *
	 * @return A new JSON-RPC 2.0 error with the set data.
	 */
	public JSONRPC2Error setData(final Object data) {

		return new JSONRPC2Error(code, getMessage(), data);
	}


	/**
	 * Appends the specified string to the message of this JSON-RPC 2.0 
	 * error.
	 *
	 * @param apx The string to append to the original error message.
	 *
	 * @return A new JSON-RPC 2.0 error with the appended message.
	 */
	public JSONRPC2Error appendMessage(final String apx) {

		return new JSONRPC2Error(code, getMessage() + apx, data);
	}
	
	
	/** 
	 * @see #toJSONObject
	 */
	@Deprecated
	public JsonObject toJSON() {
	
		return toJSONObject();
	}
	
	
	/** 
	 * Returns a JSON object representation of this JSON-RPC 2.0 error.
	 *
	 * @return A JSON object representing this error object.
	 */
	public JsonObject toJSONObject() {
	
		JsonObject out = new JsonObject();
		
		out.put("code", code);
		out.put("message", super.getMessage());
		if (data != null)
			out.put("data", data);
				
		return out;
	}
	
	
	/** 
	 * Serialises the error object to a JSON string.
	 *
	 * @return A JSON-encoded string representing this error object.
	 */
	@Override
	public String toString() {
		
		return toJSON().toString();
	}
	
	
	/**
         * Overrides {@code Object.equals()}.
         *
         * @param object The object to compare to.
         *
         * @return {@code true} if both objects are instances if this class and
	 *         their error codes are identical, {@code false} if not.
         */
	@Override
        public boolean equals(Object object) {
        
                return object != null &&
                       object instanceof JSONRPC2Error && 
                       code == ((JSONRPC2Error)object).getCode();
        }
}
