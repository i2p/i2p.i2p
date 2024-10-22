package org.rrd4j.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Base (abstract) backend factory class which holds references to all concrete
 * backend factories and defines abstract methods which must be implemented in
 * all concrete factory implementations.
 * <p>
 *
 * Factory classes are used to create concrete {@link org.rrd4j.core.RrdBackend} implementations.
 * Each factory creates unlimited number of specific backend objects.
 * <p>
 * Rrd4j supports six different backend types (backend factories) out of the box:
 * <ul>
 * <li>{@link org.rrd4j.core.RrdRandomAccessFileBackend}: objects of this class are created from the
 * {@link org.rrd4j.core.RrdRandomAccessFileBackendFactory} class. This was the default backend used in all
 * Rrd4j releases before 1.4.0 release. It uses java.io.* package and RandomAccessFile class to store
 * RRD data in files on the disk.
 *
 * <li>{@link org.rrd4j.core.RrdSafeFileBackend}: objects of this class are created from the
 * {@link org.rrd4j.core.RrdSafeFileBackendFactory} class. It uses java.io.* package and RandomAccessFile class to store
 * RRD data in files on the disk. This backend is SAFE:
 * it locks the underlying RRD file during update/fetch operations, and caches only static
 * parts of a RRD file in memory. Therefore, this backend is safe to be used when RRD files should
 * be shared <b>between several JVMs</b> at the same time. However, this backend is *slow* since it does
 * not use fast java.nio.* package (it's still based on the RandomAccessFile class).
 *
 * <li>{@link org.rrd4j.core.RrdNioBackend}: objects of this class are created from the
 * {@link org.rrd4j.core.RrdNioBackendFactory} class. The backend uses java.io.* and java.nio.*
 * classes (mapped ByteBuffer) to store RRD data in files on the disk. This is the default backend
 * since 1.4.0 release.
 *
 * <li>{@link org.rrd4j.core.RrdMemoryBackend}: objects of this class are created from the
 * {@link org.rrd4j.core.RrdMemoryBackendFactory} class. This backend stores all data in memory. Once
 * JVM exits, all data gets lost. The backend is extremely fast and memory hungry.
 * 
 * </ul>
 * <p>
 * Each backend factory used to be identified by its {@link #getName() name}. Constructors
 * are provided in the {@link org.rrd4j.core.RrdDb} class to create RrdDb objects (RRD databases)
 * backed with a specific backend.
 * <p>
 * A more generic management was added in version 3.2 that allows multiple instances of a backend to be used. Each backend can
 * manage custom URL. They are tried in the declared order by the {@link #setActiveFactories(RrdBackendFactory...)} or
 * {@link #addFactories(RrdBackendFactory...)} and the method {@link #canStore(URI)} return true when it can manage the given
 * URI. Using {@link #setActiveFactories(RrdBackendFactory...)} with new created instance is the preferred way to manage factories, as
 * it provides a much precise control of creation and end of life of factories.
 * <p>
 * Since 3.4, using only {@link #setActiveFactories(RrdBackendFactory...)} and {@link #addActiveFactories(RrdBackendFactory...)} will not register any
 * named backend at all. {@link #getDefaultFactory()} will return the first active factory. All methods using named backend and the registry of factory were deprecated.
 * <p>
 * For default implementation, the path is separated in a root URI prefix and the path components. The root URI can be
 * used to identify different name spaces or just be `/`.
 * <p>
 * See javadoc for {@link org.rrd4j.core.RrdBackend} to find out how to create your custom backends.
 *
 */
public abstract class RrdBackendFactory implements Closeable {

    private static final class Registry {
        private static final Map<String, RrdBackendFactory> factories = new HashMap<>();
        static {
            RrdRandomAccessFileBackendFactory fileFactory = new RrdRandomAccessFileBackendFactory();
            factories.put(fileFactory.name, fileFactory);
            RrdMemoryBackendFactory memoryFactory = new RrdMemoryBackendFactory();
            factories.put(memoryFactory.name, memoryFactory);
            RrdNioBackendFactory nioFactory = new RrdNioBackendFactory();
            factories.put(nioFactory.name, nioFactory);
            RrdSafeFileBackendFactory safeFactory = new RrdSafeFileBackendFactory();
            factories.put(safeFactory.name, safeFactory);
            defaultFactory = factories.get(DEFAULTFACTORY);
        }
        private static final RrdBackendFactory defaultFactory;
    }

    /**
     * The default factory type. It will also put in the active factories list.
     * 
     */
    public static final String DEFAULTFACTORY = "NIO";

    private static final List<RrdBackendFactory> activeFactories = new ArrayList<>();

    /**
     * Returns backend factory for the given backend factory name.
     *
     * @param name Backend factory name. Initially supported names are:
     *             <ul>
     *             <li><b>FILE</b>: Default factory which creates backends based on the
     *             java.io.* package. RRD data is stored in files on the disk
     *             <li><b>SAFE</b>: Default factory which creates backends based on the
     *             java.io.* package. RRD data is stored in files on the disk. This backend
     *             is "safe". Being safe means that RRD files can be safely shared between
     *             several JVM's.
     *             <li><b>NIO</b>: Factory which creates backends based on the
     *             java.nio.* package. RRD data is stored in files on the disk
     *             <li><b>MEMORY</b>: Factory which creates memory-oriented backends.
     *             RRD data is stored in memory, it gets lost as soon as JVM exits.
     *             <li><b>BERKELEY</b>: a memory-oriented backend that ensure persistent
     *             in a <a href="http://www.oracle.com/technetwork/database/berkeleydb/overview/index-093405.html">Berkeley Db</a> storage.
     *             <li><b>MONGODB</b>: a memory-oriented backend that ensure persistent
     *             in a <a href="http://www.mongodb.org/">MongoDB</a> storage.
     *             </ul>
     *
     * @deprecated Uses active factory instead
     * @return Backend factory for the given factory name
     */
    @Deprecated
    public static synchronized RrdBackendFactory getFactory(String name) {
        RrdBackendFactory factory = Registry.factories.get(name);
        if (factory != null) {
            return factory;
        } else {
            throw new IllegalArgumentException(
                    "No backend factory found with the name specified ["
                            + name + "]");
        } 
    }

    /**
     * Registers new (custom) backend factory within the Rrd4j framework.
     *
     * @deprecated Uses active factory instead
     * @param factory Factory to be registered
     */
    @Deprecated
    public static synchronized void registerFactory(RrdBackendFactory factory) {
        String name = factory.getName();
        if (!Registry.factories.containsKey(name)) {
            Registry.factories.put(name, factory);
        }
        else {
            throw new IllegalArgumentException("Backend factory '" + name + "' cannot be registered twice");
        }
    }

    /**
     * Registers new (custom) backend factory within the Rrd4j framework and sets this
     * factory as the default.
     *
     * @deprecated Uses {@link #setActiveFactories(RrdBackendFactory...)} instead.
     * @param factory Factory to be registered and set as default
     */
    @Deprecated
    public static synchronized void registerAndSetAsDefaultFactory(RrdBackendFactory factory) {
        registerFactory(factory);
        setDefaultFactory(factory.getName());
    }

    /**
     * Returns the default backend factory. This factory is used to construct
     * {@link org.rrd4j.core.RrdDb} objects if no factory is specified in the RrdDb constructor.
     *
     * @return Default backend factory.
     */
    public static synchronized RrdBackendFactory getDefaultFactory() {
        if (!activeFactories.isEmpty()) {
            return activeFactories.get(0);
        } else {
            return Registry.defaultFactory;
        }
    }

    /**
     * Replaces the default backend factory with a new one. This method must be called before
     * the first RRD gets created.
     * <p>
     * It also clear the list of actives factories and set it to the default factory.
     * <p>
     *
     * @deprecated Uses active factory instead
     * @param factoryName Name of the default factory..
     */
    @Deprecated
    public static synchronized void setDefaultFactory(String factoryName) {
        // We will allow this only if no RRDs are created
        if (!RrdBackend.isInstanceCreated()) {
            activeFactories.clear();
            activeFactories.add(getFactory(factoryName));
        } else {
            throw new IllegalStateException(
                    "Could not change the default backend factory. "
                            + "This method must be called before the first RRD gets created");
        } 
    }

    /**
     * Set the list of active factories, i.e. the factory used to resolve URI.
     * 
     * @param newFactories the new active factories.
     */
    public static synchronized void setActiveFactories(RrdBackendFactory... newFactories) {
        activeFactories.clear();
        activeFactories.addAll(Arrays.asList(newFactories));
    }
    
    /**
     * Return the current active factories as a stream.
     * @return the Stream
     * @since 3.7
     */
    public static synchronized Stream<RrdBackendFactory> getActiveFactories() {
        return activeFactories.stream();
    }

    /**
     * Add factories to the list of active factories, i.e. the factory used to resolve URI.
     * 
     * @deprecated Uses {@link #addActiveFactories(RrdBackendFactory...)} instead.
     * @param newFactories active factories to add.
     */
    @Deprecated
    public static synchronized void addFactories(RrdBackendFactory... newFactories) {
        addActiveFactories(newFactories);
    }

    /**
     * Add factories to the list of active factories, i.e. the factory used to resolve URI.
     * 
     * @param newFactories active factories to add.
     */
    public static synchronized void addActiveFactories(RrdBackendFactory... newFactories) {
        activeFactories.addAll(Arrays.asList(newFactories));
    }

    /**
     * For a given URI, try to find a factory that can manage it in the list of active factories.
     * 
     * @param uri URI to try.
     * @return a {@link RrdBackendFactory} that can manage that URI.
     * @throws IllegalArgumentException when no matching factory is found.
     */
    public static synchronized RrdBackendFactory findFactory(URI uri) {
        // If no active factory defined, will try the default factory
        if (activeFactories.isEmpty() && Registry.defaultFactory.canStore(uri)) {
            return Registry.defaultFactory;
        } else {
            for (RrdBackendFactory tryfactory: activeFactories) {
                if (tryfactory.canStore(uri)) {
                    return tryfactory;
                }
            }
            throw new IllegalArgumentException(
                    "no matching backend factory for " + uri);
        }
    }

    private static final Pattern URIPATTERN = Pattern.compile("^(?:(?<scheme>[a-zA-Z][a-zA-Z0-9+-\\.]*):)?(?://(?<authority>[^/\\?#]*))?(?<path>[^\\?#]*)(?:\\?(?<query>[^#]*))?(?:#(?<fragment>.*))?$");

    /**
     * Try to detect an URI from a path. It's needed because of Microsoft Windows path that look's like an URI
     * and to URL-encode the path.
     * 
     * @param rrdpath a file URI that can be a Windows path
     * @return an URI
     */
    public static URI buildGenericUri(String rrdpath) {
        Matcher urimatcher = URIPATTERN.matcher(rrdpath);
        if (urimatcher.matches()) {
            String scheme = urimatcher.group("scheme");
            String authority = urimatcher.group("authority");
            String path = urimatcher.group("path");
            String query = urimatcher.group("query");
            String fragment = urimatcher.group("fragment");
            try {
                // If scheme is a single letter, it's not a scheme, but a windows path
                if (scheme != null && scheme.length() == 1) {
                    return new File(rrdpath).toURI();
                }
                // A scheme and a not absolute path, it's an opaque URI
                if (scheme != null && path.charAt(0) != '/') {
                    return new URI(scheme, path, query);
                }
                // A relative file was given, ensure that it's OK if it was on a non-unix plateform
                if (File.separatorChar != '/' && scheme == null) {
                    path = path.replace(File.separatorChar, '/');
                }
                return new URI(scheme, authority, path, query, fragment);
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException(ex.getMessage(), ex);
            }
        }
        throw new IllegalArgumentException("Not an URI pattern");
    }

    private static class ClosingReference extends PhantomReference<RrdDb> {
        private RrdBackend backend;
        public ClosingReference(RrdDb db, RrdBackend backend,
                ReferenceQueue<? super RrdDb> q) {
            super(db, q);
            this.backend = backend;
        }
        @Override
        public void clear() {
            // backend doesn't need to be closed here as it already happens in RrdBackend.rrdClose()
            backend = null;
            super.clear();
        }
    }

    private final ReferenceQueue<RrdDb> refQueue = new ReferenceQueue<>();

    protected final String name;
    protected final boolean cachingAllowed;
    protected final String scheme;
    protected final boolean validateHeader;

    protected RrdBackendFactory() {
        RrdBackendAnnotation annotation = getClass().getAnnotation(RrdBackendAnnotation.class);
        if (annotation != null) {
            name = annotation.name();
            cachingAllowed = annotation.cachingAllowed();
            if (annotation.scheme() != null && ! annotation.scheme().isEmpty()) {
                scheme = annotation.scheme();
            } else {
                scheme = name.toLowerCase(Locale.ENGLISH);
            }
            validateHeader = annotation.shouldValidateHeader();
        } else {
            name = getName();
            cachingAllowed = RrdBackendAnnotation.DEFAULT_CACHING_ALLOWED;
            scheme = getName().toLowerCase(Locale.ENGLISH);
            validateHeader = true;
        }
    }

    /**
     * Check that all phantom reference are indeed safely closed.
     */
    public void checkClosing() {
        while(true) {
            ClosingReference ref = (ClosingReference) refQueue.poll();
            if (ref == null) {
                break;
            } else if (ref.backend != null) {
                try {
                    ref.backend.close();
                } catch (IOException e) {
                }
            }
        }
    }

    /**
     * @return the scheme name for URI, default to getName().toLowerCase()
     */
    public String getScheme() {
        return scheme;
    }

    protected URI getRootUri() {
        try {
            return new URI(getScheme(), null, "/", null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid scheme " + getScheme());
        }
    }

    public boolean canStore(URI uri) {
        return false;
    }

    /**
     * Try to match an URI against a root URI using a few rules:
     * <ul>
     * <li>scheme must match if they are given.
     * <li>authority must match if they are given.
     * <li>if uri is opaque (scheme:nonabsolute), the scheme specific part is resolve as a relative path.
     * <li>query and fragment is kept as is.
     * </ul>
     * 
     * @param rootUri the URI to match against
     * @param uri an URI that the current backend can handle.
     * @param relative if true, return an URI relative to the {@code rootUri}
     * @return a calculate normalized absolute URI or null if the tried URL don't match against the root.
     */
    protected URI resolve(URI rootUri, URI uri, boolean relative) {
        String scheme = uri.getScheme();
        if (scheme != null && ! scheme.equals(rootUri.getScheme())) {
            throw new IllegalArgumentException(String.format("scheme %s not compatible with %s", scheme, rootUri.getScheme()));
        } else if (scheme == null) {
            scheme = rootUri.getScheme();
        }
        String authority = uri.getAuthority();
        if (authority != null && ! authority.equals(rootUri.getAuthority())) {
            throw new IllegalArgumentException("URI credential not compatible");
        } else if (authority == null) {
            authority = rootUri.getAuthority();
        }
        String path;
        if (uri.isOpaque()) {
            // try to resolve an opaque uri as scheme:relativepath
            path = uri.getSchemeSpecificPart();
        } else if (! uri.isAbsolute()) {
            // A relative URI, resolve it against the root
            path = rootUri.resolve(uri).normalize().getPath();
        } else {
            path = uri.normalize().getPath();
        }
        if (! path.startsWith(rootUri.getPath())) {
            throw new IllegalArgumentException(String.format("URI destination path %s not root with %s", path, rootUri.getPath()));
        }
        String query = uri.getQuery();
        String fragment = uri.getFragment();
        try {
            authority = authority != null ? authority : "";
            query = query != null ? "?" + URLEncoder.encode(query, "UTF-8") : "";
            fragment = fragment != null ? "#" + URLEncoder.encode(fragment, "UTF-8") : "";
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException("UTF-8 is missing");
        }
        String newUriString = String.format("%s://%s%s%s%s", scheme, authority, path , query, fragment);
        URI newURI = URI.create(newUriString);
        if (relative) {
            return rootUri.relativize(newURI);
        } else {
            return newURI;
        }
    }

    /**
     * Ensure that an URI is returned in a non-ambiguous way.
     * 
     * @param uri a valid URI for this backend.
     * @return the canonized URI.
     */
    public URI getCanonicalUri(URI uri) {
        return resolve(getRootUri(), uri, false);
    }

    /**
     * Transform an path in a valid URI for this backend.
     * 
     * @param path a path local to the current backend.
     * @return an URI that the current backend can handle.
     */
    public URI getUri(String path) {
        URI rootUri = getRootUri();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        try {
            return new URI(getScheme(), rootUri.getAuthority(), rootUri.getPath() + path, null, null);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    /**
     * Extract the local path from an URI.
     * 
     * @param uri The URI to parse.
     * @return the local path from the URI.
     */
    public String getPath(URI uri) {
        URI rootUri = getRootUri();
        uri = resolve(rootUri, uri, true);
        if (uri == null) {
            return null;
        }
        return "/" + uri.getPath();
    }

    protected abstract RrdBackend open(String path, boolean readOnly) throws IOException;

    /**
     * Creates RrdBackend object for the given storage path.
     *
     * @param path     Storage path
     * @param readOnly True, if the storage should be accessed in read/only mode.
     *                 False otherwise.
     * @return Backend object which handles all I/O operations for the given storage path
     * @throws java.io.IOException Thrown in case of I/O error.
     */
    RrdBackend getBackend(RrdDb rrdDb, String path, boolean readOnly) throws IOException {
        checkClosing();
        RrdBackend backend = open(path, readOnly);
        backend.done(this, new ClosingReference(rrdDb, backend, refQueue));
        return backend;
    }

    /**
     * Creates RrdBackend object for the given storage path.
     * @param rrdDb 
     *
     * @param uri     Storage uri
     * @param readOnly True, if the storage should be accessed in read/only mode.
     *                 False otherwise.
     * @return Backend object which handles all I/O operations for the given storage path
     * @throws java.io.IOException Thrown in case of I/O error.
     */
    RrdBackend getBackend(RrdDb rrdDb, URI uri, boolean readOnly) throws IOException {
        checkClosing();
        RrdBackend backend =  open(getPath(uri), readOnly);
        backend.done(this, new ClosingReference(rrdDb, backend, refQueue));
        return backend;
    }

    /**
     * Determines if a storage with the given path already exists.
     *
     * @param path Storage path
     * @throws java.io.IOException in case of I/O error.
     * @return a boolean.
     */
    protected abstract boolean exists(String path) throws IOException;

    /**
     * Determines if a storage with the given URI already exists.
     *
     * @param uri Storage URI.
     * @throws java.io.IOException in case of I/O error.
     * @return a boolean.
     */
    protected boolean exists(URI uri) throws IOException {
        return exists(getPath(uri));
    }

    /**
     * Determines if the header should be validated.
     *
     * @param path Storage path
     * @return a boolean.
     */
    protected boolean shouldValidateHeader(String path) {
        return validateHeader;
    }

    /**
     * Determines if the header should be validated.
     *
     * @param uri Storage URI
     * @return a boolean.
     */
    protected boolean shouldValidateHeader(URI uri) {
        return shouldValidateHeader(getPath(uri));
    }

    /**
     * Returns the name (primary ID) for the factory.
     *
     * @return Name of the factory.
     */
    public String getName() {
        return name;
    }

    /**
     * A generic close handle, default implementation does nothing.
     * @since 3.4
     * @throws IOException if the close fails
     */
    public void close() throws IOException {

    }

}
