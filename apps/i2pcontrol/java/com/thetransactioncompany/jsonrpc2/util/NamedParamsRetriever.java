package com.thetransactioncompany.jsonrpc2.util;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.thetransactioncompany.jsonrpc2.JSONRPC2Error;


/**
 * Utility class for retrieving JSON-RPC 2.0 named parameters (key-value pairs
 * packed into a JSON Object). 
 *
 * <p>Provides a set of getter methods according to the expected parameter type
 * (number, string, etc.) and whether the parameter is mandatory or optional:
 *
 * <ul>
 *     <li>{@code getXXX(param_name)} for mandatory parameters, where 
 *         {@code XXX} is the expected parameter type.
 *     <li>{@code getOptXXX(param_name, default_value)} for optional 
 *         parameters, specifying a default value.
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
 * <p>Example: suppose you have a method with 3 named parameters "name", "age"
 * and "sex", where the last is optional and defaults to "female":
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
 * // Create a new retriever for named parameters
 * Map params = (Map)request.getParams();
 * NamedParamsRetriever r = new NamedParamsRetriever(params);
 *
 * try {
 *         // Extract "name" string parameter
 *         String name = r.getString("name");
 *
 *         // Extract "age" integer parameter
 *         int age = r.getInt("age");
 *
 *         // Extract optional "sex" string parameter which defaults to "female"
 *         String sex = r.getOptString("sex", "female");
 *
 * } catch (JSONRPC2Error e) {
 *         // A JSONRPC2Error.INVALID_PARAMS will be thrown to indicate
 *         // an unexpected parameter type or a missing mandatory parameter.
 *         // You can use it straight away to create the appropriate
 *         // JSON-RPC 2.0 error response.
 *         JSONRPC2Response response = new JSONRPC2Response(e, null);
 * }
 * 
 * </pre>
 *
 * @author Vladimir Dzhuvinov
 */
