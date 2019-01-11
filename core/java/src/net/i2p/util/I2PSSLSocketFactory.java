package net.i2p.util;

/*
 * Contains code adapted from:
 * Jetty SslContextFactory.java
 *
 *  =======================================================================
 *  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
 *  ------------------------------------------------------------------------
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 *  ========================================================================
 */

/*
 * Contains code adapted from:
 * Apache httpcomponents PublicSuffixMatcherLoader.java
 *
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import net.i2p.I2PAppContext;
import net.i2p.crypto.KeyStoreUtil;
import net.i2p.data.DataHelper;

import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.util.PublicSuffixList;
import org.apache.http.conn.util.PublicSuffixListParser;
import org.apache.http.conn.util.PublicSuffixMatcher;

/**
 * Loads trusted ASCII certs from ~/.i2p/certificates/ and $I2P/certificates/.
 *
 * TODO extend SSLSocketFactory
 *
 * @author zzz
 * @since 0.9.9 moved from ../client, original since 0.8.3
 */
public class I2PSSLSocketFactory {

    private static final String PROP_DISABLE = "i2p.disableSSLHostnameVerification";
    private static final String PROP_GEOIP_DIR = "geoip.dir";
    private static final String GEOIP_DIR_DEFAULT = "geoip";
    private static final String GEOIP_FILE_DEFAULT = "geoip.txt";
    private static final String COUNTRY_FILE_DEFAULT = "countries.txt";
    private static final String PUBLIC_SUFFIX_LIST = "public-suffix-list.txt";
    private static PublicSuffixMatcher DEFAULT_MATCHER;
    private static boolean _matcherLoaded;
    // not in countries.txt, but only the public ones, not the private ones
    private static final String[] DEFAULT_TLDS = {
        "arpa", "asia", "biz", "cat", "com", "coop",
        "edu", "gov", "info", "int", "jobs", "mil",
        "mobi", "museum", "name", "net", "org", "post",
        "pro", "tel", "travel", "xxx"
    };
    // not in countries.txt or public-suffix-list.txt
    private static final String[] ADDITIONAL_TLDS = {
        "i2p", "mooo.com", "onion"
    };

    /**
     *  Unmodifiable.
     *  Public for RouterConsoleRunner.
     *  @since 0.9.16
     */
    public static final List<String> EXCLUDE_PROTOCOLS = Collections.unmodifiableList(Arrays.asList(new String[] {
        "SSLv2Hello", "SSLv3"
    }));

    /**
     *  Java 7 does not enable 1.1 or 1.2 by default on the client side.
     *  Java 8 does enable 1.1 and 1.2 by default on the client side.
     *  1.3 in Java 11, but it requires:
     *    ChaCha20/Poly1305 in Java 12 (we could add a provider)
     *    X25519 in Java 13 but may be pulled in to 12 (can't use our unsigned provider)
     *    Ed25519 in Java 13 (but we can use our provider)
     *    ref: https://openjdk.java.net/jeps/332
     *
     *  ref: http://docs.oracle.com/javase/7/docs/technotes/guides/security/SunProviders.html
     *  Unmodifiable.
     *  Public for RouterConsoleRunner.
     *  @since 0.9.16
     */
    public static final List<String> INCLUDE_PROTOCOLS = Collections.unmodifiableList(Arrays.asList(new String[] {
        "TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"
    }));

