package net.minidev.json.reader;

import java.io.IOException;

import net.minidev.json.JSONStyle;
import net.minidev.json.JSONValue;

public class ArrayWriter implements JsonWriterI<Object> {
	public <E> void writeJSONString(E value, Appendable out, JSONStyle compression) throws IOException {
		compression.arrayStart(out);
		boolean needSep = false;
		for (Object o : ((Object[]) value)) {
			if (needSep)
				compression.objectNext(out);
			else
				needSep = true;
			JSONValue.writeJSONString(o, out, compression);
		}
		compression.arrayStop(out);
	}
}
