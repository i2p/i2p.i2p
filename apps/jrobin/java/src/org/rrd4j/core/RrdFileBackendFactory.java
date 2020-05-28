package org.rrd4j.core;

import java.io.IOError;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        return Files.exists(Paths.get(path));
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
        // Resolve only parent, to avoid failing if the file is missing
        Path file;
        try {
            if (uri.isOpaque() || uri.getScheme() == null) {
                file = Paths.get(uri.getSchemeSpecificPart());
            } else {
                file = Paths.get(uri);
            }
            Path parent = file.getParent().toRealPath();
            return parent.resolve(file.getFileName()).toUri();
        } catch (IOError | IOException e) {
            throw new IllegalArgumentException("can't get canonical URI from " + uri + ": " + e, e);
        }
    }

    @Override
    public URI getUri(String path) {
        try {
            return Paths.get(path).normalize().toUri();
        } catch (IOError e) {
            throw new IllegalArgumentException("can't get URI from path " + path + ": " + e, e);
        }
    }

    @Override
    public String getPath(URI uri) {
        if (uri.isOpaque()) {
            return uri.getSchemeSpecificPart();
        } else if (uri.isAbsolute()) {
            return Paths.get(uri).normalize().toString();
        } else {
            return uri.getPath();
        }
    }

}