    /**
     *  We exclude everything that Java 8 disables by default, plus some others.
     *  ref: http://docs.oracle.com/javase/8/docs/technotes/guides/security/SunProviders.html
     *  See also: https://developer.android.com/reference/javax/net/ssl/SSLSocket.html
     *  Unmodifiable.
     *  Public for RouterConsoleRunner.
     *  @since 0.9.16
     */
    public static final List<String> EXCLUDE_CIPHERS = Collections.unmodifiableList(Arrays.asList(new String[] {
        // following are disabled by default in Java 8
        "SSL_DH_anon_EXPORT_WITH_DES40_CBC_SHA",
        "SSL_DH_anon_EXPORT_WITH_RC4_40_MD5",
        "SSL_DH_anon_WITH_3DES_EDE_CBC_SHA",
        "SSL_DH_anon_WITH_DES_CBC_SHA",
        "SSL_DH_anon_WITH_RC4_128_MD5",
        "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA",
        "SSL_DHE_DSS_WITH_DES_CBC_SHA",
        "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
        "SSL_DHE_RSA_WITH_DES_CBC_SHA",
        "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
        "SSL_RSA_EXPORT_WITH_RC4_40_MD5",
        "SSL_RSA_WITH_DES_CBC_SHA",
        "SSL_RSA_WITH_NULL_MD5",
        "SSL_RSA_WITH_NULL_SHA",
        "TLS_DH_anon_WITH_AES_128_CBC_SHA",
        "TLS_DH_anon_WITH_AES_128_CBC_SHA256",
        "TLS_DH_anon_WITH_AES_128_GCM_SHA256",
        "TLS_DH_anon_WITH_AES_256_CBC_SHA",
        "TLS_DH_anon_WITH_AES_256_CBC_SHA256",
        "TLS_DH_anon_WITH_AES_256_GCM_SHA384",
        "TLS_ECDH_anon_WITH_3DES_EDE_CBC_SHA",
        "TLS_ECDH_anon_WITH_AES_128_CBC_SHA",
        "TLS_ECDH_anon_WITH_AES_256_CBC_SHA",
        "TLS_ECDH_anon_WITH_NULL_SHA",
        "TLS_ECDH_anon_WITH_RC4_128_SHA",
        "TLS_ECDH_ECDSA_WITH_NULL_SHA",
        "TLS_ECDHE_ECDSA_WITH_NULL_SHA",
        "TLS_ECDHE_RSA_WITH_NULL_SHA",
        "TLS_ECDH_RSA_WITH_NULL_SHA",
        "TLS_KRB5_EXPORT_WITH_DES_CBC_40_MD5",
        "TLS_KRB5_EXPORT_WITH_DES_CBC_40_SHA",
        "TLS_KRB5_EXPORT_WITH_RC4_40_MD5",
        "TLS_KRB5_EXPORT_WITH_RC4_40_SHA",
        "TLS_KRB5_WITH_3DES_EDE_CBC_MD5",
        "TLS_KRB5_WITH_3DES_EDE_CBC_SHA",
        "TLS_KRB5_WITH_DES_CBC_MD5",
        "TLS_KRB5_WITH_DES_CBC_SHA",
        "TLS_KRB5_WITH_RC4_128_MD5",
        "TLS_KRB5_WITH_RC4_128_SHA",
        "TLS_RSA_WITH_NULL_SHA256",
        // following are disabled because they are SSLv3
        "SSL_DHE_DSS_WITH_3DES_EDE_CBC_SHA",
        "SSL_DHE_RSA_WITH_3DES_EDE_CBC_SHA",
        "SSL_RSA_WITH_3DES_EDE_CBC_SHA",
        "SSL_RSA_WITH_RC4_128_MD5",
        "SSL_RSA_WITH_RC4_128_SHA",
        // following are disabled because they are RC4
        "TLS_ECDH_ECDSA_WITH_RC4_128_SHA",
        "TLS_ECDH_RSA_WITH_RC4_128_SHA",
        "TLS_ECDHE_ECDSA_WITH_RC4_128_SHA",
        "TLS_ECDHE_RSA_WITH_RC4_128_SHA",
        // following are disabled because they are 3DES
        "TLS_ECDH_ECDSA_WITH_3DES_EDE_CBC_SHA",
        "TLS_ECDH_RSA_WITH_3DES_EDE_CBC_SHA",
        "TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA",
        "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA",
        // following is disabled because it is weak
        // see e.g. https://bugzilla.mozilla.org/show_bug.cgi?id=1107787
        "TLS_DHE_DSS_WITH_AES_128_CBC_SHA"
        // ??? "TLS_DHE_DSS_WITH_AES_256_CBC_SHA"
        //
        //  NOTE:
        //  If you add anything here, please also add to installer/resources/eepsite/jetty-ssl.xml
        //
    }));

