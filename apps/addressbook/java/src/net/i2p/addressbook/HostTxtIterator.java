/*
 * Copyright (c) 2004 Ragnarok
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.i2p.addressbook;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

import net.i2p.client.naming.HostTxtEntry;
import net.i2p.data.DataHelper;

/**
 *  A class to iterate through a hosts.txt or config file without
 *  reading the whole thing into memory.
 *  Keys are always converted to lower case.
 *
 *  Callers should iterate all the way through or call close()
 *  to ensure the underlying stream is closed.
 *
 *  This is not used for config files.
 *  It is only used for subscriptions.
 *
 *  @since 0.8.7, renamed from ConfigIterator in 0.9.26
 */
class HostTxtIterator implements Iterator<Map.Entry<String, HostTxtEntry>>, Closeable {

    private BufferedReader input;
    private MapEntry next;

    /**
     *  A dummy iterator in which hasNext() is always false.
     */
    public HostTxtIterator() {}

    /**
     *  An iterator over the key/value pairs in the file.
     */
    public HostTxtIterator(File file) throws IOException {
            FileInputStream fileStream = new FileInputStream(file);
            input = new BufferedReader(new InputStreamReader(fileStream, "UTF-8"));
    }

    public boolean hasNext() {
        if (input == null)
            return false;
        if (next != null)
            return true;
        try {
            String inputLine;
            while ((inputLine = input.readLine()) != null) {
                HostTxtEntry he = HostTxtParser.parse(inputLine, true);
                if (he == null)
                    continue;
                next = new MapEntry(he.getName(), he);
                return true;
            }
        } catch (IOException ioe) {}
        try { input.close(); } catch (IOException ioe) {}
        input = null;
        next = null;
        return false;
    }

    /**
     *  'remove' entries will be returned with a null key,
     *  and the value will contain a null name, null dest,
     *  and non-null props.
     */
    public Map.Entry<String, HostTxtEntry> next() {
        if (!hasNext())
            throw new NoSuchElementException();
        Map.Entry<String, HostTxtEntry> rv = next;
        next = null;
        return rv;
    }

    public void remove() {
        throw new UnsupportedOperationException();
    }

    public void close() {
        if (input != null) {
            try { input.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  The object returned by the iterator.
     */
    private static class MapEntry implements Map.Entry<String, HostTxtEntry> {
        private final String key;
        private final HostTxtEntry value;

        public MapEntry(String k, HostTxtEntry v) {
            key = k;
            value = v;
        }

        public String getKey() {
            return key;
        }

        public HostTxtEntry getValue() {
            return value;
        }

        public HostTxtEntry setValue(HostTxtEntry v) {
            throw new UnsupportedOperationException();
        }

        public int hashCode() {
            return key.hashCode() ^ value.hashCode();
        }

        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            @SuppressWarnings("unchecked")
            Map.Entry<Object, Object> e = (Map.Entry<Object, Object>) o;
            return key.equals(e.getKey()) && value.equals(e.getValue());
        }
    }
}
