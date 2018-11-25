package com.thetransactioncompany.jsonrpc2.util;


import java.util.List;
import java.util.Map;

import com.thetransactioncompany.jsonrpc2.JSONRPC2Error;


/**
 * Utility class for retrieving JSON-RPC 2.0 positional parameters (packed into
 * a JSON Array). 
 *
 * <p>Provides a set of getter methods according to the expected parameter type
 * (number, string, etc.) and whether the parameter is mandatory or optional:
 *
 * <ul>
 *     <li>{@code getXXX(param_pos)} for mandatory parameters, where 
 *         {@code XXX} is the expected parameter type.
 *     <li>{@code getOptXXX(param_pos, default_value)} for optional parameters,
 *         specifying a default value.
 * </ul>
 *
 * <p>There are also generic getter methods that let you do the type conversion 
 * yourself.
 *
 * <p>If a parameter cannot be retrieved, e.g. due to a missing mandatory 
 * parameter or bad type, a 
 * {@link com.thetransactioncompany.jsonrpc2.JSONRPC2Error#INVALID_PARAMS}
 * exception is thrown.
 *
 * <p>Example: suppose you have a method with 3 positional parameters where the
 * first two are mandatory and the last is optional and has a default value of
 * {@code true}.
 *
 * <pre>
 * // Parse received request string
 * JSONRPC2Request request = null;
 *
 * try {
 *         request = JSONRPC2Request.parse(jsonString);
 * } catch (JSONRPC2ParseException e) {
 *         // handle exception...
 * }
 *
 * // Create a new retriever for positional parameters
 * List params = (List)request.getParams();
 * PositionalParamsRetriever r = new PositionalParamsRetriever(params);
 *
 * try {
 *         // Extract first mandatory string parameter
 *         String param1 = r.getString(0);
 *
 *         // Extract second integer parameter
 *         int param2 = r.getInt(1);
 *
 *         // Extract third optional boolean parameter which defaults to true
 *         boolean param3 = r.getOptBoolean(2, true);
 *
 * } catch (JSONRPC2Error e) {
 *         // A JSONRPC2Error.INVALID_PARAMS will be thrown to indicate
 *         // an unexpected parameter type or a missing mandatory parameter.
 *         // You can use it straight away to create the appropriate
 *         // JSON-RPC 2.0 error response.
 *         JSONRPC2Response response = new JSONRPC2Response(e, null);
 * }
 * </pre>
 *
 * @author Vladimir Dzhuvinov
 */