    /**
     *  Nothing for now.
     *  There's nothing disabled by default we would want to enable.
     *  Unmodifiable.
     *  Public for RouterConsoleRunner.
     *  @since 0.9.16
     */
    public static final List<String> INCLUDE_CIPHERS = Collections.emptyList();

    /** the "real" factory */
    private final SSLSocketFactory _factory;
    private final I2PAppContext _context;

    /**
     * @param relativeCertPath e.g. "certificates/i2cp"
     * @since 0.9.9 was static
     */
    public I2PSSLSocketFactory(I2PAppContext context, boolean loadSystemCerts, String relativeCertPath)
                               throws GeneralSecurityException {
        _factory = initSSLContext(context, loadSystemCerts, relativeCertPath);
        _context = context;
    }

    /**
     * Returns a socket to the host.
     *
     * A host argument that's an IP address (instead of a host name)
     * is not recommended, as this will probably fail
     * SSL certificate validation.
     *
     * Hostname validation is skipped for localhost addresses, but you still
     * must trust the certificate.
     *
     */
    public Socket createSocket(String host, int port) throws IOException {
        SSLSocket rv = (SSLSocket) _factory.createSocket(host, port);
        setProtocolsAndCiphers(rv);
        verifyHostname(_context, rv, host);
        return rv;
    }

    /**
     * Returns a socket to the host.
     *
     * An InetAddress argument created with an IP address (instead of a host name)
     * is not recommended, as this will perform a reverse DNS lookup to
     * get the host name for certificate validation, which will probably then fail.
     *
     * Hostname validation is skipped for localhost addresses, but you still
     * must trust the certificate.
     *
     * @since 0.9.9
     */
    public Socket createSocket(InetAddress host, int port) throws IOException {
        SSLSocket rv = (SSLSocket) _factory.createSocket(host, port);
        setProtocolsAndCiphers(rv);
        String name = host.getHostName();
        verifyHostname(_context, rv, name);
        return rv;
    }

    /**
     *  Validate the hostname
     *
     *  ref: https://developer.android.com/training/articles/security-ssl.html
     *  ref: http://op-co.de/blog/posts/java_sslsocket_mitm/
     *  ref: http://kevinlocke.name/bits/2012/10/03/ssl-certificate-verification-in-dispatch-and-asynchttpclient/
     *
     *  @throws SSLException on hostname verification failure
     *  @since 0.9.20
     */
    public static void verifyHostname(I2PAppContext ctx, SSLSocket socket, String host) throws SSLException {
        Log log = ctx.logManager().getLog(I2PSSLSocketFactory.class);
        if (ctx.getBooleanProperty(PROP_DISABLE) ||
            host.equals("localhost") ||
            host.equals("127.0.0.1") ||
            host.equals("::1") ||
            host.equals("0:0:0:0:0:0:0:1")) {
            if (log.shouldWarn())
                log.warn("Skipping hostname validation for " + host);
            return;
        }
        HostnameVerifier hv;
        if (SystemVersion.isAndroid()) {
            // https://developer.android.com/training/articles/security-ssl.html
            hv = HttpsURLConnection.getDefaultHostnameVerifier();
        } else {
            // haha the above may work for Android but it doesn't in Oracle
            //
            // quote http://kevinlocke.name/bits/2012/10/03/ssl-certificate-verification-in-dispatch-and-asynchttpclient/ :
            // Unlike SSLContext, using the Java default (HttpsURLConnection.getDefaultHostnameVerifier)
            // is not a viable option because the default HostnameVerifier expects to only be called
            // in the case that there is a mismatch (and therefore always returns false) while some
            // of the AsyncHttpClient providers (e.g. Netty, the default) call it on all connections.
            // To make matters worse, the check is not trivial (consider SAN and wildcard matching)
            // and is implemented in sun.security.util.HostnameChecker (a Sun internal proprietary API).
            // This leaves the developer in the position of either depending on an internal API or
            // finding/copying/creating another implementation of this functionality.
            //
            hv = new DefaultHostnameVerifier(getDefaultMatcher(ctx));
        }
        SSLSession sess = socket.getSession();
        // Verify that the certicate hostname is for mail.google.com
        // This is due to lack of SNI support in the current SSLSocket.
        if (!hv.verify(host, sess)) {
            throw new SSLHandshakeException("SSL hostname verify failed, Expected " + host +
                                            // throws SSLPeerUnverifiedException
                                            //", found " + sess.getPeerPrincipal() +
                                            // returns null
                                            //", found " + sess.getPeerHost() +
                                            // enable logging for DefaultHostnameVerifier to find out the CN and SANs
                                            " - set " + PROP_DISABLE +
                                            "=true to disable verification (dangerous!)");
        }
        // At this point SSLSocket performed certificate verificaiton and
        // we have performed hostname verification, so it is safe to proceed.
    }

