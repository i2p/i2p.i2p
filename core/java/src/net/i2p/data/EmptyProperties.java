package net.i2p.data;

import java.io.InputStream;
import java.io.Reader;
import java.util.Map;

import net.i2p.util.OrderedProperties;

/**
 * Immutable OrderedProperties, to reduce object churn
 * in LS2 where it's expected to be empty.
 * Returned from DataHelper.readProperties(in, null).
 *
 * All methods that modify will throw UnsupportedOperationException,
 * except clear() and remove() which do nothing.
 *
 * @since 0.9.38
 */
public class EmptyProperties extends OrderedProperties {

    public static final EmptyProperties INSTANCE = new EmptyProperties();

    private EmptyProperties() {
        super();
    }

    @Override
    public String getProperty(String key) {
        return null;
    }

    @Override
    public void load(InputStream inStream) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void load(Reader reader) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void loadFromXML(InputStream in) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object put(Object key, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(Map<? extends Object, ? extends Object> t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object setProperty(String key, String value) {
        throw new UnsupportedOperationException();
    }
}
