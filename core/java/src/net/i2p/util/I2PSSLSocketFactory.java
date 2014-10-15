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

import java.io.File;
import java.io.IOException;
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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import net.i2p.I2PAppContext;
import net.i2p.crypto.KeyStoreUtil;

/**
 * Loads trusted ASCII certs from ~/.i2p/certificates/ and $I2P/certificates/.
 *
 * @author zzz
 * @since 0.9.9 moved from ../client, original since 0.8.3
 */
public class I2PSSLSocketFactory {

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
     *  ref: http://docs.oracle.com/javase/7/docs/technotes/guides/security/SunProviders.html
     *  Unmodifiable.
     *  Public for RouterConsoleRunner.
     *  @since 0.9.16
     */
    public static final List<String> INCLUDE_PROTOCOLS = Collections.unmodifiableList(Arrays.asList(new String[] {
        "TLSv1", "TLSv1.1", "TLSv1.2"
    }));

    /**
     *  We exclude everything that Java 8 disables by default, plus some others.
     *  ref: http://docs.oracle.com/javase/8/docs/technotes/guides/security/SunProviders.html
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
        "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA"
    }));

    /**
     *  Nothing for now.
     *  There's nothing disabled by default we would want to enable.
     *  Unmodifiable.
     *  Public for RouterConsoleRunner.
     *  @since 0.9.16
     */
    public static final List<String> INCLUDE_CIPHERS = Collections.emptyList();

    private final SSLSocketFactory _factory;

    /**
     * @param relativeCertPath e.g. "certificates/i2cp"
     * @since 0.9.9 was static
     */
    public I2PSSLSocketFactory(I2PAppContext context, boolean loadSystemCerts, String relativeCertPath)
                               throws GeneralSecurityException {
        _factory = initSSLContext(context, loadSystemCerts, relativeCertPath);
    }

    /**
     * Returns a socket to the host.
     */
    public Socket createSocket(String host, int port) throws IOException {
        SSLSocket rv = (SSLSocket) _factory.createSocket(host, port);
        setProtocolsAndCiphers(rv);
        return rv;
    }

    /**
     * Returns a socket to the host.
     * @since 0.9.9
     */
    public Socket createSocket(InetAddress host, int port) throws IOException {
        SSLSocket rv = (SSLSocket) _factory.createSocket(host, port);
        setProtocolsAndCiphers(rv);
        return rv;
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
        socket.setEnabledProtocols(selectProtocols(socket.getEnabledProtocols(),
                                                   socket.getSupportedProtocols()));
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
            List<String> foo = new ArrayList(selected);
            Collections.sort(foo);
            log.debug("Selected: " + foo);
        }
        return selected.toArray(new String[selected.size()]);
    }
}
