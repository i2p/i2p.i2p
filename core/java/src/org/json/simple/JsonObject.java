/* Copyright 2016-2017 Clifton Labs
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. */
package org.json.simple;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** JsonObject is a common non-thread safe data format for string to data mappings. The contents of a JsonObject are
 * only validated as JSON values on serialization. Meaning all values added to a JsonObject must be recognized by the
 * Jsoner for it to be a true 'JsonObject', so it is really a JsonableHashMap that will serialize to a JsonObject if all of
 * its contents are valid JSON.
 * @see Jsoner
 * @since 2.0.0 */
public class JsonObject extends HashMap<String, Object> implements Jsonable{
	/** The serialization version this class is compatible
	 * with. This value doesn't need to be incremented if and only if the only changes to occur were updating comments,
	 * updating javadocs, adding new
	 * fields to the class, changing the fields from static to non-static, or changing the fields from transient to non
	 * transient. All other changes require this number be incremented. */
	private static final long serialVersionUID = 2L;

	/** Instantiates an empty JsonObject. */
	public JsonObject(){
		super();
	}

	/** Instantiate a new JsonObject by accepting a map's entries, which could lead to de/serialization issues of the
	 * resulting JsonObject since the entry values aren't validated as JSON values.
	 * @param map represents the mappings to produce the JsonObject with. */
	public JsonObject(final Map<String, ?> map){
		super(map);
	}
	
	/** Ensures the given keys are present.
	 * @param keys represents the keys that must be present.
	 * @throws NoSuchElementException if any of the given keys are missing.
	 * @since 2.3.0 to ensure critical keys are in the JsonObject. */
	public void requireKeys(final JsonKey... keys){
		/* Track all of the missing keys. */
		final Set<JsonKey> missing = new HashSet<>();
		for(final JsonKey k : keys){
			if(!this.containsKey(k.getKey())){
				missing.add(k);
			}
		}
		if(!missing.isEmpty()){
			/* Report any missing keys in the exception. */
			final StringBuilder sb = new StringBuilder();
			for(final JsonKey k : missing){
				sb.append(k.getKey()).append(", ");
			}
			sb.setLength(sb.length() - 2);
			final String s = missing.size() > 1 ? "s" : "";
			throw new NoSuchElementException("A JsonObject is missing required key" + s + ": " + sb.toString());
		}
	}

