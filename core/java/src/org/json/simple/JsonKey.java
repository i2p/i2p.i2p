package org.json.simple;

/** Should be implemented by Enums so that keys are easily maintained.
 * @since 2.3.0 */
public interface JsonKey{
	/** The json-simple library uses a String for its keys.
	 * @return a String representing the JsonKey. */
	public String getKey();

	/** A reasonable value for the key; such as a valid default, error value, or null.
	 * @return an Object representing a reasonable general case value for the key. */
	public Object getValue();
}