    /**
     *  From Apache PublicSuffixMatcherLoader.getDefault()
     *
     *  https://publicsuffix.org/list/effective_tld_names.dat
     *  What does this get us?
     *  Deciding whether to issue or accept an SSL wildcard certificate for *.public.suffix.
     *
     *  @return null on failure
     *  @since 0.9.20
     */
    private static PublicSuffixMatcher getDefaultMatcher(I2PAppContext ctx) {
        synchronized (I2PSSLSocketFactory.class) {
            if (!_matcherLoaded) {
                String geoDir = ctx.getProperty(PROP_GEOIP_DIR, GEOIP_DIR_DEFAULT);
                File geoFile = new File(geoDir);
                if (!geoFile.isAbsolute())
                    geoFile = new File(ctx.getBaseDir(), geoDir);
                geoFile = new File(geoFile, PUBLIC_SUFFIX_LIST);
                Log log = ctx.logManager().getLog(I2PSSLSocketFactory.class);
                if (geoFile.exists()) {
                    try {
                        // we can't use PublicSuffixMatcherLoader.load() here because we
                        // want to add some of our own and a PublicSuffixMatcher's
                        // underlying PublicSuffixList is immutable and inaccessible
                        long begin = System.currentTimeMillis();
                        InputStream in = null;
                        PublicSuffixList list = new PublicSuffixList(Arrays.asList(ADDITIONAL_TLDS),
                                                                     Collections.<String>emptyList());
                        try {
                            in = new FileInputStream(geoFile);
                            PublicSuffixList list2 = new PublicSuffixListParser().parse(
                                 new InputStreamReader(in, "UTF-8"));
                            list = merge(list, list2);
                        } finally {
                            try { if (in != null) in.close(); } catch (IOException ioe) {}
                        }
                        DEFAULT_MATCHER = new PublicSuffixMatcher(list.getRules(), list.getExceptions());
                        if (log.shouldWarn())
                            log.warn("Loaded " + geoFile + " in " + (System.currentTimeMillis() - begin) +
                                     " ms and created list with " + list.getRules().size() + " entries and " +
                                     list.getExceptions().size() + " exceptions");
                    } catch (IOException ex) {
                         log.error("Failure loading public suffix list from " + geoFile, ex);
                         // DEFAULT_MATCHER remains null
                    }
                } else {
                    List<String> list = new ArrayList<String>(320);
                    addCountries(ctx, list);
                    list.addAll(Arrays.asList(DEFAULT_TLDS));
                    list.addAll(Arrays.asList(ADDITIONAL_TLDS));
                    DEFAULT_MATCHER = new PublicSuffixMatcher(list, null);
                    if (log.shouldWarn())
                        log.warn("No public suffix list found at " + geoFile +
                                 " - created default with " + list.size() + " entries");
                }
            }
            _matcherLoaded = true;
        }
        return DEFAULT_MATCHER;
    }

   /**
    *  Merge two PublicSuffixLists
    *  Have to do this because they are unmodifiable
    *
    *  @since 0.9.20
    */
    private static PublicSuffixList merge(PublicSuffixList a, PublicSuffixList b) {
        List<String> ar = a.getRules();
        List<String> ae = a.getExceptions();
        List<String> br = b.getRules();
        List<String> be = b.getExceptions();
        List<String> cr = new ArrayList<String>(ar.size() + br.size());
        List<String> ce = new ArrayList<String>(ae.size() + be.size());
        cr.addAll(ar);
        cr.addAll(br);
        ce.addAll(ae);
        ce.addAll(be);
        return new PublicSuffixList(cr, ce);
    }

