package org.rrd4j.core;

import java.io.File;
import java.io.IOException;
import java.net.URI;

/**
 * An abstract backend factory which is used to store RRD data to ordinary files on the disk.
 * <p>
 * Every backend factory storing RRD data as ordinary files should inherit from it, some check are done
 * in the code for instanceof.
 *
 */
public abstract class RrdFileBackendFactory extends RrdBackendFactory {

    /**
     * {@inheritDoc}
     *
     * Method to determine if a file with the given path already exists.
     */
    @Override
    protected boolean exists(String path) {
        return Util.fileExists(path);
    }

    /** {@inheritDoc} */
    @Override
    public boolean canStore(URI uri) {
        if ((uri.isOpaque() || uri.isAbsolute()) && ! "file".equals(uri.getScheme())) {
            return false;
        } else if (uri.getAuthority() != null || uri.getFragment() != null || uri.getQuery() != null) {
            return false;
        } else {
            return true;
        }
    }

    @Override
    public URI getCanonicalUri(URI uri) {
        try {
            if (uri.isOpaque()) {
                return new File(uri.getSchemeSpecificPart()).getCanonicalFile().toURI();
            } else if (uri.isAbsolute()) {
                return new File(uri).getCanonicalFile().toURI();
            } else {
                return new File(uri.getPath()).getCanonicalFile().toURI();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("can't get canonical URI from " + uri + ": " + e);
        }
    }

    @Override
    public URI getUri(String path) {
        try {
            return new File(path).getCanonicalFile().toURI();
        } catch (IOException e) {
            throw new IllegalArgumentException("can't get canonical URI from path " + path + ": " + e);
        }
    }

    @Override
    public String getPath(URI uri) {
        if (uri.isOpaque()) {
            return uri.getSchemeSpecificPart();
        } else if (uri.isAbsolute()) {
            return new File(uri).getPath();
        } else {
            return uri.getPath();
        }
    }

}
