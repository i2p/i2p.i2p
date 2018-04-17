package net.minidev.json.reader;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minidev.json.JSONAware;
import net.minidev.json.JSONAwareEx;
import net.minidev.json.JSONStreamAware;
import net.minidev.json.JSONStreamAwareEx;
import net.minidev.json.JSONStyle;
import net.minidev.json.JSONValue;

public class JsonWriter {
	private ConcurrentHashMap<Class<?>, JsonWriterI<?>> data;
	private LinkedList<WriterByInterface> writerInterfaces;

	public JsonWriter() {
		data = new ConcurrentHashMap<Class<?>, JsonWriterI<?>>();
		writerInterfaces = new LinkedList<WriterByInterface>();
		init();
	}

	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	static class WriterByInterface {
		public Class<?> _interface;
		public JsonWriterI<?> _writer;

		public WriterByInterface(Class<?> _interface, JsonWriterI<?> _writer) {
			this._interface = _interface;
			this._writer = _writer;
		}
	}

	/**
	 * try to find a Writer by Cheking implemented interface
	 * @param clazz class to serialize
	 * @return a Writer or null
	 */
	@SuppressWarnings("rawtypes")
	public JsonWriterI getWriterByInterface(Class<?> clazz) {
		for (WriterByInterface w : writerInterfaces) {
			if (w._interface.isAssignableFrom(clazz))
				return w._writer;
		}
		return null;
	}

	@SuppressWarnings("rawtypes")
	public JsonWriterI getWrite(Class cls) {
		return data.get(cls);
	}

	final static public JsonWriterI<JSONStreamAwareEx> JSONStreamAwareWriter = new JsonWriterI<JSONStreamAwareEx>() {
		public <E extends JSONStreamAwareEx> void writeJSONString(E value, Appendable out, JSONStyle compression) throws IOException {
			value.writeJSONString(out);
		}
	};

	final static public JsonWriterI<JSONStreamAwareEx> JSONStreamAwareExWriter = new JsonWriterI<JSONStreamAwareEx>() {
		public <E extends JSONStreamAwareEx> void writeJSONString(E value, Appendable out, JSONStyle compression) throws IOException {
			value.writeJSONString(out, compression);
		}
	};

	final static public JsonWriterI<JSONAwareEx> JSONJSONAwareExWriter = new JsonWriterI<JSONAwareEx>() {
		public <E extends JSONAwareEx> void writeJSONString(E value, Appendable out, JSONStyle compression) throws IOException {
			out.append(value.toJSONString(compression));
		}
	};

	final static public JsonWriterI<JSONAware> JSONJSONAwareWriter = new JsonWriterI<JSONAware>() {
		public <E extends JSONAware> void writeJSONString(E value, Appendable out, JSONStyle compression) throws IOException {
			out.append(value.toJSONString());
		}
	};

	final static public JsonWriterI<Iterable<? extends Object>> JSONIterableWriter = new JsonWriterI<Iterable<? extends Object>>() {
		public <E extends Iterable<? extends Object>> void writeJSONString(E list, Appendable out, JSONStyle compression) throws IOException {
			boolean first = true;
			compression.arrayStart(out);
			for (Object value : list) {
				if (first) {
					first = false;
					compression.arrayfirstObject(out);
				} else {
					compression.arrayNextElm(out);
				}
				if (value == null)
					out.append("null");
				else
					JSONValue.writeJSONString(value, out, compression);
				compression.arrayObjectEnd(out);
			}
			compression.arrayStop(out);
		}
	};

	final static public JsonWriterI<Enum<?>> EnumWriter = new JsonWriterI<Enum<?>>() {
		public <E extends Enum<?>> void writeJSONString(E value, Appendable out, JSONStyle compression)	throws IOException {
			@SuppressWarnings("rawtypes")
			String s = ((Enum) value).name();
			compression.writeString(out, s);
		}
	};