	/** A convenience method that assumes there is a BigDecimal, Number, or String at the given key. If a Number is
	 * there its Number#toString() is used to construct a new BigDecimal(String). If a String is there it is used to
	 * construct a new BigDecimal(String).
	 * @param key representing where the value ought to be paired with.
	 * @return a BigDecimal representing the value paired with the key.
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see BigDecimal
	 * @see Number#toString()
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public BigDecimal getBigDecimal(final JsonKey key){
		Object returnable = this.get(key.getKey());
		if(returnable instanceof BigDecimal){
			/* Success there was a BigDecimal or it defaulted. */
		}else if(returnable instanceof Number){
			/* A number can be used to construct a BigDecimal */
			returnable = new BigDecimal(returnable.toString());
		}else if(returnable instanceof String){
			/* A number can be used to construct a BigDecimal */
			returnable = new BigDecimal((String)returnable);
		}
		return (BigDecimal)returnable;
	}

	/** A convenience method that assumes there is a BigDecimal, Number, or String at the given key. If a Number is
	 * there its Number#toString() is used to construct a new BigDecimal(String). If a String is there it is used to
	 * construct a new BigDecimal(String).
	 * @param key representing where the value ought to be stored at.
	 * @return the value stored at the key.
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see BigDecimal
	 * @see Number#toString()
	 * @deprecated 2.3.0 in favor of {@link #getBigDecimal(JsonKey)} */
	@Deprecated
	public BigDecimal getBigDecimal(final String key){
		Object returnable = this.get(key);
		if(returnable instanceof BigDecimal){
			/* Success there was a BigDecimal or it defaulted. */
		}else if(returnable instanceof Number){
			/* A number can be used to construct a BigDecimal */
			returnable = new BigDecimal(returnable.toString());
		}else if(returnable instanceof String){
			/* A number can be used to construct a BigDecimal */
			returnable = new BigDecimal((String)returnable);
		}
		return (BigDecimal)returnable;
	}

	/** A convenience method that assumes there is a BigDecimal, Number, or String at the given key. If a Number is
	 * there its Number#toString() is used to construct a new BigDecimal(String). If a String is there it is used to
	 * construct a new BigDecimal(String).
	 * @param key representing where the value ought to be paired with.
	 * @return a BigDecimal representing the value paired with the key or JsonKey#getValue() if the key isn't present.
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see BigDecimal
	 * @see Number#toString()
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public BigDecimal getBigDecimalOrDefault(final JsonKey key){
		Object returnable;
		if(this.containsKey(key.getKey())){
			returnable = this.get(key.getKey());
		}else{
			returnable = key.getValue();
		}
		if(returnable instanceof BigDecimal){
			/* Success there was a BigDecimal or it defaulted. */
		}else if(returnable instanceof Number){
			/* A number can be used to construct a BigDecimal */
			returnable = new BigDecimal(returnable.toString());
		}else if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal */
			returnable = new BigDecimal((String)returnable);
		}
		return (BigDecimal)returnable;
	}

	/** A convenience method that assumes there is a BigDecimal, Number, or String at the given key. If a Number is
	 * there its Number#toString() is used to construct a new BigDecimal(String). If a String is there it is used to
	 * construct a new BigDecimal(String).
	 * @param key representing where the value ought to be stored at.
	 * @param defaultValue representing what is returned when the key isn't in the JsonObject.
	 * @return the value stored at the key or the default provided if the key doesn't exist.
	 * @throws ClassCastException if there was a value but didn't match the assumed return types.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see BigDecimal
	 * @see Number#toString()
	 * @deprecated 2.3.0 in favor of {@link #getBigDecimalOrDefault(JsonKey)} */
	@Deprecated
	public BigDecimal getBigDecimalOrDefault(final String key, final BigDecimal defaultValue){
		Object returnable;
		if(this.containsKey(key)){
			returnable = this.get(key);
		}else{
			return defaultValue;
		}
		if(returnable instanceof BigDecimal){
			/* Success there was a BigDecimal or it defaulted. */
		}else if(returnable instanceof Number){
			/* A number can be used to construct a BigDecimal */
			returnable = new BigDecimal(returnable.toString());
		}else if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal */
			returnable = new BigDecimal((String)returnable);
		}
		return (BigDecimal)returnable;
	}

	/** A convenience method that assumes there is a Boolean or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return a Boolean representing the value paired with the key.
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public Boolean getBoolean(final JsonKey key){
		Object returnable = this.get(key.getKey());
		if(returnable instanceof String){
			returnable = Boolean.valueOf((String)returnable);
		}
		return (Boolean)returnable;
	}

	/** A convenience method that assumes there is a Boolean or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @return the value stored at the key.
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @deprecated 2.3.0 in favor of {@link #getBoolean(JsonKey)} */
	@Deprecated
	public Boolean getBoolean(final String key){
		Object returnable = this.get(key);
		if(returnable instanceof String){
			returnable = Boolean.valueOf((String)returnable);
		}
		return (Boolean)returnable;
	}

	/** A convenience method that assumes there is a Boolean or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return a Boolean representing the value paired with the key or JsonKey#getValue() if the key isn't present.
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public Boolean getBooleanOrDefault(final JsonKey key){
		Object returnable;
		if(this.containsKey(key.getKey())){
			returnable = this.get(key.getKey());
		}else{
			returnable = key.getValue();
		}
		if(returnable instanceof String){
			returnable = Boolean.valueOf((String)returnable);
		}
		return (Boolean)returnable;
	}

	/** A convenience method that assumes there is a Boolean or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @param defaultValue representing what is returned when the key isn't in the JsonObject.
	 * @return the value stored at the key or the default provided if the key doesn't exist.
	 * @throws ClassCastException if there was a value but didn't match the assumed return type.
	 * @deprecated 2.3.0 in favor of {@link #getBooleanOrDefault(JsonKey)} */
	@Deprecated
	public Boolean getBooleanOrDefault(final String key, final boolean defaultValue){
		Object returnable;
		if(this.containsKey(key)){
			returnable = this.get(key);
		}else{
			return defaultValue;
		}
		if(returnable instanceof String){
			returnable = Boolean.valueOf((String)returnable);
		}
		return (Boolean)returnable;
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return a Byte representing the value paired with the key (which may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#byteValue()
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public Byte getByte(final JsonKey key){
		Object returnable = this.get(key.getKey());
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).byteValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @return the value stored at the key (which may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#byteValue()
	 * @deprecated 2.3.0 in favor of {@link #getByte(JsonKey)} */
	@Deprecated
	public Byte getByte(final String key){
		Object returnable = this.get(key);
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).byteValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return a Byte representing the value paired with the key or JsonKey#getValue() if the key isn't present (which
	 *         may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#byteValue()
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public Byte getByteOrDefault(final JsonKey key){
		Object returnable;
		if(this.containsKey(key.getKey())){
			returnable = this.get(key.getKey());
		}else{
			returnable = key.getValue();
		}
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).byteValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @param defaultValue representing what is returned when the key isn't in the JsonObject.
	 * @return the value stored at the key (which may involve rounding or truncation) or the default provided if the key
	 *         doesn't exist.
	 * @throws ClassCastException if there was a value but didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#byteValue()
	 * @deprecated 2.3.0 in favor of {@link #getByteOrDefault(JsonKey)} */
	@Deprecated
	public Byte getByteOrDefault(final String key, final byte defaultValue){
		Object returnable;
		if(this.containsKey(key)){
			returnable = this.get(key);
		}else{
			return defaultValue;
		}
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).byteValue();
	}

	/** A convenience method that assumes there is a Collection at the given key.
	 * @param <T> the kind of collection to expect at the key. Note unless manually added, collection values will be a
	 *        JsonArray.
	 * @param key representing where the value ought to be paired with.
	 * @return a Collection representing the value paired with the key.
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	@SuppressWarnings("unchecked")
	public <T extends Collection<?>> T getCollection(final JsonKey key){
		/* The unchecked warning is suppressed because there is no way of guaranteeing at compile time the cast will
		 * work. */
		return (T)this.get(key.getKey());
	}

	/** A convenience method that assumes there is a Collection at the given key.
	 * @param <T> the kind of collection to expect at the key. Note unless manually added, collection values will be a
	 *        JsonArray.
	 * @param key representing where the value ought to be stored at.
	 * @return the value stored at the key.
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @deprecated 2.3.0 in favor of {@link #getCollection(JsonKey)} */
	@Deprecated
	@SuppressWarnings("unchecked")
	public <T extends Collection<?>> T getCollection(final String key){
		/* The unchecked warning is suppressed because there is no way of guaranteeing at compile time the cast will
		 * work. */
		return (T)this.get(key);
	}

	/** A convenience method that assumes there is a Collection at the given key.
	 * @param <T> the kind of collection to expect at the key. Note unless manually added, collection values will be a
	 *        JsonArray.
	 * @param key representing where the value ought to be paired with.
	 * @return a Collection representing the value paired with the key or JsonKey#getValue() if the key isn't present..
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	@SuppressWarnings("unchecked")
	public <T extends Collection<?>> T getCollectionOrDefault(final JsonKey key){
		/* The unchecked warning is suppressed because there is no way of guaranteeing at compile time the cast will
		 * work. */
		Object returnable;
		if(this.containsKey(key.getKey())){
			returnable = this.get(key.getKey());
		}else{
			returnable = key.getValue();
		}
		return (T)returnable;
	}

	/** A convenience method that assumes there is a Collection at the given key.
	 * @param <T> the kind of collection to expect at the key. Note unless manually added, collection values will be a
	 *        JsonArray.
	 * @param key representing where the value ought to be stored at.
	 * @param defaultValue representing what is returned when the key isn't in the JsonObject.
	 * @return the value stored at the key or the default provided if the key doesn't exist.
	 * @throws ClassCastException if there was a value but didn't match the assumed return type.
	 * @deprecated 2.3.0 in favor of {@link #getCollectionOrDefault(JsonKey)} */
	@Deprecated
	@SuppressWarnings("unchecked")
	public <T extends Collection<?>> T getCollectionOrDefault(final String key, final T defaultValue){
		/* The unchecked warning is suppressed because there is no way of guaranteeing at compile time the cast will
		 * work. */
		Object returnable;
		if(this.containsKey(key)){
			returnable = this.get(key);
		}else{
			return defaultValue;
		}
		return (T)returnable;
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return a Double representing the value paired with the key (which may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#doubleValue()
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public Double getDouble(final JsonKey key){
		Object returnable = this.get(key.getKey());
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).doubleValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @return the value stored at the key (which may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#doubleValue()
	 * @deprecated 2.3.0 in favor of {@link #getDouble(JsonKey)} */
	@Deprecated
	public Double getDouble(final String key){
		Object returnable = this.get(key);
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).doubleValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return a Double representing the value paired with the key or JsonKey#getValue() if the key isn't present (which
	 *         may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#doubleValue()
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public Double getDoubleOrDefault(final JsonKey key){
		Object returnable;
		if(this.containsKey(key.getKey())){
			returnable = this.get(key.getKey());
		}else{
			returnable = key.getValue();
		}
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).doubleValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @param defaultValue representing what is returned when the key isn't in the JsonObject.
	 * @return the value stored at the key (which may involve rounding or truncation) or the default provided if the key
	 *         doesn't exist.
	 * @throws ClassCastException if there was a value but didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#doubleValue()
	 * @deprecated 2.3.0 in favor of {@link #getDoubleOrDefault(JsonKey)} */
	@Deprecated
	public Double getDoubleOrDefault(final String key, final double defaultValue){
		Object returnable;
		if(this.containsKey(key)){
			returnable = this.get(key);
		}else{
			return defaultValue;
		}
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).doubleValue();
	}

	/** A convenience method that assumes there is a String value at the given key representing a fully qualified name
	 * in dot notation of an enum.
	 * @param key representing where the value ought to be paired with.
	 * @param <T> the Enum type the value at the key is expected to belong to.
	 * @return an Enum representing the value paired with the key.
	 * @throws ClassNotFoundException if the value was a String but the declaring enum type couldn't be determined with
	 *         it.
	 * @throws ClassCastException if the element at the index was not a String or if the fully qualified enum name is of
	 *         the wrong type.
	 * @throws IllegalArgumentException if an enum type was determined but it doesn't define an enum with the determined
	 *         name.
	 * @see Enum#valueOf(Class, String)
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey
	 * @deprecated 2.3.0 Jsoner deprecated automatically serializing enums as Strings. */
	@Deprecated
	@SuppressWarnings("unchecked")
	public <T extends Enum<T>> T getEnum(final JsonKey key) throws ClassNotFoundException{
		/* Supressing the unchecked warning because the returnType is dynamically identified and could lead to a
		 * ClassCastException when returnType is cast to Class<T>, which is acceptable by the method's contract. */
		T returnable;
		final String value;
		final String[] splitValues;
		final int numberOfSplitValues;
		final StringBuilder returnTypeName;
		final StringBuilder enumName;
		final Class<T> returnType;
		/* Make sure the value at the key is a String. */
		value = this.getString(key);
		if(value == null){
			return null;
		}
		/* Get the package, class, and enum names. */
		splitValues = value.split("\\.");
		numberOfSplitValues = splitValues.length;
		returnTypeName = new StringBuilder();
		enumName = new StringBuilder();
		for(int i = 0; i < numberOfSplitValues; i++){
			if(i == (numberOfSplitValues - 1)){
				/* If it is the last split value then it should be the name of the Enum since dots are not allowed
				 * in enum names. */
				enumName.append(splitValues[i]);
			}else if(i == (numberOfSplitValues - 2)){
				/* If it is the penultimate split value then it should be the end of the package/enum type and not
				 * need a dot appended to it. */
				returnTypeName.append(splitValues[i]);
			}else{
				/* Must be part of the package/enum type and will need a dot appended to it since they got removed
				 * in the split. */
				returnTypeName.append(splitValues[i]);
				returnTypeName.append(".");
			}
		}
		/* Use the package/class and enum names to get the Enum<T>. */
		returnType = (Class<T>)Class.forName(returnTypeName.toString());
		returnable = Enum.valueOf(returnType, enumName.toString());
		return returnable;
	}

	/** A convenience method that assumes there is a String value at the given key representing a fully qualified name
	 * in dot notation of an enum.
	 * @param key representing where the value ought to be stored at.
	 * @param <T> the Enum type the value at the key is expected to belong to.
	 * @return the enum based on the string found at the key, or null if the value paired with the provided key is null.
	 * @throws ClassNotFoundException if the value was a String but the declaring enum type couldn't be determined with
	 *         it.
	 * @throws ClassCastException if the element at the index was not a String or if the fully qualified enum name is of
	 *         the wrong type.
	 * @throws IllegalArgumentException if an enum type was determined but it doesn't define an enum with the determined
	 *         name.
	 * @see Enum#valueOf(Class, String)
	 * @deprecated 2.3.0 in favor of {@link #getEnum(JsonKey)} */
	@Deprecated
	@SuppressWarnings("unchecked")
	public <T extends Enum<T>> T getEnum(final String key) throws ClassNotFoundException{
		/* Supressing the unchecked warning because the returnType is dynamically identified and could lead to a
		 * ClassCastException when returnType is cast to Class<T>, which is expected by the method's contract. */
		T returnable;
		final String value;
		final String[] splitValues;
		final int numberOfSplitValues;
		final StringBuilder returnTypeName;
		final StringBuilder enumName;
		final Class<T> returnType;
		/* Make sure the value at the key is a String. */
		value = this.getStringOrDefault(key, "");
		if(value == null){
			return null;
		}
		/* Get the package, class, and enum names. */
		splitValues = value.split("\\.");
		numberOfSplitValues = splitValues.length;
		returnTypeName = new StringBuilder();
		enumName = new StringBuilder();
		for(int i = 0; i < numberOfSplitValues; i++){
			if(i == (numberOfSplitValues - 1)){
				/* If it is the last split value then it should be the name of the Enum since dots are not allowed
				 * in enum names. */
				enumName.append(splitValues[i]);
			}else if(i == (numberOfSplitValues - 2)){
				/* If it is the penultimate split value then it should be the end of the package/enum type and not
				 * need a dot appended to it. */
				returnTypeName.append(splitValues[i]);
			}else{
				/* Must be part of the package/enum type and will need a dot appended to it since they got removed
				 * in the split. */
				returnTypeName.append(splitValues[i]);
				returnTypeName.append(".");
			}
		}
		/* Use the package/class and enum names to get the Enum<T>. */
		returnType = (Class<T>)Class.forName(returnTypeName.toString());
		returnable = Enum.valueOf(returnType, enumName.toString());
		return returnable;
	}

	/** A convenience method that assumes there is a String value at the given key representing a fully qualified name
	 * in dot notation of an enum.
	 * @param key representing where the value ought to be paired with.
	 * @param <T> the Enum type the value at the key is expected to belong to.
	 * @return an Enum representing the value paired with the key or JsonKey#getValue() if the key isn't present.
	 * @throws ClassNotFoundException if the value was a String but the declaring enum type couldn't be determined with
	 *         it.
	 * @throws ClassCastException if the element at the index was not a String or if the fully qualified enum name is of
	 *         the wrong type.
	 * @throws IllegalArgumentException if an enum type was determined but it doesn't define an enum with the determined
	 *         name.
	 * @see Enum#valueOf(Class, String)
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey
	 * @deprecated 2.3.0 Jsoner deprecated automatically serializing enums as Strings. */
	@Deprecated
	@SuppressWarnings("unchecked")
	public <T extends Enum<T>> T getEnumOrDefault(final JsonKey key) throws ClassNotFoundException{
		/* Supressing the unchecked warning because the returnType is dynamically identified and could lead to a
		 * ClassCastException when returnType is cast to Class<T>, which is acceptable by the method's contract. */
		T returnable;
		final String value;
		final String[] splitValues;
		final int numberOfSplitValues;
		final StringBuilder returnTypeName;
		final StringBuilder enumName;
		final Class<T> returnType;
		/* Check to make sure the key is there. */
		if(this.containsKey(key)){
			/* Make sure the value at the key is a String. */
			value = this.getStringOrDefault(key.getKey(), "");
			if(value == null){
				return null;
			}
			/* Get the package, class, and enum names. */
			splitValues = value.split("\\.");
			numberOfSplitValues = splitValues.length;
			returnTypeName = new StringBuilder();
			enumName = new StringBuilder();
			for(int i = 0; i < numberOfSplitValues; i++){
				if(i == (numberOfSplitValues - 1)){
					/* If it is the last split value then it should be the name of the Enum since dots are not allowed
					 * in enum names. */
					enumName.append(splitValues[i]);
				}else if(i == (numberOfSplitValues - 2)){
					/* If it is the penultimate split value then it should be the end of the package/enum type and not
					 * need a dot appended to it. */
					returnTypeName.append(splitValues[i]);
				}else{
					/* Must be part of the package/enum type and will need a dot appended to it since they got removed
					 * in the split. */
					returnTypeName.append(splitValues[i]);
					returnTypeName.append(".");
				}
			}
			/* Use the package/class and enum names to get the Enum<T>. */
			returnType = (Class<T>)Class.forName(returnTypeName.toString());
			returnable = Enum.valueOf(returnType, enumName.toString());
		}else{
			/* It wasn't there and according to the method's contract we return the default value. */
			returnable = (T)key.getValue();
		}
		return returnable;
	}

	/** A convenience method that assumes there is a String value at the given key representing a fully qualified name
	 * in dot notation of an enum.
	 * @param key representing where the value ought to be stored at.
	 * @param defaultValue representing what is returned when the key isn't in the JsonObject.
	 * @param <T> the Enum type the value at the key is expected to belong to.
	 * @return the enum based on the string found at the key, or the defaultValue provided if the key doesn't exist, or
	 *         null if the value paired with provided key is null.
	 * @throws ClassNotFoundException if the value was a String but the declaring enum type couldn't be determined with
	 *         it.
	 * @throws ClassCastException if the element at the index was not a String or if the fully qualified enum name is of
	 *         the wrong type.
	 * @throws IllegalArgumentException if an enum type was determined but it doesn't define an enum with the determined
	 *         name.
	 * @see Enum#valueOf(Class, String)
	 * @deprecated 2.3.0 in favor of {@link #getEnumOrDefault(JsonKey)} */
	@Deprecated
	@SuppressWarnings("unchecked")
	public <T extends Enum<T>> T getEnumOrDefault(final String key, final T defaultValue) throws ClassNotFoundException{
		/* Supressing the unchecked warning because the returnType is dynamically identified and could lead to a
		 * ClassCastException when returnType is cast to Class<T>, which is expected by the method's contract. */
		T returnable;
		final String value;
		final String[] splitValues;
		final int numberOfSplitValues;
		final StringBuilder returnTypeName;
		final StringBuilder enumName;
		final Class<T> returnType;
		/* Check to make sure the key wasn't actually there and wasn't coincidentally the defaulted String as its
		 * value. */
		if(this.containsKey(key)){
			/* Make sure the value at the key is a String. */
			value = this.getStringOrDefault(key, "");
			if(value == null){
				return null;
			}
			/* Get the package, class, and enum names. */
			splitValues = value.split("\\.");
			numberOfSplitValues = splitValues.length;
			returnTypeName = new StringBuilder();
			enumName = new StringBuilder();
			for(int i = 0; i < numberOfSplitValues; i++){
				if(i == (numberOfSplitValues - 1)){
					/* If it is the last split value then it should be the name of the Enum since dots are not allowed
					 * in enum names. */
					enumName.append(splitValues[i]);
				}else if(i == (numberOfSplitValues - 2)){
					/* If it is the penultimate split value then it should be the end of the package/enum type and not
					 * need a dot appended to it. */
					returnTypeName.append(splitValues[i]);
				}else{
					/* Must be part of the package/enum type and will need a dot appended to it since they got removed
					 * in the split. */
					returnTypeName.append(splitValues[i]);
					returnTypeName.append(".");
				}
			}
			/* Use the package/class and enum names to get the Enum<T>. */
			returnType = (Class<T>)Class.forName(returnTypeName.toString());
			returnable = Enum.valueOf(returnType, enumName.toString());
		}else{
			/* It wasn't there and according to the method's contract we return the default value. */
			return defaultValue;
		}
		return returnable;
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return a Float representing the value paired with the key (which may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#floatValue()
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public Float getFloat(final JsonKey key){
		Object returnable = this.get(key.getKey());
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).floatValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @return the value stored at the key (which may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#floatValue()
	 * @deprecated 2.3.0 in favor of {@link #getFloat(JsonKey)} */
	@Deprecated
	public Float getFloat(final String key){
		Object returnable = this.get(key);
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).floatValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return a Float representing the value paired with the key or JsonKey#getValue() if the key isn't present (which
	 *         may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#floatValue()
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public Float getFloatOrDefault(final JsonKey key){
		Object returnable;
		if(this.containsKey(key.getKey())){
			returnable = this.get(key.getKey());
		}else{
			returnable = key.getValue();
		}
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).floatValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @param defaultValue representing what is returned when the key isn't in the JsonObject.
	 * @return the value stored at the key (which may involve rounding or truncation) or the default provided if the key
	 *         doesn't exist.
	 * @throws ClassCastException if there was a value but didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#floatValue()
	 * @deprecated 2.3.0 in favor of {@link #getFloatOrDefault(JsonKey)} */
	@Deprecated
	public Float getFloatOrDefault(final String key, final float defaultValue){
		Object returnable;
		if(this.containsKey(key)){
			returnable = this.get(key);
		}else{
			return defaultValue;
		}
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).floatValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return an Integer representing the value paired with the key (which may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#intValue()
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public Integer getInteger(final JsonKey key){
		Object returnable = this.get(key.getKey());
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).intValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @return the value stored at the key (which may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#intValue()
	 * @deprecated 2.3.0 in favor of {@link #getInteger(JsonKey)} */
	@Deprecated
	public Integer getInteger(final String key){
		Object returnable = this.get(key);
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).intValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return an Integer representing the value paired with the key or JsonKey#getValue() if the key isn't present
	 *         (which may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#intValue()
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public Integer getIntegerOrDefault(final JsonKey key){
		Object returnable;
		if(this.containsKey(key.getKey())){
			returnable = this.get(key.getKey());
		}else{
			returnable = key.getValue();
		}
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).intValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @param defaultValue representing what is returned when the key isn't in the JsonObject.
	 * @return the value stored at the key (which may involve rounding or truncation) or the default provided if the key
	 *         doesn't exist.
	 * @throws ClassCastException if there was a value but didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#intValue()
	 * @deprecated 2.3.0 in favor of {@link #getIntegerOrDefault(JsonKey)} */
	@Deprecated
	public Integer getIntegerOrDefault(final String key, final int defaultValue){
		Object returnable;
		if(this.containsKey(key)){
			returnable = this.get(key);
		}else{
			return defaultValue;
		}
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).intValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return a Long representing the value paired with the key (which may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#longValue()
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public Long getLong(final JsonKey key){
		Object returnable = this.get(key.getKey());
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).longValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @return the value stored at the key (which may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#longValue()
	 * @deprecated 2.3.0 in favor of {@link #getLong(JsonKey)} */
	@Deprecated
	public Long getLong(final String key){
		Object returnable = this.get(key);
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).longValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return a Long representing the value paired with the key or JsonKey#getValue() if the key isn't present (which
	 *         may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#longValue()
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public Long getLongOrDefault(final JsonKey key){
		Object returnable;
		if(this.containsKey(key.getKey())){
			returnable = this.get(key.getKey());
		}else{
			returnable = key.getValue();
		}
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).longValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @param defaultValue representing what is returned when the key isn't in the JsonObject.
	 * @return the value stored at the key (which may involve rounding or truncation) or the default provided if the key
	 *         doesn't exist.
	 * @throws ClassCastException if there was a value but didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#longValue()
	 * @deprecated 2.3.0 in favor of {@link #getLongOrDefault(JsonKey)} */
	@Deprecated
	public Long getLongOrDefault(final String key, final long defaultValue){
		Object returnable;
		if(this.containsKey(key)){
			returnable = this.get(key);
		}else{
			return defaultValue;
		}
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).longValue();
	}

	/** A convenience method that assumes there is a Map at the given key.
	 * @param <T> the kind of map to expect at the key. Note unless manually added, Map values will be a JsonObject.
	 * @param key representing where the value ought to be paired with.
	 * @return a Map representing the value paired with the key.
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	@SuppressWarnings("unchecked")
	public <T extends Map<?, ?>> T getMap(final JsonKey key){
		/* The unchecked warning is suppressed because there is no way of guaranteeing at compile time the cast will
		 * work. */
		return (T)this.get(key.getKey());
	}

	/** A convenience method that assumes there is a Map at the given key.
	 * @param <T> the kind of map to expect at the key. Note unless manually added, Map values will be a JsonObject.
	 * @param key representing where the value ought to be stored at.
	 * @return the value stored at the key.
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @deprecated 2.3.0 in favor of {@link #getMap(JsonKey)} */
	@Deprecated
	@SuppressWarnings("unchecked")
	public <T extends Map<?, ?>> T getMap(final String key){
		/* The unchecked warning is suppressed because there is no way of guaranteeing at compile time the cast will
		 * work. */
		return (T)this.get(key);
	}

	/** A convenience method that assumes there is a Map at the given key.
	 * @param <T> the kind of map to expect at the key. Note unless manually added, Map values will be a JsonObject.
	 * @param key representing where the value ought to be paired with.
	 * @return a Map representing the value paired with the key or JsonKey#getValue() if the key isn't present.
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	@SuppressWarnings("unchecked")
	public <T extends Map<?, ?>> T getMapOrDefault(final JsonKey key){
		/* The unchecked warning is suppressed because there is no way of guaranteeing at compile time the cast will
		 * work. */
		Object returnable;
		if(this.containsKey(key.getKey())){
			returnable = this.get(key.getKey());
		}else{
			returnable = key.getValue();
		}
		return (T)returnable;
	}

	/** A convenience method that assumes there is a Map at the given key.
	 * @param <T> the kind of map to expect at the key. Note unless manually added, Map values will be a JsonObject.
	 * @param key representing where the value ought to be stored at.
	 * @param defaultValue representing what is returned when the key isn't in the JsonObject.
	 * @return the value stored at the key or the default provided if the key doesn't exist.
	 * @throws ClassCastException if there was a value but didn't match the assumed return type.
	 * @deprecated 2.3.0 in favor of {@link #getMapOrDefault(JsonKey)} */
	@Deprecated
	@SuppressWarnings("unchecked")
	public <T extends Map<?, ?>> T getMapOrDefault(final String key, final T defaultValue){
		/* The unchecked warning is suppressed because there is no way of guaranteeing at compile time the cast will
		 * work. */
		Object returnable;
		if(this.containsKey(key)){
			returnable = this.get(key);
		}else{
			returnable = defaultValue;
		}
		return (T)returnable;
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return a Short representing the value paired with the key (which may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#shortValue()
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public Short getShort(final JsonKey key){
		Object returnable = this.get(key.getKey());
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).shortValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @return the value stored at the key (which may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#shortValue()
	 * @deprecated 2.3.0 in favor of {@link #getShort(JsonKey)} */
	@Deprecated
	public Short getShort(final String key){
		Object returnable = this.get(key);
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).shortValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return a Short representing the value paired with the key or JsonKey#getValue() if the key isn't present (which
	 *         may involve rounding or truncation).
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#shortValue()
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public Short getShortOrDefault(final JsonKey key){
		Object returnable;
		if(this.containsKey(key.getKey())){
			returnable = this.get(key.getKey());
		}else{
			returnable = key.getValue();
		}
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).shortValue();
	}

	/** A convenience method that assumes there is a Number or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @param defaultValue representing what is returned when the key isn't in the JsonObject.
	 * @return the value stored at the key (which may involve rounding or truncation) or the default provided if the key
	 *         doesn't exist.
	 * @throws ClassCastException if there was a value but didn't match the assumed return type.
	 * @throws NumberFormatException if a String isn't a valid representation of a BigDecimal or if the Number
	 *         represents the double or float Infinity or NaN.
	 * @see Number#shortValue()
	 * @deprecated 2.3.0 in favor of {@link #getShortOrDefault(JsonKey)} */
	@Deprecated
	public Short getShortOrDefault(final String key, final short defaultValue){
		Object returnable;
		if(this.containsKey(key)){
			returnable = this.get(key);
		}else{
			return defaultValue;
		}
		if(returnable == null){
			return null;
		}
		if(returnable instanceof String){
			/* A String can be used to construct a BigDecimal. */
			returnable = new BigDecimal((String)returnable);
		}
		return ((Number)returnable).shortValue();
	}

	/** A convenience method that assumes there is a Boolean, Number, or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return a String representing the value paired with the key.
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public String getString(final JsonKey key){
		Object returnable = this.get(key.getKey());
		if(returnable instanceof Boolean){
			returnable = returnable.toString();
		}else if(returnable instanceof Number){
			returnable = returnable.toString();
		}
		return (String)returnable;
	}

	/** A convenience method that assumes there is a Boolean, Number, or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @return the value stored at the key.
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @deprecated 2.3.0 in favor of {@link #getString(JsonKey)} */
	@Deprecated
	public String getString(final String key){
		Object returnable = this.get(key);
		if(returnable instanceof Boolean){
			returnable = returnable.toString();
		}else if(returnable instanceof Number){
			returnable = returnable.toString();
		}
		return (String)returnable;
	}

	/** A convenience method that assumes there is a Boolean, Number, or String value at the given key.
	 * @param key representing where the value ought to be paired with.
	 * @return a String representing the value paired with the key or JsonKey#getValue() if the key isn't present.
	 * @throws ClassCastException if the value didn't match the assumed return type.
	 * @see JsonKey
	 * @since 2.3.0 to utilize JsonKey */
	public String getStringOrDefault(final JsonKey key){
		Object returnable;
		if(this.containsKey(key.getKey())){
			returnable = this.get(key.getKey());
		}else{
			returnable = key.getValue();
		}
		if(returnable instanceof Boolean){
			returnable = returnable.toString();
		}else if(returnable instanceof Number){
			returnable = returnable.toString();
		}
		return (String)returnable;
	}

	/** A convenience method that assumes there is a Boolean, Number, or String value at the given key.
	 * @param key representing where the value ought to be stored at.
	 * @param defaultValue representing what is returned when the key isn't in the JsonObject.
	 * @return the value stored at the key or the default provided if the key doesn't exist.
	 * @throws ClassCastException if there was a value but didn't match the assumed return type.
	 * @deprecated 2.3.0 in favor of {@link #getStringOrDefault(JsonKey)} */
	@Deprecated
	public String getStringOrDefault(final String key, final String defaultValue){
		Object returnable;
		if(this.containsKey(key)){
			returnable = this.get(key);
		}else{
			return defaultValue;
		}
		if(returnable instanceof Boolean){
			returnable = returnable.toString();
		}else if(returnable instanceof Number){
			returnable = returnable.toString();
		}
		return (String)returnable;
	}

	/* (non-Javadoc)
	 * @see org.json.simple.Jsonable#asJsonString() */
	@Override
	public String toJson(){
		final StringWriter writable = new StringWriter();
		try{
			this.toJson(writable);
		}catch(final IOException caught){
			/* See java.io.StringWriter. */
		}
		return writable.toString();
	}

	/* (non-Javadoc)
	 * @see org.json.simple.Jsonable#toJsonString(java.io.Writer) */
	@Override
	public void toJson(final Writer writable) throws IOException{
		/* Writes the map in JSON object format. */
		boolean isFirstEntry = true;
		final Iterator<Map.Entry<String, Object>> entries = this.entrySet().iterator();
		writable.write('{');
		while(entries.hasNext()){
			if(isFirstEntry){
				isFirstEntry = false;
			}else{
				writable.write(',');
			}
			final Map.Entry<String, Object> entry = entries.next();
			writable.write(Jsoner.serialize(entry.getKey()));
			writable.write(':');
			writable.write(Jsoner.serialize(entry.getValue()));
		}
		writable.write('}');
	}
}
