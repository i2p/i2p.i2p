package com.thetransactioncompany.jsonrpc2.util;


/**
 * The base abstract class for the JSON-RPC 2.0 parameter retrievers.
 *
 * @author Vladimir Dzhuvinov
 */
public abstract class ParamsRetriever {

	
	/**
	 * Returns the parameter count.
	 *
	 * @return The parameters count.
	 */
	public abstract int size();


	/**
	 * Matches a string against an array of acceptable values.
	 *
	 * @param input       The string to match.
	 * @param enumStrings The acceptable string values. Must not be 
	 *                    {@code null}.
	 * @param ignoreCase  {@code true} for a case insensitive match.
	 *
	 * @return The matching string value, {@code null} if no match was
	 *         found.
	 */
	protected static String getEnumStringMatch(final String input, 
		                                   final String[] enumStrings, 
		                                   final boolean ignoreCase) {
	
		for (final String en: enumStrings) {
		
			if (ignoreCase) {
				if (en.equalsIgnoreCase(input))
					return en;
			}
			else {
				if (en.equals(input))
					return en;
			}
		}

		return null;
	}
	
	
	/**
	 * Matches a string against an enumeration of acceptable values.
	 *
	 * @param input      The string to match.
	 * @param enumClass  The enumeration class specifying the acceptable 
	 *                   string values. Must not be {@code null}.
	 * @param ignoreCase {@code true} for a case insensitive match.
	 *
	 * @return The matching enumeration constant, {@code null} if no match
	 *         was found.
	 */
	protected static <T extends Enum<T>> T getEnumStringMatch(final String input, 
		                                                  final Class<T> enumClass, 
		                                                  final boolean ignoreCase) {
		
		for (T en: enumClass.getEnumConstants()) {
		
			if (ignoreCase) {
				if (en.toString().equalsIgnoreCase(input))
					return en;
			}
			else {
				if (en.toString().equals(input))
					return en;
			}
		}
		
		return null;
	}
}
