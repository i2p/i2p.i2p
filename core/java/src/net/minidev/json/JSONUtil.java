package net.minidev.json;

/*
 *    Copyright 2011 JSON-SMART authors
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * @author Uriel Chemouni uchemouni@gmail.com
 */
public class JSONUtil {
	public static String getSetterName(String key) {
		int len = key.length();
		char[] b = new char[len + 3];
		b[0] = 's';
		b[1] = 'e';
		b[2] = 't';
		char c = key.charAt(0);
		if (c >= 'a' && c <= 'z')
			c += 'A' - 'a';
		b[3] = c;
		for (int i = 1; i < len; i++) {
			b[i + 3] = key.charAt(i);
		}
		return new String(b);
	}

	public static String getGetterName(String key) {
		int len = key.length();
		char[] b = new char[len + 3];
		b[0] = 'g';
		b[1] = 'e';
		b[2] = 't';
		char c = key.charAt(0);
		if (c >= 'a' && c <= 'z')
			c += 'A' - 'a';
		b[3] = c;
		for (int i = 1; i < len; i++) {
			b[i + 3] = key.charAt(i);
		}
		return new String(b);
	}

	public static String getIsName(String key) {
		int len = key.length();
		char[] b = new char[len + 2];
		b[0] = 'i';
		b[1] = 's';
		char c = key.charAt(0);
		if (c >= 'a' && c <= 'z')
			c += 'A' - 'a';
		b[2] = c;
		for (int i = 1; i < len; i++) {
			b[i + 2] = key.charAt(i);
		}
		return new String(b);
	}
}
