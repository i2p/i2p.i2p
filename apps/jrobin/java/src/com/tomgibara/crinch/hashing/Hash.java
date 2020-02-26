/*
 * Copyright 2010 Tom Gibara
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
package com.tomgibara.crinch.hashing;

import java.math.BigInteger;

/**
 * <p>
 * Implementations of this interface can generate one hash value for a given
 * object. Depending upon the implementation, null values may be supported.
 * </p>
 * 
 * @author tomgibara
 * 
 * @param <T>
 *            the type of objects for which hashes may be generated
 */

public interface Hash<T> {

    HashRange getRange();

    /**
     * The hash value as a {@link BigInteger}. This method may be useful in
     * circumstances where the generated hash is too large to be accomodated in
     * a single primitive value, eg. if cryptographic hashes are being used.
     * 
     * @param value
     *            the object to be hashed
     * @return the object's hash code, never null
     * @throws IllegalArgumentException
     *             if the value cannot be hashed
     */

    BigInteger hashAsBigInt(T value) throws IllegalArgumentException;

    /**
     * The hash value as an int. This method should provide better performance
     * for integer-ranged hashes. This value is not guaranteed to lie within the
     * indicated {@link com.tomgibara.crinch.hashing.HashRange}.
     * 
     * @param value
     *            the object to be hashed
     * @return the object's hash code
     * @throws IllegalArgumentException
     *             if the value cannot be hashed
     */

    int hashAsInt(T value) throws IllegalArgumentException;

    /**
     * The hash value as a long. This method should provide better performance
     * for long-ranged hashes. This value is not guaranteed to lie within the
     * indicated {@link com.tomgibara.crinch.hashing.HashRange}.
     * 
     * @param value
     *            the object to be hashed
     * @return the object's hash code
     * @throws IllegalArgumentException
     *             if the value cannot be hashed
     */

    long hashAsLong(T value) throws IllegalArgumentException;

}