public class PositionalParamsRetriever
	extends ParamsRetriever {

	
	/** 
	 * The positional parameters interface. 
	 */
	private List<Object> params = null;


	/**
	 * Throws a JSON-RPC 2.0 error indicating a missing positional 
	 * parameter.
	 *
	 * @param position The parameter position. Should be non-negative.
	 *
	 * @throws JSONRPC2Error Formatted JSON-RPC 2.0 error.
	 */
	private static void throwMissingParameterException(final int position)
		throws JSONRPC2Error {

		throw JSONRPC2Error.INVALID_PARAMS.
			appendMessage(": Missing parameter at position " + position);
	}


	/**
	 * Throws a JSON-RPC 2.0 error indicating a positional parameter with
	 * unexpected {@code null} value.
	 *
	 * @param position The parameter position. Should be non-negative.
	 *
	 * @throws JSONRPC2Error Formatted JSON-RPC 2.0 error.
	 */
	private static void throwNullParameterException(final int position)
		throws JSONRPC2Error {

		throw JSONRPC2Error.INVALID_PARAMS.
			appendMessage(": Parameter at position " + position + " must not be null");
	}


	/**
	 * Throws a JSON-RPC 2.0 error indicating a positional parameter with 
	 * an unexpected enumerated value.
	 *
	 * @param position    The parameter position. Should be non-negative.
	 * @param enumStrings The acceptable string values. Must not be 
	 *                    {@code null}.
	 *
	 * @throws JSONRPC2Error Formatted JSON-RPC 2.0 error.
	 */
	private static void throwEnumParameterException(final int position, 
		                                        final String[] enumStrings)
		throws JSONRPC2Error {

		StringBuilder msg = new StringBuilder(": Enumerated parameter at position " + 
			position + "\" must have values ");

		for (int i=0; i < enumStrings.length; i++) {

			if (i > 0 && i == enumStrings.length - 1)
				msg.append(" or ");

			else if (i > 0)
				msg.append(", ");

			msg.append('"');
			msg.append(enumStrings[i]);
			msg.append('"');
		}

		throw JSONRPC2Error.INVALID_PARAMS.appendMessage(msg.toString());
	}


	/**
	 * Throws a JSON-RPC 2.0 error indicating a positional parameter with 
	 * an unexpected enumerated value.
	 *
	 * @param position  The parameter position. Should be non-negative.
	 * @param enumClass The enumeration class specifying the acceptable 
	 *                  string values. Must not be {@code null}.
	 *
	 * @throws JSONRPC2Error Formatted JSON-RPC 2.0 error.
	 */
	private static <T extends Enum<T>> void throwEnumParameterException(final int position, 
		                                                            final Class<T> enumClass)
		throws JSONRPC2Error {

		StringBuilder msg = new StringBuilder(": Enumerated parameter at position " + 
			position + " must have values ");

		T[] constants = enumClass.getEnumConstants();

		for (int i = 0; i < constants.length; i++) {
		
			if (i > 0 && i == constants.length - 1)
				msg.append(" or ");

			else if (i > 0)
				msg.append(", ");

			msg.append('"');
			msg.append(constants[i].toString());
			msg.append('"');
		}

		throw JSONRPC2Error.INVALID_PARAMS.appendMessage(msg.toString());
	}


	/**
	 * Creates a JSON-RPC 2.0 error indicating a positional parameter with 
	 * an unexpected JSON type.
	 *
	 * @param position The parameter position. Should be non-negative.
	 *
	 * @return Formatted JSON-RPC 2.0 error.
	 */
	private static JSONRPC2Error newUnexpectedParameterTypeException(final int position) {

		return JSONRPC2Error.INVALID_PARAMS.
			appendMessage(": Parameter at position " + position + " has an unexpected JSON type");
	}


	/**
	 * Creates a JSON-RPC 2.0 error indicating an array exception.
	 *
	 * @param position The parameter position. Should be non-negative.
	 *
	 * @return Formatted JSON-RPC 2.0 error.
	 */
	private static JSONRPC2Error newArrayException(final int position) {

		return JSONRPC2Error.INVALID_PARAMS.
			appendMessage(": Parameter at position " + position + " caused an array exception");
	}
	
	
	/**
	 * Creates a new positional parameters retriever from the specified 
	 * value list.
	 *
	 * @param params The positional parameters list. Must not be 
	 *               {@code null}.
	 */
	public PositionalParamsRetriever(final List<Object> params) {
	
		if (params == null)
			throw new IllegalArgumentException("The parameters list must not be null");

		this.params = params;
	}


	/**
	 * Gets the positional parameters for this retriever.
	 *
	 * @return The positional parameters.
	 */
	public List<Object> getParams() {

		return params;
	}
	
	
	@Override
	public int size() {
	
		return params.size();
	}
	
	
	/** 
	 * Returns {@code true} a parameter at the specified position exists, 
	 * else {@code false}.
	 *
	 * @param position The parameter position.
	 *
	 * @return {@code true} if the parameter exists, else {@code false}.
	 */
	public boolean hasParam(final int position) {

		return position < params.size();
	}


	/**
	 * @see #hasParam
	 */
	@Deprecated
	public boolean hasParameter(final int position) {

		return hasParam(position);
	}
	
	
	/**
	 * Throws a {@code JSONRPC2Error.INVALID_PARAMS} exception if there is
	 * no parameter at the specified position.
	 *
	 * <p>You may use this method to fire the proper JSON-RPC 2.0 error
	 * on a missing mandatory parameter.
	 *
	 * @param position The parameter position, starting with zero for the 
	 *                 first.
	 *
	 * @throws JSONRPC2Error On a missing parameter
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public void ensureParam(final int position)
		throws JSONRPC2Error {
		
		if (position >= params.size() )
			throwMissingParameterException(position);
	}


	/**
	 * @see #ensureParam
	 */
	@Deprecated
	public void ensureParameter(final int position)
		throws JSONRPC2Error {

		ensureParam(position);
	}
	
	
	/**
	 * Throws a {@code JSONRPC2Error.INVALID_PARAMS} exception if there is
	 * no parameter at the specified position, its value is {@code null}, 
	 * or its type doesn't map to the specified.
	 *
	 * <p>You may use this method to fire the proper JSON-RPC 2.0 error
	 * on a missing or badly-typed mandatory parameter.
	 *
	 * @param position The parameter position.
	 * @param clazz    The corresponding Java class that the parameter 
	 *                 should map to (any one of the return types of the 
	 *                 {@code getXXX()} getter methods. Set to 
	 *                 {@code Object.class} to allow any type. Must not be
	 *                 {@code null}.
	 *
	 * @throws JSONRPC2Error On a missing parameter, {@code null} value or 
	 *                       bad type ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public <T> void ensureParam(final int position, final Class<T> clazz)
		throws JSONRPC2Error {
		
		ensureParameter(position, clazz, false);
	}


	/**
	 * @see #ensureParam(int, Class)
	 */
	@Deprecated
	public <T> void ensureParameter(final int position, final Class<T> clazz)
		throws JSONRPC2Error {

		ensureParam(position, clazz);
	}
	
	
	/**
	 * Throws a {@code JSONRPC2Error.INVALID_PARAMS} exception if there is
	 * no parameter at the specified position or its type doesn't map to 
	 * the specified.
	 *
	 * <p>You may use this method to fire the proper JSON-RPC 2.0 error
	 * on a missing or badly-typed mandatory parameter.
	 *
	 * @param position  The parameter position.
	 * @param clazz     The corresponding Java class that the parameter 
	 *                  should map to (any one of the return types of the 
	 *                  {@code getXXX()} getter methods. Set to 
	 *                  {@code Object.class} to allow any type. Must not be
	 *                  {@code null}.
	 * @param allowNull If {@code true} allows a {@code null} parameter
	 *                  value.
	 *
	 * @throws JSONRPC2Error On a missing parameter or bad type 
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public <T> void ensureParam(final int position, 
		                    final Class<T> clazz, 
		                    final boolean allowNull)
		throws JSONRPC2Error {
		
		// First, check existence only
		ensureParameter(position);
		
		// Now check type
		Object value = params.get(position);
		
		if (value == null) {
			
			if (allowNull)
				return; // ok
			else
				throwNullParameterException(position);
		}
		
		if (! clazz.isAssignableFrom(value.getClass()))
			throw newUnexpectedParameterTypeException(position);
	}


	/**
	 * @see #ensureParam(int, Class, boolean)
	 */
	@Deprecated
	public <T> void ensureParameter(final int position, 
		                        final Class<T> clazz, 
		                        final boolean allowNull)
		throws JSONRPC2Error {

		ensureParam(position, clazz, allowNull);
	}
	
	
	/**
	 * Retrieves the specified parameter which can be of any type. Use this 
	 * generic getter if you want to cast the value yourself. Otherwise 
	 * look at the typed {@code get*} methods.
	 *
	 * @param position The parameter position.
	 *
	 * @return The parameter value.
	 *
	 * @throws JSONRPC2Error On a missing parameter
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public Object get(final int position)
		throws JSONRPC2Error {
		
		ensureParameter(position);
		
		return params.get(position);
	}
	
	
	/**
	 * Retrieves the specified parameter which must map to the provided
	 * class (use the appropriate wrapper class for primitive types).
	 *
	 * @param position The parameter position.
	 * @param clazz    The corresponding Java class that the parameter 
	 *                 should map to (any one of the return types of the 
	 *                 {@code getXXX()} getter methods. Set to
	 *                 {@code Object.class} to allow any type. Must not be
	 *                 {@code null}.
	 *
	 * @return The parameter value.
	 *
	 * @throws JSONRPC2Error On a missing parameter, {@code null} value or 
	 *                       bad type ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public <T> T get(final int position, final Class<T> clazz)
		throws JSONRPC2Error {
	
		return get(position, clazz, false);
	}
	
	
	/**
	 * Retrieves the specified parameter which must map to the provided
	 * class (use the appropriate wrapper class for primitive types).
	 *
	 * @param position  The parameter position.
	 * @param clazz     The corresponding Java class that the parameter 
	 *                  should map to (any one of the return types of the 
	 *                  {@code getXXX()} getter methods. Set to
	 *                  {@code Object.class} to allow any type. Must not be
	 *                  {@code null}.
	 * @param allowNull If {@code true} allows a {@code null} parameter
	 *                  value.
	 *
	 * @return The parameter value.
	 *
	 * @throws JSONRPC2Error On a missing parameter or bad type 
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	@SuppressWarnings("unchecked")
	public <T> T get(final int position, final Class<T> clazz, final boolean allowNull)
		throws JSONRPC2Error {
	
		ensureParameter(position, clazz, allowNull);
		
		try {
			return (T)params.get(position);
			
		} catch (ClassCastException e) {
			
			throw newUnexpectedParameterTypeException(position);
		}
	}
	
	
	/**
	 * Retrieves the specified optional parameter which must map to the
	 * provided class (use the appropriate wrapper class for primitive 
	 * types). If the parameter doesn't exist the method returns the 
	 * specified default value.
	 *
	 * @param position     The parameter position.
	 * @param clazz        The corresponding Java class that the parameter 
	 *                     should map to (any one of the return types of 
	 *                     the {@code getXXX()} getter methods. Set to 
	 *                     {@code Object.class} to allow any type. Must not
	 *                     be {@code null}.
	 * @param defaultValue The default return value if the parameter
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public <T> T getOpt(final int position, final Class<T> clazz, final T defaultValue)
		throws JSONRPC2Error {
	
		return getOpt(position, clazz, false, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified optional parameter which must map to the
	 * provided class (use the appropriate wrapper class for primitive 
	 * types). If the parameter doesn't exist the method returns the 
	 * specified default value.
	 *
	 * @param position     The parameter position.
	 * @param clazz        The corresponding Java class that the parameter 
	 *                     should map to (any one of the return types of 
	 *                     the {@code getXXX()} getter methods. Set to 
	 *                     {@code Object.class} to allow any type. Must not
	 *                     be {@code null}.
	 * @param allowNull    If {@code true} allows a {@code null} parameter
	 *                     value.
	 * @param defaultValue The default return value if the parameter
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value.
	 *
	 * @throws JSONRPC2Error On a bad parameter type
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	@SuppressWarnings("unchecked")
	public <T> T getOpt(final int position, 
		            final Class<T> clazz, 
		            final boolean allowNull, 
		            final T defaultValue)
		throws JSONRPC2Error {
	
		if (! hasParameter(position))
			return defaultValue;
		
		ensureParameter(position, clazz, allowNull);
		
		try {
			return (T)params.get(position);
			
		} catch (ClassCastException e) {
			
			throw newUnexpectedParameterTypeException(position);
		}
	}
	
	
	/**
	 * Retrieves the specified string parameter.
	 *
	 * @param position The parameter position.
	 *
	 * @return The parameter value as a string.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or 
	 *                       {@code null} value 
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String getString(final int position)
		throws JSONRPC2Error {
		
		return getString(position, false);
	}
	
	
	/**
	 * Retrieves the specified string parameter.
	 *
	 * @param position  The parameter position.
	 * @param allowNull If {@code true} allows a {@code null} value.
	 *
	 * @return The parameter value as a string.
	 *
	 * @throws JSONRPC2Error On a missing parameter or bad type
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String getString(final int position, final boolean allowNull)
		throws JSONRPC2Error {
		
		return get(position, String.class, allowNull);
	}
	
	
	/**
	 * Retrieves the specified optional string parameter. If it doesn't 
	 * exist the method will return the specified default value.
	 *
	 * @param position     The parameter position.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value as a string.
	 *
	 * @throws JSONRPC2Error On a bad type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String getOptString(final int position, final String defaultValue)
		throws JSONRPC2Error {
	
		return getOptString(position, false, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified optional string parameter. If it doesn't 
	 * exist the method will return the specified default value.
	 *
	 * @param position     The parameter position.
	 * @param allowNull    If {@code true} allows a {@code null} value.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value as a string.
	 *
	 * @throws JSONRPC2Error On a bad type ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String getOptString(final int position, final boolean allowNull, final String defaultValue)
		throws JSONRPC2Error {
	
		return getOpt(position, String.class, allowNull, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified enumerated string parameter.
	 *
	 * @param position    The parameter position.
	 * @param enumStrings The acceptable string values. Must not be
	 *                    {@code null}.
	 *
	 * @return The parameter value as a string.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or 
	 *                       bad enumeration value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String getEnumString(final int position, final String[] enumStrings)
		throws JSONRPC2Error {
		
		return getEnumString(position, enumStrings, false); 
	}
	
	
	/**
	 * Retrieves the specified enumerated string parameter, allowing for a
	 * case insenstive match.
	 *
	 * @param position    The parameter position.
	 * @param enumStrings The acceptable string values. Must not be
	 *                    {@code null}.
	 * @param ignoreCase  {@code true} for a case insensitive match.
	 *
	 * @return The matching parameter value as a string.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or 
	 *                       bad enumeration value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String getEnumString(final int position, 
		                    final String[] enumStrings, 
		                    final boolean ignoreCase)
		throws JSONRPC2Error {
		
		String value = get(position, String.class);
		
		String match = getEnumStringMatch(value, enumStrings, ignoreCase);

		if (match == null)
			throwEnumParameterException(position, enumStrings);

		return match;
	}
	
	
	/**
	 * Retrieves the specified optional enumerated string parameter. If it
	 * doesn't exist the method will return the specified default value.
	 *
	 * @param position     The parameter position.
	 * @param enumStrings  The acceptable string values. Must not be
	 *                     {@code null}.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value as a string.
	 *
	 * @throws JSONRPC2Error On a bad type or bad enumeration value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String getOptEnumString(final int position, 
		                       final String[] enumStrings, 
		                       final String defaultValue)
		throws JSONRPC2Error {
		
		return getOptEnumString(position, enumStrings, defaultValue, false); 
	}
	
	
	/**
	 * Retrieves the specified optional enumerated string parameter, 
	 * allowing for a case insenstive match. If it doesn't exist the method 
	 * will return the specified default value.
	 *
	 * @param position     The parameter position.
	 * @param enumStrings  The acceptable string values. Must not be
	 *                     {@code null}.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 * @param ignoreCase   {@code true} for a case insensitive match.
	 *
	 * @return The parameter value as a string.
	 *
	 * @throws JSONRPC2Error On a bad type or bad enumeration value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String getOptEnumString(final int position, 
		                       final String[] enumStrings, 
		                       final String defaultValue, 
		                       final boolean ignoreCase)
		throws JSONRPC2Error {
		
		String value = getOpt(position, String.class, defaultValue);

		if (defaultValue == null && value == null)
			return null;
		
		String match = getEnumStringMatch(value, enumStrings, ignoreCase);

		if (match == null)
			throwEnumParameterException(position, enumStrings);

		return match;
	}
	
	
	/**
	 * Retrieves the specified enumerated parameter (from a JSON string 
	 * that has a predefined set of possible values).
	 *
	 * @param position  The parameter position.
	 * @param enumClass An enumeration type with constant names 
	 *                  representing the acceptable string values. Must not
	 *                  be {@code null}.
	 *
	 * @return The matching enumeration constant.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or 
	 *                       bad enumeration value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public <T extends Enum<T>> T getEnum(final int position, final Class<T> enumClass)
		throws JSONRPC2Error {
		
		return getEnum(position, enumClass, false); 
	}
	
	
	/**
	 * Retrieves the specified enumerated parameter (from a JSON string 
	 * that has a predefined set of possible values), allowing for a case 
	 * insensitive match.
	 *
	 * @param position   The parameter position.
	 * @param enumClass  An enumeration type with constant names 
	 *                   representing the acceptable string values. Must
	 *                   not be {@code null}.
	 * @param ignoreCase If {@code true} a case insensitive match against
	 *                   the acceptable constant names is performed.
	 *
	 * @return The matching enumeration constant.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or 
	 *                       bad enumeration value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public <T extends Enum<T>> T getEnum(final int position, 
		                             final Class<T> enumClass, 
		                             final boolean ignoreCase)
		throws JSONRPC2Error {
		
		String value = get(position, String.class);
		
		T match = getEnumStringMatch(value, enumClass, ignoreCase);

		if (match == null)
			throwEnumParameterException(position, enumClass);

		return match;
	}
	
	
	/**
	 * Retrieves the specified optional enumerated parameter (from a JSON
	 * string that has a predefined set of possible values). If it doesn't
	 * exist the method will return the specified default value.
	 *
	 * @param position     The parameter position.
	 * @param enumClass    An enumeration type with constant names 
	 *                     representing the acceptable string values. Must
	 *                     not be {@code null}.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The matching enumeration constant.
	 *
	 * @throws JSONRPC2Error On a bad type or bad enumeration value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public <T extends Enum<T>> T getOptEnum(final int position, 
		                                final Class<T> enumClass, 
		                                final String defaultValue)
		throws JSONRPC2Error {
		
		return getOptEnum(position, enumClass, defaultValue, false); 
	}
	
	
	/**
	 * Retrieves the specified optional enumerated parameter (from a JSON
	 * string that has a predefined set of possible values), allowing for a 
	 * case insenstive match. If it doesn't exist the method will return 
	 * the specified default value.
	 *
	 * @param position     The parameter position.
	 * @param enumClass    An enumeration type with constant names 
	 *                     representing the acceptable string values. Must
	 *                     not be {@code null}.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 * @param ignoreCase   If {@code true} a case insensitive match against
	 *                     the acceptable constant names is performed.
	 *
	 * @return The matching enumeration constant.
	 *
	 * @throws JSONRPC2Error On a bad type or bad enumeration value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public <T extends Enum<T>> T getOptEnum(final int position, 
		                                final Class<T> enumClass, 
		                                final String defaultValue, 
		                                final boolean ignoreCase)
		throws JSONRPC2Error {
		
		String value = getOpt(position, String.class, defaultValue);

		if (defaultValue == null && value == null)
			return null;
		
		T match = getEnumStringMatch(value, enumClass, ignoreCase);

		if (match == null)
			throwEnumParameterException(position, enumClass);

		return match;
	}
	
	
	/**
	 * Retrieves the specified boolean (maps from JSON true/false)
	 * parameter.
	 *
	 * @param position The parameter position.
	 *
	 * @return The parameter value as a {@code boolean}.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public boolean getBoolean(final int position)
		throws JSONRPC2Error {
	
		return get(position, Boolean.class);
	}
	
	
	/**
	 * Retrieves the specified optional boolean (maps from JSON true/false)
	 * parameter. If it doesn't exist the method will return the specified 
	 * default value.
	 *
	 * @param position     The parameter position.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist.
	 *
	 * @return The parameter value as a {@code boolean}.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public boolean getOptBoolean(final int position, final boolean defaultValue)
		throws JSONRPC2Error {
	
		return getOpt(position, Boolean.class, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified numeric parameter as an {@code int}.
	 *
	 * @param position The parameter position.
	 *
	 * @return The parameter value as an {@code int}.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or 
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public int getInt(final int position)
		throws JSONRPC2Error {
		
		Number number = get(position, Number.class);
		return number.intValue();
	}
	
	
	/**
	 * Retrieves the specified optional numeric parameter as an 
	 * {@code int}. If it doesn't exist the method will return the 
	 * specified default value.
	 *
	 * @param position     The parameter position.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist.
	 *
	 * @return The parameter value as an {@code int}.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public int getOptInt(final int position, final int defaultValue)
		throws JSONRPC2Error {
	
		Number number = getOpt(position, Number.class, defaultValue);
		return number.intValue();
	}
	
	
	/**
	 * Retrieves the specified numeric parameter as a {@code long}.
	 *
	 * @param position The parameter position.
	 *
	 * @return The parameter value as a {@code long}.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public long getLong(final int position)
		throws JSONRPC2Error {
	
		Number number = get(position, Number.class);
		return number.longValue();
	}
	
	
	/**
	 * Retrieves the specified optional numeric parameter as a 
	 * {@code long}. If it doesn't exist the method will return the 
	 * specified default value.
	 *
	 * @param position     The parameter position.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist.
	 *
	 * @return The parameter value as a {@code long}.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public long getOptLong(final int position, final long defaultValue)
		throws JSONRPC2Error {
	
		Number number = getOpt(position, Number.class, defaultValue);
		return number.longValue();
	}
	
	
	/**
	 * Retrieves the specified numeric parameter as a {@code float}.
	 *
	 * @param position The parameter position.
	 *
	 * @return The parameter value as a {@code float}.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or 
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public float getFloat(final int position)
		throws JSONRPC2Error {
	
		Number number = get(position, Number.class);
		return number.floatValue();
	}
	
	
	/**
	 * Retrieves the specified optional numeric parameter as a 
	 * {@code float}. If it doesn't exist the method will return the 
	 * specified default value.
	 *
	 * @param position     The parameter position.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist.
	 *
	 * @return The parameter value as a {@code float}.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public float getOptFloat(final int position, final float defaultValue)
		throws JSONRPC2Error {
	
		Number number = getOpt(position, Number.class, defaultValue);
		return number.floatValue();
	}
	
	
	/**
	 * Retrieves the specified numeric parameter as a {@code double}.
	 *
	 * @param position The parameter position.
	 *
	 * @return The parameter value as a {@code double}.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public double getDouble(final int position)
		throws JSONRPC2Error {
	
		Number number = get(position, Number.class);
		return number.doubleValue();
	}
	
	
	/**
	 * Retrieves the specified optional numeric parameter as a 
	 * {@code double}. If it doesn't exist the method will return the 
	 * specified default value.
	 *
	 * @param position     The parameter position.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist.
	 *
	 * @return The parameter value as a {@code double}.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public double getOptDouble(final int position, final double defaultValue)
		throws JSONRPC2Error {
	
		Number number = getOpt(position, Number.class, defaultValue);
		return number.doubleValue();
	}
	
	
	/**
	 * Retrieves the specified list (maps from JSON array) parameter.
	 *
	 * @param position The parameter position.
	 *
	 * @return The parameter value as a list.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public List<Object> getList(final int position)
		throws JSONRPC2Error {
	
		final boolean allowNull = false;
	
		return getList(position, allowNull);
	}
	
	
	/**
	 * Retrieves the specified list (maps from JSON array) parameter.
	 *
	 * @param position  The parameter position.
	 * @param allowNull If {@code true} allows a {@code null} value.
	 *
	 * @return The parameter value as a list.
	 *
	 * @throws JSONRPC2Error On a missing parameter or bad type
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	@SuppressWarnings("unchecked")
	public List<Object> getList(final int position, final boolean allowNull)
		throws JSONRPC2Error {
	
		return (List<Object>)get(position, List.class, allowNull);
	}
	
	
	/**
	 * Retrieves the specified optional list (maps from JSON array) 
	 * parameter. If it doesn't exist the method will return the specified 
	 * default value.
	 *
	 * @param position     The parameter position.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value as a list.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public List<Object> getOptList(final int position, final List<Object> defaultValue)
		throws JSONRPC2Error {
	
		final boolean allowNull = false;
	
		return getOptList(position, allowNull, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified optional list (maps from JSON array) 
	 * parameter. If it doesn't exist the method will return the specified 
	 * default value.
	 *
	 * @param position     The parameter position.
	 * @param allowNull    If {@code true} allows a {@code null} value.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value as a list.
	 *
	 * @throws JSONRPC2Error On a bad parameter type
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	@SuppressWarnings("unchecked")
	public List<Object> getOptList(final int position, 
		                       final boolean allowNull, 
		                       final List<Object> defaultValue)
		throws JSONRPC2Error {
	
		return (List<Object>)getOpt(position, List.class, allowNull, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified string array (maps from JSON array of 
	 * strings) parameter.
	 *
	 * @param position The parameter position.
	 *
	 * @return The parameter value as a string array.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String[] getStringArray(final int position)
		throws JSONRPC2Error {
	
		final boolean allowNull = false;
	
		return getStringArray(position, allowNull);
	}
	
	
	/**
	 * Retrieves the specified string array (maps from JSON array of 
	 * strings) parameter.
	 *
	 * @param position  The parameter position.
	 * @param allowNull If {@code true} allows a {@code null} value.
	 *
	 * @return The parameter value as a string array.
	 *
	 * @throws JSONRPC2Error On a missing parameter or bad type
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	@SuppressWarnings("unchecked")
	public String[] getStringArray(final int position, final boolean allowNull)
		throws JSONRPC2Error {
	
		List<Object> list = getList(position, allowNull);
		
		if (list == null)
			return null;
		
		try {
			return list.toArray(new String[list.size()]);
			
		} catch (ArrayStoreException e) {
			
			throw newArrayException(position);
		}
	}
	
	
	/**
	 * Retrieves the specified optional string array (maps from JSON array
	 * of strings) parameter. If it doesn't exist the method will return 
	 * the specified default value.
	 *
	 * @param position     The parameter position.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value as a string array.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String[] getOptStringArray(final int position, final String[] defaultValue)
		throws JSONRPC2Error {
	
		final boolean allowNull = false;
	
		return getOptStringArray(position, allowNull, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified optional string array (maps from JSON array
	 * of strings) parameter. If it doesn't exist the method will return 
	 * the specified default value.
	 *
	 * @param position     The parameter position.
	 * @param allowNull    If {@code true} allows a {@code null} value.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist.  May be {@code null}.
	 *
	 * @return The parameter value as a string array.
	 *
	 * @throws JSONRPC2Error On a bad parameter type
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String[] getOptStringArray(final int position, 
		                          final boolean allowNull, 
		                          final String[] defaultValue)
		throws JSONRPC2Error {
	
		if (! hasParameter(position))
			return defaultValue;
	
		return getStringArray(position, allowNull);
	}
	
	
	/**
	 * Retrieves the specified map (maps from JSON object) parameter.
	 *
	 * @param position The parameter position.
	 *
	 * @return The parameter value as a map.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public Map<String,Object> getMap(final int position)
		throws JSONRPC2Error {
		
		return getMap(position, false);
	}
	
	
	/**
	 * Retrieves the specified map (maps from JSON object) parameter.
	 *
	 * @param position  The parameter position.
	 * @param allowNull If {@code true} allows a {@code null} value.
	 *
	 * @return The parameter value as a map.
	 *
	 * @throws JSONRPC2Error On a missing parameter or bad type
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	@SuppressWarnings("unchecked")
	public Map<String,Object> getMap(final int position, final boolean allowNull)
		throws JSONRPC2Error {
		
		try {
			return (Map<String,Object>)get(position, Map.class, allowNull);
			
		} catch (ClassCastException e) {
			
			throw newUnexpectedParameterTypeException(position);
		}
	}
	
	
	/**
	 * Retrieves the specified optional map (maps from JSON object) 
	 * parameter. If it doesn't exist the method will return the specified 
	 * default value.
	 *
	 * @param position     The parameter position.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value as a map.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public Map<String,Object> getOptMap(final int position, final Map<String,Object> defaultValue)
		throws JSONRPC2Error {
	
		return getOptMap(position, false, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified optional map (maps from JSON object) 
	 * parameter. If it doesn't exist the method will return the specified 
	 * default value.
	 *
	 * @param position     The parameter position.
	 * @param allowNull    If {@code true} allows a {@code null} value.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist.  May be {@code null}.
	 *
	 * @return The parameter value as a map.
	 *
	 * @throws JSONRPC2Error On a bad parameter type
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	@SuppressWarnings("unchecked")
	public Map<String,Object> getOptMap(final int position, 
		                            final boolean allowNull, 
		                            final Map<String,Object> defaultValue)
		throws JSONRPC2Error {
	
		try {
			return (Map<String,Object>)getOpt(position, Map.class, allowNull, defaultValue);
			
		} catch (ClassCastException e) {
			
			throw newUnexpectedParameterTypeException(position);
		}
	}
}
