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
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 *  A class to iterate through a hosts.txt or config file without
 *  reading the whole thing into memory.
 *  Keys are always converted to lower case.
 *
 *  Callers should iterate all the way through or call close()
 *  to ensure the underlying stream is closed.
 *
 *  @since 0.8.7
 */
class ConfigIterator implements Iterator<Map.Entry<String, String>> {

    private BufferedReader input;
    private ConfigEntry next;

    /**
     *  A dummy iterator in which hasNext() is always false.
     */
    public ConfigIterator() {}

    /**
     *  An iterator over the key/value pairs in the file.
     */
    public ConfigIterator(File file) {
        try {
            FileInputStream fileStream = new FileInputStream(file);
            input = new BufferedReader(new InputStreamReader(fileStream));
        } catch (IOException ioe) {}
    }

    public boolean hasNext() {
        if (input == null)
            return false;
        if (next != null)
            return true;
        try {
            String inputLine = input.readLine();
            while (inputLine != null) {
                inputLine = ConfigParser.stripComments(inputLine);
                String[] splitLine = inputLine.split("=");
                if (splitLine.length == 2) {
                    next = new ConfigEntry(splitLine[0].trim().toLowerCase(), splitLine[1].trim());
                    return true;
                }
                inputLine = input.readLine();
            }
        } catch (IOException ioe) {}
        try { input.close(); } catch (IOException ioe) {}
        input = null;
        next = null;
        return false;
    }

    public Map.Entry<String, String> next() {
        if (!hasNext())
            throw new NoSuchElementException();
        Map.Entry<String, String> rv = next;
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

    @Override
    protected void finalize() {
        close();
    }

    /**
     *  The object returned by the iterator.
     */
    private static class ConfigEntry implements Map.Entry<String, String> {
        private final String key;
        private final String value;

        public ConfigEntry(String k, String v) {
            key = k;
            value = v;
        }

        public String getKey() {
            return key;
        }

        public String getValue() {
            return value;
        }

        public String setValue(String v) {
            throw new UnsupportedOperationException();
        }

        public int hashCode() {
            return key.hashCode() ^ value.hashCode();
        }

        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry e = (Map.Entry) o;
            return key.equals(e.getKey()) && value.equals(e.getValue());
        }
    }
}
