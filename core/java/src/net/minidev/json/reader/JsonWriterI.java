package net.minidev.json.reader;

import java.io.IOException;

import net.minidev.json.JSONStyle;

public interface JsonWriterI<T> {
	public <E extends T> void writeJSONString(E value, Appendable out, JSONStyle compression) throws IOException;
}