public class NamedParamsRetriever 
	extends ParamsRetriever {

	
	/** 
	 * The named parameters interface. 
	 */
	private Map<String,Object> params = null;


	/**
	 * Throws a JSON-RPC 2.0 error indicating one or more missing named
	 * parameters.
	 *
	 * @param names The parameter names. Must not be {@code null}.
	 *
	 * @throws JSONRPC2Error Formatted JSON-RPC 2.0 error.
	 */
	private static void throwMissingParameterException(final String... names)
		throws JSONRPC2Error {

		if (names.length == 1)
			throw JSONRPC2Error.INVALID_PARAMS.
				appendMessage(": Missing \"" + names[0] + "\" parameter");

		// Compose list of missing parameters
		StringBuilder list = new StringBuilder();

		for (String name : names) {

			if (list.length() > 0)
				list.append(',');

			list.append('"');
			list.append(name);
			list.append('"');
		}

		throw JSONRPC2Error.INVALID_PARAMS.
			appendMessage(": Missing " + list.toString() + " parameters");
	}


	/**
	 * Throws a JSON-RPC 2.0 error indicating a named parameter with an
	 * unexpected {@code null} value.
	 *
	 * @param name The parameter name. Must not be {@code null}.
	 *
	 * @throws JSONRPC2Error Formatted JSON-RPC 2.0 error.
	 */
	private static void throwNullParameterException(final String name)
		throws JSONRPC2Error {

		throw JSONRPC2Error.INVALID_PARAMS.
			appendMessage(": Parameter \"" + name + "\" must not be null");
	}


	/**
	 * Throws a JSON-RPC 2.0 error indicating a named parameter with an
	 * unexpected enumerated value.
	 *
	 * @param name        The parameter name.
	 * @param enumStrings The acceptable string values. Must not be 
	 *                    {@code null}.
	 *
	 * @throws JSONRPC2Error Formatted JSON-RPC 2.0 error.
	 */
	private static void throwEnumParameterException(final String name, 
		                                        final String[] enumStrings)
		throws JSONRPC2Error {

		StringBuilder msg = new StringBuilder(": Enumerated parameter \"" + name + "\" must have values ");

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
	 * Throws a JSON-RPC 2.0 error indicating a named parameter with an
	 * unexpected enumerated value.
	 *
	 * @param name      The parameter name.
	 * @param enumClass The enumeration class specifying the acceptable 
	 *                  string values. Must not be {@code null}.
	 *
	 * @throws JSONRPC2Error Formatted JSON-RPC 2.0 error.
	 */
	private static <T extends Enum<T>> void throwEnumParameterException(final String name, 
		                                                            final Class<T> enumClass)
		throws JSONRPC2Error {

		StringBuilder msg = new StringBuilder(": Enumerated parameter \"" + name + "\" must have values ");

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
	 * Creates a JSON-RPC 2.0 error indicating a named parameter with an
	 * unexpected JSON type.
	 *
	 * @param name The parameter name. Must not be {@code null}.
	 *
	 * @return Formatted JSON-RPC 2.0 error.
	 */
	private static JSONRPC2Error newUnexpectedParameterTypeException(final String name) {

		return JSONRPC2Error.INVALID_PARAMS.
			appendMessage(": Parameter \"" + name + "\" has an unexpected JSON type");
	}


	/**
	 * Creates a JSON-RPC 2.0 error indicating an array exception.
	 *
	 * @param name The parameter name. Must not be {@code null}.
	 *
	 * @return Formatted JSON-RPC 2.0 error.
	 */
	private static JSONRPC2Error newArrayException(final String name) {

		return JSONRPC2Error.INVALID_PARAMS.
			appendMessage(": Parameter \"" + name + "\" caused an array exception");
	}


	/** 
	 * Creates a new named parameters retriever from the specified 
	 * key-value map.
	 *
	 * @param params The named parameters map. Must not be {@code null}.
	 */
	public NamedParamsRetriever(final Map<String,Object> params) {
	
		if (params == null)
			throw new IllegalArgumentException("The parameters map must not be null");

		this.params = params;
	}


	/**
	 * Gets the named parameters for this retriever.
	 *
	 * @return The named parameters.
	 */
	public Map<String,Object> getParams() {

		return params;
	}
	
	
	@Override
	public int size() {
	
		return params.size();
	}
	
	
	/** 
	 * Returns {@code true} if a parameter by the specified name exists, 
	 * else {@code false}.
	 *
	 * @param name The parameter name.
	 *
	 * @return {@code true} if the parameter exists, else {@code false}.
	 */
	public boolean hasParam(final String name) {

		return params.containsKey(name);
	}


	/**
	 * @see #hasParam
	 */
	@Deprecated
	public boolean hasParameter(final String name) {

		return hasParam(name);
	}
	
	
	/**
	 * Returns {@code true} if the parameters by the specified names exist,
	 * else {@code false}.
	 *
	 * @param names The parameter names. Must not be {@code null}.
	 *
	 * @return {@code true} if the parameters exist, else {@code false}.
	 */
	public boolean hasParams(final String[] names) {
	
		return hasParams(names, null);
	}


	/**
	 * @see #hasParams(String[])
	 */
	@Deprecated
	public boolean hasParameters(final String[] names) {

		return hasParams(names);
	}
	
	
	/**
	 * Returns {@code true} if the parameters by the specified mandatory
	 * names exist, {@code false} if any mandatory name is missing or a 
	 * name outside the mandatory and optional is present.
	 *
	 * @param mandatoryNames The expected mandatory parameter names. Must
	 *                       not be {@code null}.
	 * @param optionalNames  The expected optional parameter names,
	 *                       empty array or {@code null} if none.
	 *
	 * @return {@code true} if the specified mandatory names and only any 
	 *         of the optional are present, else {@code false}.
	 */
	public boolean hasParams(final String[] mandatoryNames, 
		                 final String[] optionalNames) {
	
		// Do shallow copy of params
		Map paramsCopy = (Map)((HashMap)params).clone();
	
		// Pop the mandatory names
		for (String name: mandatoryNames) {
			
			if (paramsCopy.containsKey(name))
				paramsCopy.remove(name);
			else
				return false;
		}
		
		// Pop the optional names (if any specified)
		if (optionalNames != null) {
		
			for (String name: optionalNames) {

				if (paramsCopy.containsKey(name))
					paramsCopy.remove(name);
			}
		}
		
		// Any remaining keys that shouldn't be there?
		int remainingKeys = paramsCopy.size();

		return remainingKeys == 0;
	}


	/**
	 * @see #hasParams(String[], String[])
	 */
	@Deprecated
	public boolean hasParameters(final String[] mandatoryNames, 
		                     final String[] optionalNames) {

		return hasParams(mandatoryNames, optionalNames);
	}
	
	
	/**
	 * Returns the names of all available parameters.
	 *
	 * @return The parameter names.
	 */
	public String[] getNames() {
	
		Set<String> keySet = params.keySet();
		
		return keySet.toArray(new String[keySet.size()]);
	}
	
	
	/**
	 * Throws a {@code JSONRPC2Error.INVALID_PARAMS} if the specified
	 * names aren't present in the parameters, or names outside the
	 * specified are contained.
	 *
	 * <p>You may use this method to a fire a proper JSON-RPC 2.0 error
	 * on a missing or unexpected mandatory parameter name.
	 *
	 * @param mandatoryNames The expected parameter names. Must not be
	 *                       {@code null}.
	 *
	 * @throws JSONRPC2Error On a missing parameter name or names outside
	 *                       the specified 
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public void ensureParams(final String[] mandatoryNames)
		throws JSONRPC2Error {
	
		ensureParameters(mandatoryNames, null);
	}


	/**
	 * @see #ensureParams(String[])
	 */
	@Deprecated
	public void ensureParameters(final String[] mandatoryNames)
		throws JSONRPC2Error {

		ensureParams(mandatoryNames);
	}
	
	
	/**
	 * Throws a {@code JSONRPC2Error.INVALID_PARAMS} if the specified
	 * mandatory names aren't contained in the parameters, or names outside 
	 * the specified mandatory and optional are present.
	 *
	 * <p>You may use this method to a fire a proper JSON-RPC 2.0 error
	 * on a missing or unexpected mandatory parameter name.
	 *
	 * @param mandatoryNames The expected mandatory parameter names. Must
	 *                       not be {@code null}.
	 * @param optionalNames  The expected optional parameter names,
	 *                       empty array or {@code null} if none.
	 *
	 * @throws JSONRPC2Error On a missing mandatory parameter name or names 
	 *                       outside the specified mandatory and optional
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public void ensureParams(final String[] mandatoryNames, 
		                 final String[] optionalNames)
		throws JSONRPC2Error {
	
		if (! hasParameters(mandatoryNames, optionalNames))
			throwMissingParameterException(mandatoryNames);
	}


	/**
	 * @see #ensureParams(String[], String[])
	 */
	@Deprecated
	public void ensureParameters(final String[] mandatoryNames, 
		                     final String[] optionalNames)
		throws JSONRPC2Error {

		ensureParams(mandatoryNames, optionalNames);
	}
	
	
	/**
	 * Throws a {@code JSONRPC2Error.INVALID_PARAMS} exception if there is
	 * no parameter by the specified name.
	 *
	 * <p>You may use this method to fire the proper JSON-RPC 2.0 error
	 * on a missing mandatory parameter.
	 *
	 * @param name The parameter name.
	 *
	 * @throws JSONRPC2Error On a missing parameter
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public void ensureParam(final String name)
		throws JSONRPC2Error {
		
		if (! hasParameter(name))
			throwMissingParameterException(name);
	}


	/**
	 * @see #ensureParam(String)
	 */
	@Deprecated
	public void ensureParameter(final String name)
		throws JSONRPC2Error {

		ensureParam(name);
	}
	
	
	/**
	 * Throws a {@code JSONRPC2Error.INVALID_PARAMS} exception if there is
	 * no parameter by the specified name, its value is {@code null}, or 
	 * its type doesn't map to the specified.
	 * 
	 * <p>You may use this method to fire the proper JSON-RPC 2.0 error
	 * on a missing or badly-typed mandatory parameter.
	 *
	 * @param name  The parameter name.
	 * @param clazz The corresponding Java class that the parameter should 
	 *              map to (any one of the return types of the 
	 *              {@code getXXX()} getter methods. Set to 
	 *              {@code Object.class} to allow any type. Must not be
	 *              {@code null}.
	 *
	 * @throws JSONRPC2Error On a missing parameter, {@code null} value or 
	 *                       bad type ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public <T> void ensureParam(final String name, final Class<T> clazz)
		throws JSONRPC2Error {
		
		ensureParameter(name, clazz, false);
	}


	/**
	 * @see #ensureParam(String, Class)
	 */
	@Deprecated
	public <T> void ensureParameter(final String name, final Class<T> clazz)
		throws JSONRPC2Error {

		ensureParam(name, clazz);
	}
	
	
	/**
	 * Throws a {@code JSONRPC2Error.INVALID_PARAMS} exception if there is
	 * no parameter by the specified name or its type doesn't map to the 
	 * specified.
	 * 
	 * <p>You may use this method to fire the proper JSON-RPC 2.0 error
	 * on a missing or badly-typed mandatory parameter.
	 *
	 * @param name      The parameter name.
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
	public <T> void ensureParam(final String name, 
		                    final Class<T> clazz, 
		                    final boolean allowNull)
		throws JSONRPC2Error {
		
		// First, check existence only
		ensureParameter(name);
		
		// Now check type
		Object value = params.get(name);
		
		if (value == null) {
			
			if (allowNull)
				return; // ok
		
			else
				throwNullParameterException(name);
		}
		
		if (! clazz.isAssignableFrom(value.getClass()))
			throw newUnexpectedParameterTypeException(name);
	}


	/**
	 * @see #ensureParam(String, Class, boolean)
	 */
	@Deprecated
	public <T> void ensureParameter(final String name, 
		                        final Class<T> clazz, 
		                        final boolean allowNull)
		throws JSONRPC2Error {

		ensureParam(name, clazz, allowNull);
	}
	
	
	/**
	 * Retrieves the specified parameter which can be of any type. Use this
	 * generic getter if you want to cast the value yourself. Otherwise 
	 * look at the typed {@code get*} methods.
	 *
	 * @param name The parameter name.
	 *
	 * @return The parameter value.
	 *
	 * @throws JSONRPC2Error On a missing parameter
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public Object get(final String name)
		throws JSONRPC2Error {
	
		ensureParameter(name);
	
		return params.get(name);
	}
	
	
	/**
	 * Retrieves the specified parameter which must map to the provided
	 * class (use the appropriate wrapper class for primitive types).
	 *
	 * @param name  The parameter name.
	 * @param clazz The corresponding Java class that the parameter should 
	 *              map to (any one of the return types of the 
	 *              {@code getXXX()}  getter methods. Set to 
	 *              {@code Object.class} to allow any type. Must not be
	 *              {@code null}.
	 *
	 * @return The parameter value.
	 *
	 * @throws JSONRPC2Error On a missing parameter, {@code null} value or 
	 *                       bad type ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public <T> T get(final String name, final Class<T> clazz)
		throws JSONRPC2Error {
	
		return get(name, clazz, false);
	}
	
	
	/**
	 * Retrieves the specified parameter which must map to the provided
	 * class (use the appropriate wrapper class for primitive types).
	 *
	 * @param name      The parameter name.
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
	public <T> T get(final String name, final Class<T> clazz, final boolean allowNull)
		throws JSONRPC2Error {
	
		ensureParameter(name, clazz, allowNull);
		
		try {
			return (T)params.get(name);
			
		} catch (ClassCastException e) {
			
			throw newUnexpectedParameterTypeException(name);
		}
	}
	
	
	/**
	 * Retrieves the specified optional parameter which must map to the
	 * provided class (use the appropriate wrapper class for primitive 
	 * types). If the parameter doesn't exist the method returns the 
	 * specified default value.
	 *
	 * @param name         The parameter name.
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
	public <T> T getOpt(final String name, final Class<T> clazz, final T defaultValue)
		throws JSONRPC2Error {
	
		return getOpt(name, clazz, false, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified optional parameter which must map to the
	 * provided class (use the appropriate wrapper class for primitive 
	 * types). If the parameter doesn't exist the method returns the 
	 * specified default value.
	 *
	 * @param name         The parameter name.
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
	public <T> T getOpt(final String name, 
		            final Class<T> clazz, 
		            final boolean allowNull, 
		            final T defaultValue)
		throws JSONRPC2Error {
	
		if (! hasParameter(name))
			return defaultValue;
		
		ensureParameter(name, clazz, allowNull);
		
		try {
			return (T)params.get(name);
			
		} catch (ClassCastException e) {
			
			throw newUnexpectedParameterTypeException(name);
		}
	}
	
	
	/**
	 * Retrieves the specified string parameter.
	 *
	 * @param name The parameter name.
	 *
	 * @return The parameter value as a string.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or 
	 *                       {@code null} value 
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String getString(final String name)
		throws JSONRPC2Error {
		
		return getString(name, false);
	}
	
	
	/**
	 * Retrieves the specified string parameter.
	 *
	 * @param name      The parameter name.
	 * @param allowNull If {@code true} allows a {@code null} value.
	 *
	 * @return The parameter value as a string.
	 *
	 * @throws JSONRPC2Error On a missing parameter or bad type
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String getString(final String name, final boolean allowNull)
		throws JSONRPC2Error {
		
		return get(name, String.class, allowNull);
	}
	
	
	/**
	 * Retrieves the specified optional string parameter. If it doesn't 
	 * exist the method will return the specified default value.
	 *
	 * @param name         The parameter name.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value as a string.
	 *
	 * @throws JSONRPC2Error On a bad type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String getOptString(final String name, final String defaultValue)
		throws JSONRPC2Error {
	
		return getOptString(name, false, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified optional string parameter. If it doesn't 
	 * exist the method will return the specified default value.
	 *
	 * @param name         The parameter name.
	 * @param allowNull    If {@code true} allows a {@code null} value.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value as a string.
	 *
	 * @throws JSONRPC2Error On a bad type ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String getOptString(final String name, final boolean allowNull, final String defaultValue)
		throws JSONRPC2Error {
	
		return getOpt(name, String.class, allowNull, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified enumerated string parameter.
	 *
	 * @param name        The parameter name.
	 * @param enumStrings The acceptable string values. Must not be
	 *                    {@code null}.
	 *
	 * @return The parameter value as a string.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or 
	 *                       bad enumeration value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String getEnumString(final String name, final String[] enumStrings)
		throws JSONRPC2Error {
		
		return getEnumString(name, enumStrings, false); 
	}
	
	
	/**
	 * Retrieves the specified enumerated string parameter, allowing for a
	 * case insenstive match.
	 *
	 * @param name        The parameter name.
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
	public String getEnumString(final String name, 
		                    final String[] enumStrings, 
		                    final boolean ignoreCase)
		throws JSONRPC2Error {
		
		String value = get(name, String.class);
		
		String match = getEnumStringMatch(value, enumStrings, ignoreCase);

		if (match == null)
			throwEnumParameterException(name, enumStrings);

		return match;
	}
	
	
	/**
	 * Retrieves the specified optional enumerated string parameter.
	 *
	 * @param name         The parameter name.
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
	public String getOptEnumString(final String name, 
		                       final String[] enumStrings, 
		                       final String defaultValue)
		throws JSONRPC2Error {
		
		return getOptEnumString(name, enumStrings, defaultValue, false); 
	}
	
	
	/**
	 * Retrieves the specified optional enumerated string parameter, 
	 * allowing for a case insenstive match. If it doesn't exist the method 
	 * will return the specified default value.
	 *
	 * @param name         The parameter name.
	 * @param enumStrings  The acceptable string values. Must not be
	 *                     {@code null}.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 * @param ignoreCase   {@code true} for a case insensitive match.
	 *
	 * @return The matching parameter value as a string.
	 *
	 * @throws JSONRPC2Error On a bad type or bad enumeration value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String getOptEnumString(final String name, 
		                       final String[] enumStrings, 
		                       final String defaultValue, 
		                       final boolean ignoreCase)
		throws JSONRPC2Error {
		
		String value = getOpt(name, String.class, defaultValue);

		if (defaultValue == null && value == null)
			return null;

		String match = getEnumStringMatch(value, enumStrings, ignoreCase);

		if (match == null)
			throwEnumParameterException(name, enumStrings);

		return match;
	}
	
	
	/**
	 * Retrieves the specified enumerated parameter (from a JSON string 
	 * that has a predefined set of possible values).
	 *
	 * @param name      The parameter name.
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
	public <T extends Enum<T>> T getEnum(final String name, final Class<T> enumClass)
		throws JSONRPC2Error {
		
		return getEnum(name, enumClass, false);
	}
	
	
	/**
	 * Retrieves the specified enumerated parameter (from a JSON string 
	 * that has a predefined set of possible values), allowing for a case 
	 * insensitive match.
	 *
	 * @param name       The parameter name.
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
	public <T extends Enum<T>> T getEnum(final String name, 
		                             final Class<T> enumClass, 
		                             final boolean ignoreCase)
		throws JSONRPC2Error {
		
		String value = get(name, String.class);
		
		T match = getEnumStringMatch(value, enumClass, ignoreCase);

		if (match == null)
			throwEnumParameterException(name, enumClass);

		return match;
	}
	
	
	/**
	 * Retrieves the specified optional enumerated parameter (from a JSON
	 * string that has a predefined set of possible values).
	 *
	 * @param name         The parameter name.
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
	public <T extends Enum<T>> T getOptEnum(final String name, 
		                                final Class<T> enumClass, 
		                                final T defaultValue)
		throws JSONRPC2Error {
		
		return getOptEnum(name, enumClass, defaultValue, false); 
	}
	
	
	/**
	 * Retrieves the specified optional enumerated parameter (from a JSON
	 * string that has a predefined set of possible values), allowing for 
	 * a case insenstive match. If it doesn't exist the method will return 
	 * the specified default value.
	 *
	 * @param name         The parameter name.
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
	public <T extends Enum<T>> T getOptEnum(final String name, 
		                                final Class<T> enumClass, 
		                                final T defaultValue, 
		                                final boolean ignoreCase)
		throws JSONRPC2Error {

		String value;

		if (defaultValue != null)
			value = getOpt(name, String.class, defaultValue.toString());

		else 
			value = getOpt(name, String.class, null);

		if (defaultValue == null && value == null)
			return null;

		T match = getEnumStringMatch(value, enumClass, ignoreCase);

		if (match == null)
			throwEnumParameterException(name, enumClass);

		return match;
	}
	
	
	/**
	 * Retrieves the specified boolean (maps from JSON true/false)
	 * parameter.
	 *
	 * @param name The parameter name.
	 *
	 * @return The parameter value as a {@code boolean}.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public boolean getBoolean(final String name)
		throws JSONRPC2Error {
	
		return get(name, Boolean.class);
	}
	
	
	/**
	 * Retrieves the specified optional boolean (maps from JSON true/false)
	 * parameter. If it doesn't exist the method will return the specified 
	 * default value.
	 *
	 * @param name         The parameter name.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist.
	 *
	 * @return The parameter value as a {@code boolean}.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public boolean getOptBoolean(final String name, final boolean defaultValue)
		throws JSONRPC2Error {
	
		return getOpt(name, Boolean.class, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified numeric parameter as an {@code int}.
	 *
	 * @param name The parameter name.
	 *
	 * @return The parameter value as an {@code int}.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or 
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public int getInt(final String name)
		throws JSONRPC2Error {
		
		Number number = get(name, Number.class);
		return number.intValue();
	}
	
	
	/**
	 * Retrieves the specified optional numeric parameter as an 
	 * {@code int}. If it doesn't exist the method will return the 
	 * specified default value.
	 *
	 * @param name         The parameter name.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist.
	 *
	 * @return The parameter value as an {@code int}.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public int getOptInt(final String name, final int defaultValue)
		throws JSONRPC2Error {
	
		Number number = getOpt(name, Number.class, defaultValue);
		return number.intValue();
	}
	
	
	/**
	 * Retrieves the specified numeric parameter as a {@code long}.
	 *
	 * @param name The parameter name.
	 *
	 * @return The parameter value as a {@code long}.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public long getLong(final String name)
		throws JSONRPC2Error {
	
		Number number = get(name, Number.class);
		return number.longValue();
	}
	
	
	/**
	 * Retrieves the specified optional numeric parameter as a 
	 * {@code long}. If it doesn't exist the method will return the 
	 * specified default value.
	 *
	 * @param name         The parameter name.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist.
	 *
	 * @return The parameter value as a long.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public long getOptLong(final String name, final long defaultValue)
		throws JSONRPC2Error {
	
		Number number = getOpt(name, Number.class, defaultValue);
		return number.longValue();
	}
	
	
	/**
	 * Retrieves the specified numeric parameter as a {@code float}.
	 *
	 * @param name The parameter name.
	 *
	 * @return The parameter value as a {@code float}.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or 
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public float getFloat(final String name)
		throws JSONRPC2Error {
	
		Number number = get(name, Number.class);
		return number.floatValue();
	}
	
	
	/**
	 * Retrieves the specified optional numeric parameter as a 
	 * {@code float}. If it doesn't exist the method will return the 
	 * specified default value.
	 *
	 * @param name         The parameter name.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist.
	 *
	 * @return The parameter value as a {@code float}.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public float getOptFloat(final String name, final float defaultValue)
		throws JSONRPC2Error {
	
		Number number = getOpt(name, Number.class, defaultValue);
		return number.floatValue();
	}
	
	
	/**
	 * Retrieves the specified numeric parameter as a {@code double}.
	 *
	 * @param name The parameter name.
	 *
	 * @return The parameter value as a {@code double}.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public double getDouble(final String name)
		throws JSONRPC2Error {
	
		Number number = get(name, Number.class);
		return number.doubleValue();
	}
	
	
	/**
	 * Retrieves the specified optional numeric parameter as a 
	 * {@code double}. If it doesn't exist the method will return the 
	 * specified default value.
	 *
	 * @param name         The parameter name.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist.
	 *
	 * @return The parameter value as a {@code double}.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public double getOptDouble(final String name, final double defaultValue)
		throws JSONRPC2Error {
	
		Number number = getOpt(name, Number.class, defaultValue);
		return number.doubleValue();
	}
	
	
	/**
	 * Retrieves the specified list (maps from JSON array) parameter.
	 *
	 * @param name The parameter name.
	 *
	 * @return The parameter value as a list.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public List<Object> getList(final String name)
		throws JSONRPC2Error {
	
		final boolean allowNull = false;
	
		return getList(name, allowNull);
	}
	
	
	/**
	 * Retrieves the specified list (maps from JSON array) parameter.
	 *
	 * @param name      The parameter name.
	 * @param allowNull If {@code true} allows a {@code null} value.
	 *
	 * @return The parameter value as a list.
	 *
	 * @throws JSONRPC2Error On a missing parameter or bad type
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	@SuppressWarnings("unchecked")
	public List<Object> getList(final String name, final boolean allowNull)
		throws JSONRPC2Error {
	
		return (List<Object>)get(name, List.class, allowNull);
	}
	
	
	/**
	 * Retrieves the specified optional list (maps from JSON array) 
	 * parameter. If it doesn't exist the method will return the specified 
	 * default value.
	 *
	 * @param name         The parameter name.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value as a list.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public List<Object> getOptList(final String name, final List<Object> defaultValue)
		throws JSONRPC2Error {
	
		final boolean allowNull = false;
	
		return getOptList(name, allowNull, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified optional list (maps from JSON array) 
	 * parameter. If it doesn't exist the method will return the specified 
	 * default value.
	 *
	 * @param name         The parameter name.
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
	public List<Object> getOptList(final String name, 
		                       final boolean allowNull, 
		                       final List<Object> defaultValue)
		throws JSONRPC2Error {
	
		return (List<Object>)getOpt(name, List.class, allowNull, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified string array (maps from JSON array of 
	 * strings) parameter.
	 *
	 * @param name The parameter name.
	 *
	 * @return The parameter value as a string array.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String[] getStringArray(final String name)
		throws JSONRPC2Error {
	
		final boolean allowNull = false;
	
		return getStringArray(name, allowNull);
	}
	
	
	/**
	 * Retrieves the specified string array (maps from JSON array of 
	 * strings) parameter.
	 *
	 * @param name      The parameter name.
	 * @param allowNull If {@code true} allows a {@code null} value.
	 *
	 * @return The parameter value as a string array.
	 *
	 * @throws JSONRPC2Error On a missing parameter or bad type
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	@SuppressWarnings("unchecked")
	public String[] getStringArray(final String name, final boolean allowNull)
		throws JSONRPC2Error {
	
		List<Object> list = getList(name, allowNull);
		
		if (list == null)
			return null;
		
		try {
			return list.toArray(new String[list.size()]);
			
		} catch (ArrayStoreException e) {
			
			throw newArrayException(name);
		}
	}
	
	
	/**
	 * Retrieves the specified optional string array (maps from JSON array
	 * of strings) parameter. If it doesn't exist the method will return 
	 * the specified default value.
	 *
	 * @param name         The parameter name.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value as a string array.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String[] getOptStringArray(final String name, final String[] defaultValue)
		throws JSONRPC2Error {
	
		final boolean allowNull = false;
	
		return getOptStringArray(name, allowNull, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified optional string array (maps from JSON array
	 * of strings) parameter. If it doesn't exist the method will return 
	 * the specified default value.
	 *
	 * @param name         The parameter name.
	 * @param allowNull    If {@code true} allows a {@code null} value.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value as a string array.
	 *
	 * @throws JSONRPC2Error On a bad parameter type
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public String[] getOptStringArray(final String name, 
		                          final boolean allowNull, 
		                          final String[] defaultValue)
		throws JSONRPC2Error {
	
		if (! hasParameter(name))
			return defaultValue;
	
		return getStringArray(name, allowNull);
	}
	
	
	/**
	 * Retrieves the specified map (maps from JSON object) parameter.
	 *
	 * @param name The parameter name.
	 *
	 * @return The parameter value as a map.
	 *
	 * @throws JSONRPC2Error On a missing parameter, bad type or
	 *                       {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public Map<String,Object> getMap(final String name)
		throws JSONRPC2Error {
		
		return getMap(name, false);
	}
	
	
	/**
	 * Retrieves the specified map (maps from JSON object) parameter.
	 *
	 * @param name      The parameter name.
	 * @param allowNull If {@code true} allows a {@code null} value.
	 *
	 * @return The parameter value as a map.
	 *
	 * @throws JSONRPC2Error On a missing parameter or bad type
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	@SuppressWarnings("unchecked")
	public Map<String,Object> getMap(final String name, final boolean allowNull)
		throws JSONRPC2Error {
		
		try {
			return (Map<String,Object>)get(name, Map.class, allowNull);
			
		} catch (ClassCastException e) {
			
			throw newUnexpectedParameterTypeException(name);
		}
	}
	
	
	/**
	 * Retrieves the specified optional map (maps from JSON object) 
	 * parameter. If it doesn't exist the method will return the specified 
	 * default value.
	 *
	 * @param name         The parameter name.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value as a map.
	 *
	 * @throws JSONRPC2Error On a bad parameter type or {@code null} value
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	public Map<String,Object> getOptMap(final String name, final Map<String,Object> defaultValue)
		throws JSONRPC2Error {
	
		return getOptMap(name, false, defaultValue);
	}
	
	
	/**
	 * Retrieves the specified optional map (maps from JSON object) 
	 * parameter. If it doesn't exist the method will return the specified 
	 * default value.
	 *
	 * @param name         The parameter name.
	 * @param allowNull    If {@code true} allows a {@code null} value.
	 * @param defaultValue The default return value if the parameter 
	 *                     doesn't exist. May be {@code null}.
	 *
	 * @return The parameter value as a map.
	 *
	 * @throws JSONRPC2Error On a bad parameter type
	 *                       ({@link JSONRPC2Error#INVALID_PARAMS}).
	 */
	@SuppressWarnings("unchecked")
	public Map<String,Object> getOptMap(final String name, 
		                            final boolean allowNull, 
		                            final Map<String,Object> defaultValue)
		throws JSONRPC2Error {
		
		try {
			return (Map<String,Object>)getOpt(name, Map.class, allowNull, defaultValue);
			
		} catch (ClassCastException e) {
			
			throw newUnexpectedParameterTypeException(name);
		}
	}
}
