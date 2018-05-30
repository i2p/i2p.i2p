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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fake Container factory used for JSon check and SaX parsing
 * 
 * @author Uriel Chemouni uchemouni@gmail.com
 */
public class FakeContainerFactory implements ContainerFactory {
	public FackList list;
	public FackMap map;

	// @Override JDK 1.5 compatibility change
	public List<Object> createArrayContainer() {
		if (list == null)
			list = new FackList();
		return list;
	}

	// @Override JDK 1.5 compatibility change
	public Map<String, Object> createObjectContainer() {
		if (map == null)
			map = new FackMap();
		return map;
	}

	/**
	 * dummy AbstractMap
	 */
	static class FackMap extends AbstractMap<String, Object> {
		public Object put(String key, Object value) {
			return null;
		}

		@Override
		public Set<java.util.Map.Entry<String, Object>> entrySet() {
			return null;
		}
	}

	/**
	 * dummy AbstractList
	 * replace AbsractList by list to make it compile on jdk 1.7
	 */
	@SuppressWarnings("serial")
	static class FackList extends ArrayList<Object> {
		public boolean add(Object e) {
			return false;
		}

		@Override
		public Object get(int index) {
			return null;
		}

		@Override
		public int size() {
			return 0;
		}
	}
}
