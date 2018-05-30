package net.minidev.json.parser;

/*
 *    Copyright 2011 JSON-SMART authors
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;

/**
 * Container factory for creating containers for JSON object and JSON array.
 * 
 * @author Uriel Chemouni uchemouni@gmail.com
 */
public interface ContainerFactory {
	/**
	 * @return A Map instance to build JSON object.
	 */
	public Map<String, Object> createObjectContainer();

	/**
	 * @return A List instance to store JSON array.
	 */
	public List<Object> createArrayContainer();

	/**
	 * Default factory
	 */
	public final static ContainerFactory FACTORY_SIMPLE = new ContainerFactory() {

		// @Override JDK 1.5 compatibility change
		public Map<String, Object> createObjectContainer() {
			return new JSONObject();
		}

		// @Override JDK 1.5 compatibility change
		public List<Object> createArrayContainer() {
			return new JSONArray();
		}
	};

	public final static ContainerFactory FACTORY_ORDERED = new ContainerFactory() {

		// @Override JDK 1.5 compatibility change
		public Map<String, Object> createObjectContainer() {
			return new LinkedHashMap<String, Object>();
		}

		// @Override JDK 1.5 compatibility change
		public List<Object> createArrayContainer() {
			return new JSONArray();
		}
	};

}
