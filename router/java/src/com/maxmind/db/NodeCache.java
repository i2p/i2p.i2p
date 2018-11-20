package com.maxmind.db;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;

public interface NodeCache {

    public interface Loader {
        JsonNode load(int key) throws IOException;
    }

    public JsonNode get(int key, Loader loader) throws IOException;

}
