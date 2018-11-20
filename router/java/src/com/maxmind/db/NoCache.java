package com.maxmind.db;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A no-op cache singleton.
 */
public class NoCache implements NodeCache {

    private static final NoCache INSTANCE = new NoCache();

    private NoCache() {
    }

    @Override
    public JsonNode get(int key, Loader loader) throws IOException {
        return loader.load(key);
    }

    public static NoCache getInstance() {
        return INSTANCE;
    }

}