	final static public JsonWriterI<Map<String, ? extends Object>> JSONMapWriter = new JsonWriterI<Map<String, ? extends Object>>() {
		public <E extends Map<String, ? extends Object>> void writeJSONString(E map, Appendable out, JSONStyle compression) throws IOException {
			boolean first = true;
			compression.objectStart(out);
			/**
			 * do not use <String, Object> to handle non String key maps
			 */
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				Object v = entry.getValue();
				if (v == null && compression.ignoreNull())
					continue;
				if (first) {
					compression.objectFirstStart(out);
					first = false;
				} else {
					compression.objectNext(out);
				}
				JsonWriter.writeJSONKV(entry.getKey().toString(), v, out, compression);
				// compression.objectElmStop(out);
			}
			compression.objectStop(out);
		}
	};
	
	/**
	 * Json-Smart V2 Beans serialiser
	 * 
	 * Based on ASM
	 */
//	final static public JsonWriterI<Object> beansWriterASM = new BeansWriterASM();

	/**
	 * Json-Smart V1 Beans serialiser
	 */
	final static public JsonWriterI<Object> beansWriter = new BeansWriter();
	
	/**
	 * Json-Smart ArrayWriterClass
	 */
	final static public JsonWriterI<Object> arrayWriter = new ArrayWriter();

	/**
	 * ToString Writer
	 */
	final static public JsonWriterI<Object> toStringWriter = new JsonWriterI<Object>() {
		public void writeJSONString(Object value, Appendable out, JSONStyle compression) throws IOException {
			out.append(value.toString());
		}
	};
	
	public void init() {
		registerWriter(new JsonWriterI<String>() {
			public void writeJSONString(String value, Appendable out, JSONStyle compression) throws IOException {
				compression.writeString(out, (String) value);
			}
		}, String.class);

		registerWriter(new JsonWriterI<Double>() {
			public void writeJSONString(Double value, Appendable out, JSONStyle compression) throws IOException {
				if (value.isInfinite())
					out.append("null");
				else
					out.append(value.toString());
			}
		}, Double.class);

		registerWriter(new JsonWriterI<Date>() {
			public void writeJSONString(Date value, Appendable out, JSONStyle compression) throws IOException {
				out.append('"');
				JSONValue.escape(value.toString(), out, compression);
				out.append('"');
			}
		}, Date.class);

		registerWriter(new JsonWriterI<Float>() {
			public void writeJSONString(Float value, Appendable out, JSONStyle compression) throws IOException {
				if (value.isInfinite())
					out.append("null");
				else
					out.append(value.toString());
			}
		}, Float.class);

		registerWriter(toStringWriter, Integer.class, Long.class, Byte.class, Short.class, BigInteger.class, BigDecimal.class);
		registerWriter(toStringWriter, Boolean.class);

		/**
		 * Array
		 */

		registerWriter(new JsonWriterI<int[]>() {
			public void writeJSONString(int[] value, Appendable out, JSONStyle compression) throws IOException {
				boolean needSep = false;
				compression.arrayStart(out);
				for (int b : value) {
					if (needSep)
						compression.objectNext(out);
					else
						needSep = true;
					out.append(Integer.toString(b));
				}
				compression.arrayStop(out);
			}
		}, int[].class);

		registerWriter(new JsonWriterI<short[]>() {
			public void writeJSONString(short[] value, Appendable out, JSONStyle compression) throws IOException {
				boolean needSep = false;
				compression.arrayStart(out);
				for (short b : value) {
					if (needSep)
						compression.objectNext(out);
					else
						needSep = true;
					out.append(Short.toString(b));
				}
				compression.arrayStop(out);
			}
		}, short[].class);

		registerWriter(new JsonWriterI<long[]>() {
			public void writeJSONString(long[] value, Appendable out, JSONStyle compression) throws IOException {
				boolean needSep = false;
				compression.arrayStart(out);
				for (long b : value) {
					if (needSep)
						compression.objectNext(out);
					else
						needSep = true;
					out.append(Long.toString(b));
				}
				compression.arrayStop(out);
			}
		}, long[].class);

		registerWriter(new JsonWriterI<float[]>() {
			public void writeJSONString(float[] value, Appendable out, JSONStyle compression) throws IOException {
				boolean needSep = false;
				compression.arrayStart(out);
				for (float b : value) {
					if (needSep)
						compression.objectNext(out);
					else
						needSep = true;
					out.append(Float.toString(b));
				}
				compression.arrayStop(out);
			}
		}, float[].class);

		registerWriter(new JsonWriterI<double[]>() {
			public void writeJSONString(double[] value, Appendable out, JSONStyle compression) throws IOException {
				boolean needSep = false;
				compression.arrayStart(out);
				for (double b : value) {
					if (needSep)
						compression.objectNext(out);
					else
						needSep = true;
					out.append(Double.toString(b));
				}
				compression.arrayStop(out);
			}
		}, double[].class);

		registerWriter(new JsonWriterI<boolean[]>() {
			public void writeJSONString(boolean[] value, Appendable out, JSONStyle compression) throws IOException {
				boolean needSep = false;
				compression.arrayStart(out);
				for (boolean b : value) {
					if (needSep)
						compression.objectNext(out);
					else
						needSep = true;
					out.append(Boolean.toString(b));
				}
				compression.arrayStop(out);
			}
		}, boolean[].class);

		registerWriterInterface(JSONStreamAwareEx.class, JsonWriter.JSONStreamAwareExWriter);
		registerWriterInterface(JSONStreamAware.class, JsonWriter.JSONStreamAwareWriter);
		registerWriterInterface(JSONAwareEx.class, JsonWriter.JSONJSONAwareExWriter);
		registerWriterInterface(JSONAware.class, JsonWriter.JSONJSONAwareWriter);
		registerWriterInterface(Map.class, JsonWriter.JSONMapWriter);
		registerWriterInterface(Iterable.class, JsonWriter.JSONIterableWriter);
		registerWriterInterface(Enum.class, JsonWriter.EnumWriter);
		registerWriterInterface(Number.class, JsonWriter.toStringWriter);
	}

	/**
	 * associate an Writer to a interface With Hi priority
	 * @param interFace interface to map
	 * @param writer writer Object
	 * @deprecated use registerWriterInterfaceFirst
	 */
	public void addInterfaceWriterFirst(Class<?> interFace, JsonWriterI<?> writer) {
		registerWriterInterfaceFirst(interFace, writer);
	}

	/**
	 * associate an Writer to a interface With Low priority
	 * @param interFace interface to map
	 * @param writer writer Object
	 * @deprecated use registerWriterInterfaceLast
	 */
	public void addInterfaceWriterLast(Class<?> interFace, JsonWriterI<?> writer) {
		registerWriterInterfaceLast(interFace, writer);
	}

	/**
	 * associate an Writer to a interface With Low priority
	 * @param interFace interface to map
	 * @param writer writer Object
	 */
	public void registerWriterInterfaceLast(Class<?> interFace, JsonWriterI<?> writer) {
		writerInterfaces.addLast(new WriterByInterface(interFace, writer));
	}
	
	/**
	 * associate an Writer to a interface With Hi priority
	 * @param interFace interface to map
	 * @param writer writer Object
	 */
	public void registerWriterInterfaceFirst(Class<?> interFace, JsonWriterI<?> writer) {
		writerInterfaces.addFirst(new WriterByInterface(interFace, writer));
	}

	/**
	 * an alias for registerWriterInterfaceLast
	 * @param interFace interface to map
	 * @param writer writer Object
	 */
	public void registerWriterInterface(Class<?> interFace, JsonWriterI<?> writer) {
		registerWriterInterfaceLast(interFace, writer);
	}

	/**
	 * associate an Writer to a Class
	 * @param writer
	 * @param cls
	 */
	public <T> void registerWriter(JsonWriterI<T> writer, Class<?>... cls) {
		for (Class<?> c : cls)
			data.put(c, writer);
	}

	/**
	 * Write a Key : value entry to a stream
	 */
	public static void writeJSONKV(String key, Object value, Appendable out, JSONStyle compression) throws IOException {
		if (key == null)
			out.append("null");
		else if (!compression.mustProtectKey(key))
			out.append(key);
		else {
			out.append('"');
			JSONValue.escape(key, out, compression);
			out.append('"');
		}
		compression.objectEndOfKey(out);
		if (value instanceof String) {
			compression.writeString(out, (String) value);
		} else
			JSONValue.writeJSONString(value, out, compression);
		compression.objectElmStop(out);
	}
}
