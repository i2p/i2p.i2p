package net.i2p.i2pcontrol.servlets.jsonrpc2handlers;

import com.thetransactioncompany.jsonrpc2.JSONRPC2Error;

/*
 *  Copyright 2011 hottuna (dev@robertfoss.se)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

/**
 * Represents a JSON-RPC 2.0 error that occured during the processing of a
 * request.
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
 * underlying JSON.simple library):
 * <pre>
 *     true|false  &lt;---&gt;  java.lang.Boolean
 *     number      &lt;---&gt;  java.lang.Number
 *     string      &lt;---&gt;  java.lang.String
 *     array       &lt;---&gt;  java.util.List
 *     object      &lt;---&gt;  java.util.Map
 *     null        &lt;---&gt;  null
 * </pre>
 *
 * <p>The JSON-RPC 2.0 specification and user group forum can be found
 * <a href="http://groups.google.com/group/json-rpc">here</a>.
 *
 * @author <a href="http://dzhuvinov.com">Vladimir Dzhuvinov</a>
 * @version 1.16 (2010-10-04)
 */
public class JSONRPC2ExtendedError extends JSONRPC2Error {

    private static final long serialVersionUID = -6574632977222371077L;

    /** Invalid JSON-RPC 2.0, implementation defined error (-32099 .. -32000) */
    public static final JSONRPC2Error INVALID_PASSWORD = new JSONRPC2ExtendedError(-32001, "Invalid password provided.");

    /** Invalid JSON-RPC 2.0, implementation defined error (-32099 .. -32000) */
    public static final JSONRPC2Error NO_TOKEN = new JSONRPC2ExtendedError(-32002, "No authentication token presented.");

    /** Invalid JSON-RPC 2.0, implementation defined error (-32099 .. -32000) */
    public static final JSONRPC2Error INVALID_TOKEN = new JSONRPC2ExtendedError(-32003, "Authentication token doesn't exist.");

    /** Invalid JSON-RPC 2.0, implementation defined error (-32099 .. -32000) */
    public static final JSONRPC2Error TOKEN_EXPIRED = new JSONRPC2ExtendedError(-32004, "Provided authentication token was expired and will be removed.");

    /** Invalid JSON-RPC 2.0, implementation defined error (-32099 .. -32000) */
    public static final JSONRPC2Error UNSPECIFIED_API_VERSION = new JSONRPC2ExtendedError(-32005, "The version of the I2PControl API wasn't specified, but is required to be specified.");

    /** Invalid JSON-RPC 2.0, implementation defined error (-32099 .. -32000) */
    public static final JSONRPC2Error UNSUPPORTED_API_VERSION = new JSONRPC2ExtendedError(-32006, "The version of the I2PControl API specified is not supported by I2PControl.");



    /**
     * Creates a new JSON-RPC 2.0 error with the specified code and
     * message. The optional data is omitted.
     *
     * @param code    The error code (standard pre-defined or
     *                application-specific).
     * @param message The error message.
     */
    public JSONRPC2ExtendedError(int code, String message) {
        super(code, message);
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
    public JSONRPC2ExtendedError(int code, String message, Object data) {
        super(code, message, data);
    }
}