   /**
    *  Read in the country file and add all TLDs to the list.
    *  It would almost be easier just to add all possible 26*26 two-letter codes.
    *
    *  @param tlds out parameter
    *  @since 0.9.20 adapted from GeoIP.loadCountryFile()
    */
    private static void addCountries(I2PAppContext ctx, List<String> tlds) {
        Log log = ctx.logManager().getLog(I2PSSLSocketFactory.class);
        String geoDir = ctx.getProperty(PROP_GEOIP_DIR, GEOIP_DIR_DEFAULT);
        File geoFile = new File(geoDir);
        if (!geoFile.isAbsolute())
            geoFile = new File(ctx.getBaseDir(), geoDir);
        geoFile = new File(geoFile, COUNTRY_FILE_DEFAULT);
        if (!geoFile.exists()) {
            if (log.shouldWarn())
                log.warn("Country file not found: " + geoFile.getAbsolutePath());
            return;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(geoFile), "UTF-8"));
            String line = null;
            int i = 0;
            while ( (line = br.readLine()) != null) {
                try {
                    if (line.charAt(0) == '#')
                        continue;
                    String[] s = DataHelper.split(line, ",");
                    String lc = s[0].toLowerCase(Locale.US);
                    tlds.add(lc);
                    i++;
                } catch (IndexOutOfBoundsException ioobe) {}
            }
            if (log.shouldInfo())
                log.info("Loaded " + i + " TLDs from " + geoFile.getAbsolutePath());
        } catch (IOException ioe) {
            log.error("Error reading the Country File", ioe);
        } finally {
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  Loads certs from
     *  the ~/.i2p/certificates/ and $I2P/certificates/ directories.
     */
    private static SSLSocketFactory initSSLContext(I2PAppContext context, boolean loadSystemCerts, String relativeCertPath)
                               throws GeneralSecurityException {
        Log log = context.logManager().getLog(I2PSSLSocketFactory.class);
        KeyStore ks;
        if (loadSystemCerts) {
            ks = KeyStoreUtil.loadSystemKeyStore();
            if (ks == null)
                throw new GeneralSecurityException("Key Store init error");
        } else {
            try {
                ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, "".toCharArray());
            } catch (IOException ioe) {
                throw new GeneralSecurityException("Key Store init error", ioe);
            }
        }

        File dir = new File(context.getConfigDir(), relativeCertPath);
        int adds = KeyStoreUtil.addCerts(dir, ks);
        int totalAdds = adds;
        if (adds > 0) {
            if (log.shouldLog(Log.INFO))
                log.info("Loaded " + adds + " trusted certificates from " + dir.getAbsolutePath());
        }

        File dir2 = new File(context.getBaseDir(), relativeCertPath);
        if (!dir.getAbsolutePath().equals(dir2.getAbsolutePath())) {
            adds = KeyStoreUtil.addCerts(dir2, ks);
            totalAdds += adds;
            if (adds > 0) {
                if (log.shouldLog(Log.INFO))
                    log.info("Loaded " + adds + " trusted certificates from " + dir.getAbsolutePath());
            }
        }
        if (totalAdds > 0 || loadSystemCerts) {
            if (log.shouldLog(Log.INFO))
                log.info("Loaded total of " + totalAdds + " new trusted certificates");
        } else {
            String msg = "No trusted certificates loaded (looked in " +
                       dir.getAbsolutePath() + (dir.getAbsolutePath().equals(dir2.getAbsolutePath()) ? "" : (" and " + dir2.getAbsolutePath())) +
                       ", SSL connections will fail. " +
                       "Copy the cert in " + relativeCertPath + " from the router to the directory.";
            // don't continue, since we didn't load the system keystore, we have nothing.
            throw new GeneralSecurityException(msg);
        }

            SSLContext sslc = SSLContext.getInstance("TLS");
            TrustManagerFactory tmf =   TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            sslc.init(null, tmf.getTrustManagers(), context.random());
            return sslc.getSocketFactory();
    }

    /**
     * Select protocols and cipher suites to be used
     * based on configured inclusion and exclusion lists
     * as well as enabled and supported protocols and cipher suites.
     *
     * Adapted from Jetty SslContextFactory.java
     *
     * @since 0.9.16
     */
    public static void setProtocolsAndCiphers(SSLSocket socket) {
        socket.setEnabledProtocols(selectProtocols(socket.getEnabledProtocols(),
                                                   socket.getSupportedProtocols()));
        socket.setEnabledCipherSuites(selectCipherSuites(socket.getEnabledCipherSuites(),
                                                         socket.getSupportedCipherSuites()));
    }

    /**
     * Select protocols and cipher suites to be used
     * based on configured inclusion and exclusion lists
     * as well as enabled and supported protocols and cipher suites.
     *
     * Adapted from Jetty SslContextFactory.java
     *
     * @since 0.9.16
     */
    public static void setProtocolsAndCiphers(SSLServerSocket socket) {
        String[] p = selectProtocols(socket.getEnabledProtocols(),
                                     socket.getSupportedProtocols());
        for (int i = 0; i < p.length; i++) {
            // if we left SSLv3 in there, we don't support TLS,
            // so we should't remove the SSL ciphers
            if (p[i].equals("SSLv3"))
                return;
        }
        socket.setEnabledProtocols(p);
        socket.setEnabledCipherSuites(selectCipherSuites(socket.getEnabledCipherSuites(),
                                                         socket.getSupportedCipherSuites()));
    }

    /**
     * Select protocols to be used
     * based on configured inclusion and exclusion lists
     * as well as enabled and supported protocols.
     *
     * Adapted from Jetty SslContextFactory.java
     *
     * @param enabledProtocols Array of enabled protocols
     * @param supportedProtocols Array of supported protocols
     * @return Array of protocols to enable
     * @since 0.9.16
     */
    private static String[] selectProtocols(String[] enabledProtocols, String[] supportedProtocols) {
         return select(enabledProtocols, supportedProtocols, INCLUDE_PROTOCOLS, EXCLUDE_PROTOCOLS);
    }

    /**
     * Select cipher suites to be used
     * based on configured inclusion and exclusion lists
     * as well as enabled and supported cipher suite lists.
     *
     * Adapted from Jetty SslContextFactory.java
     *
     * @param enabledCipherSuites Array of enabled cipher suites
     * @param supportedCipherSuites Array of supported cipher suites
     * @return Array of cipher suites to enable
     * @since 0.9.16
     */
    private static String[] selectCipherSuites(String[] enabledCipherSuites, String[] supportedCipherSuites) {
         return select(enabledCipherSuites, supportedCipherSuites, INCLUDE_CIPHERS, EXCLUDE_CIPHERS);
    }

    /**
     * Adapted from Jetty SslContextFactory.java
     *
     * @param toEnable Add all these to what is enabled, if supported
     * @param toExclude Remove all these from what is enabled
     * @since 0.9.16
     */
    private static String[] select(String[] enabledArr, String[] supportedArr,
                            List<String> toEnable, List<String> toExclude) {
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(I2PSSLSocketFactory.class);
        Set<String> selected = new HashSet<String>(enabledArr.length);
        selected.addAll(Arrays.asList(enabledArr));
        selected.removeAll(toExclude);
        Set<String> supported = new HashSet<String>(supportedArr.length);
        supported.addAll(Arrays.asList(supportedArr));
        for (String s : toEnable) {
            if (supported.contains(s)) {
                if (selected.add(s)) {
                   if (log.shouldLog(Log.INFO))
                       log.info("Added, previously disabled: " + s);
                }
            } else if (log.shouldLog(Log.INFO)) {
                log.info("Not supported in this JVM: " + s);
            }
        }
        if (selected.isEmpty()) {
            // shouldn't happen, Java 6 supports TLSv1
            log.logAlways(Log.WARN, "No TLS support for SSLEepGet, falling back");
            return enabledArr;
        }
        if (log.shouldLog(Log.DEBUG)) {
            List<String> foo = new ArrayList<String>(selected);
            Collections.sort(foo);
            log.debug("Selected: " + foo);
        }
        return selected.toArray(new String[selected.size()]);
    }
}
