/** 
 * Utility classes for typed retrieval of JSON-RPC 2.0 request parameters on the
 * server side.
 * 
 * <p>The following parameter type conversion choices are available:
 *
 * <ul>
 *     <li>JSON true/false to Java {@code boolean}
 *     <li>JSON number to Java {@code int}, {@code long}, {@code float} or 
 *         {@code double}
 *     <li>JSON string to {@code java.lang.String}
 *     <li>Predefined (enumerated) JSON string to a Java {@code enum} constant
 *         or {@code java.lang.String}
 *     <li>JSON array to Java {@code boolean[]}, {@code int[]}, {@code long[]},
 *         {@code float[]}, {@code double[]} or {@code string[]} array, or 
 *         to mixed type {@code java.util.List}
 *     <li>JSON object to {@code java.util.Map}
 * </ul>
 *
 * <p>If a parameter cannot be retrieved, either because it's missing or
 * is of the wrong type, a standard 
 * {@link com.thetransactioncompany.jsonrpc2.JSONRPC2Error#INVALID_PARAMS}
 * exception is thrown.
 *
 * <p>There are two concrete classes:
 *
 * <ul>
 *     <li>The {@link com.thetransactioncompany.jsonrpc2.util.PositionalParamsRetriever} 
 *         class is for extracting <em>positional parameters</em> (packed in a
 *	   JSON array).
 *     <li>The {@link com.thetransactioncompany.jsonrpc2.util.NamedParamsRetriever} 
 *         class is for extracting <em>named parameters</em> (packed in a JSON
 *         object).
 * </ul>
 *
 *
 * <p><b>Package dependencies:</b> The classes in this package depend on the 
 * sister {@link com.thetransactioncompany.jsonrpc2} package.
 *
 * @author Vladimir Dzhuvinov
 */
package com.thetransactioncompany.jsonrpc2.util;

  
