package com.maxmind.db;

import java.io.IOException;

public interface NodeCache {

    public interface Loader {
        Object load(int key) throws IOException;
    }

    public Object get(int key, Loader loader) throws IOException;

}
