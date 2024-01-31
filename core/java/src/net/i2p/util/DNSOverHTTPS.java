package net.i2p.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import gnu.getopt.Getopt;

import org.minidns.dnsmessage.DnsMessage;
import org.minidns.dnsmessage.Question;
import org.minidns.record.A;
import org.minidns.record.AAAA;
import org.minidns.record.CNAME;
import org.minidns.record.Data;
import org.minidns.record.Record;
import org.minidns.record.Record.TYPE;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.Addresses;

/**
 *  Simple implemetation of DNS over HTTPS.
 *  Also sets the local clock from the received date header.
 *
 *  Warning - not thread-safe. Create new instances as necessary.
 *
 *  As of 0.9.49, this supports the RFC 8484 (DNS) format only.
 *  Does NOT support the JSON format (used prior to 0.9.49)
 *  or RFC 7858 (DNS over TLS).
 *
 *  https://developers.google.com/speed/public-dns/docs/dns-over-https
 *  https://developers.cloudflare.com/1.1.1.1/dns-over-https/json-format/
 *  https://github.com/curl/curl/wiki/DNS-over-HTTPS
 *
 *  @since 0.9.35
 */
public class DNSOverHTTPS implements EepGet.StatusListener {
    private final I2PAppContext ctx;
    private final Log _log;
    private final ByteArrayOutputStream baos;
    private SSLEepGet.SSLState state;
    private long fetchStart;
    private int gotDate;

    private static final Map<String, Result> v4Cache = new LHMCache<String, Result>(32);
    private static final Map<String, Result> v6Cache = new LHMCache<String, Result>(32);
    // v4 URLs to query, ending with '&'
    private static final List<String> v4urls = new ArrayList<String>(8);
    // v6 URLs to query, ending with '&'
    private static final List<String> v6urls = new ArrayList<String>(8);
    // consecutive failures
    private static final ObjectCounter<String> fails = new ObjectCounter<String>();

    /**
     *  ESR version of Firefox, same as Tor Browser
     *
     *  @since public since 0.9.54 for i2ptunnel
     */
    public static final String UA_CLEARNET = "Mozilla/5.0 (Windows NT 10.0; rv:109.0) Gecko/20100101 Firefox/115.0";

    private static final int MAX_RESPONSE_SIZE = 2048;
    private static final boolean DEBUG = false;

    // Don't look up any of these TLDs
    // RFC 2606, 3166, 6303, 7393
    // https://www.iana.org/assignments/locally-served-dns-zones/locally-served-dns-zones.xhtml
    // https://ithi.research.icann.org/graph-m3.html#M332
    // https://tools.ietf.org/html/draft-ietf-dnsop-private-use-tld-00
    private static final List<String> locals = Arrays.asList(new String[] {
        "localhost",
        "in-addr.arpa", "ip6.arpa", "home.arpa",
        "i2p", "onion",
        "i2p.arpa", "onion.arpa",
        "corp", "home", "internal", "intranet", "lan", "local", "private",
        "dhcp", "localdomain", "bbrouter", "dlink", "ctc", "intra", "loc", "modem", "ip",
        "test", "example", "invalid",
        "alt",
        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
        "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
        "aa",
        "qm", "qn", "qo", "qp", "qq", "qr", "qs", "qt", "qu", "qv", "qw", "qx", "qy", "qz",
        "xa", "xb", "xc", "xd", "xe", "xf", "xg", "xh", "xi", "xj", "xk", "xl", "xm",
        "xn", "xo", "xp", "xq", "xr", "xs", "xt", "xu", "xv", "xw", "xx", "xy", "xz",
        "zz"
    } );

    static {
        // Public lists:
        // https://dnscrypt.info/public-servers/
        // https://github.com/curl/curl/wiki/DNS-over-HTTPS#publicly-available-servers
        // https://dnsprivacy.org/wiki/display/DP/DNS+Privacy+Public+Resolvers#DNSPrivacyPublicResolvers-DNS-over-HTTPS(DoH)

        // Google
        // https://developers.google.com/speed/public-dns/docs/doh/
        // 8.8.8.8 and 8.8.4.4 now redirect to dns.google, but SSLEepGet doesn't support redirect
        v4urls.add("https://dns.google/dns-query");
        v6urls.add("https://dns.google/dns-query");
        // Cloudflare cloudflare-dns.com
        // https://developers.cloudflare.com/1.1.1.1/nitty-gritty-details/
        // 1.1.1.1 is a privacy centric resolver so it does not send any client IP information
        // and does not send the EDNS Client Subnet Header to authoritative servers
        v4urls.add("https://1.1.1.1/dns-query");
        v4urls.add("https://1.0.0.1/dns-query");
        v6urls.add("https://[2606:4700:4700::1111]/dns-query");
        v6urls.add("https://[2606:4700:4700::1001]/dns-query");
        // Quad9
        // https://quad9.net/doh-quad9-dns-servers/
        v4urls.add("https://9.9.9.9/dns-query");
        v4urls.add("https://149.112.112.112/dns-query");
        v6urls.add("https://[2620:fe::fe]/dns-query");
        v6urls.add("https://[2620:fe::fe:9]/dns-query");

        loadURLs();
    }

    // keep the timeout very short, as we try multiple addresses,
    // and will be falling back to regular DNS.
    private static final long TIMEOUT = 3*1000;
    // total for v4 + v6
    private static final long OVERALL_TIMEOUT = 10*1000;
    private static final int MAX_TTL = 24*60*60;
    // don't use a URL after this many consecutive failures
    private static final int MAX_FAILS = 3;
    // each for v4 and v6
    private static final int MAX_REQUESTS = 4;
    private static final int MAX_DATE_SETS = 2;
    // From RouterClock
    private static final int DEFAULT_STRATUM = 8;

    public DNSOverHTTPS(I2PAppContext context) {
        this(context, null);
    }

    public DNSOverHTTPS(I2PAppContext context, SSLEepGet.SSLState sslState) {
        ctx = context;
        _log = ctx.logManager().getLog(DNSOverHTTPS.class);
        state = sslState;
        baos = new ByteArrayOutputStream(512);
    }

    public enum Type { V4_ONLY, V6_ONLY, V4_PREFERRED, V6_PREFERRED }

    private static class Result {
        public final String ip;
        public final long expires;
        public Result(String i, long e) {
            ip = i; expires = e;
        }
    }

    /**
     *  V4_ONLY unless we have only IPv6 address, then V6_ONLY
     *  @return null if not found
     */
    public String lookup(String host) {
        Set<AddressType> addrs = Addresses.getConnectedAddressTypes();
        Type type = (addrs.contains(AddressType.IPV4) || !addrs.contains(AddressType.IPV6)) ? Type.V4_ONLY : Type.V6_ONLY;
        return lookup(host, type);
    }

    /**
     *  Lookup in cache, then query servers
     *  @return null if not found
     */
    public String lookup(String host, Type type) {
        return lookup(host, type, null);
    }

    /**
     *  Lookup in cache, then query servers
     *  @param url null to query several default servers, or specify single server
     *  @return null if not found
     *  @since 0.9.48
     */
    private String lookup(String host, Type type, String url) {
        if (Addresses.isIPAddress(host))
            return host;
        if (host.startsWith("["))
            return host;
        host = host.toLowerCase(Locale.US);
        if (host.indexOf('.') < 0)
            return null;
        for (String local : locals) {
            if (host.equals(local) ||
                (host.endsWith(local) && host.charAt(host.length() - local.length() - 1) == '.')) {
                return null;
            }
        }
        // don't loop via SSLEepGet
        if (host.equals("dns.google"))
            return "8.8.8.8";
        if (type == Type.V4_ONLY || type == Type.V4_PREFERRED) {
            // v4 lookup
            String rv = lookup(host, v4Cache);
            if (rv != null)
                return rv;
        }
        if (type != Type.V4_ONLY) {
            // v6 lookup
            String rv = lookup(host, v6Cache);
            if (rv != null)
                return rv;
        }
        if (type == Type.V6_PREFERRED) {
            // v4 lookup after v6 lookup
            String rv = lookup(host, v4Cache);
            if (rv != null)
                return rv;
        }
        return query(host, type, url);
    }

    public static void clearCaches() {
        synchronized (v4Cache) {
            v4Cache.clear();
        }
        synchronized (v6Cache) {
            v6Cache.clear();
        }
        fails.clear();
    }

    /**
     *  Lookup in cache
     *  @return null if not found or expired
     */
    private static String lookup(String host, Map<String, Result> cache) {
        synchronized (cache) {
            Result r = cache.get(host);
            if (r != null) {
                if (r.expires >= System.currentTimeMillis())
                    return r.ip;
                cache.remove(host);
            }
        }
        return null;
    }

    /**
     *  Query servers
     *  @param url null to query several default servers, or specify single server
     *  @return null if not found
     */
    private String query(String host, Type type, String url) {
        List<String> toQuery;
        if (url != null) {
            toQuery = Collections.singletonList(url);
        } else {
            toQuery = new ArrayList<String>((type == Type.V6_ONLY) ? v6urls : v4urls);
            Collections.shuffle(toQuery);
        }
        final long timeout = System.currentTimeMillis() + OVERALL_TIMEOUT;
        if (type == Type.V4_ONLY || type == Type.V4_PREFERRED) {
            // v4 query
            String rv = query(host, false, toQuery, timeout);
            if (rv != null)
                return rv;
        }
        if (type != Type.V4_ONLY) {
            // v6 query
            String rv = query(host, true, toQuery, timeout);
            if (rv != null)
                return rv;
        }
        if (type == Type.V6_PREFERRED) {
            // v4 query after v6 query
            String rv = query(host, false, toQuery, timeout);
            if (rv != null)
                return rv;
        }
        return null;
    }

    /**
     *  @return null if not found
     */
    private String query(String host, boolean isv6, List<String> toQuery, long timeout) {
        Question q = new Question(host, isv6 ? TYPE.AAAA : TYPE.A);
        DnsMessage msg = DnsMessage.builder()
                                   .setId(0)
                                   .setOpcode(DnsMessage.OPCODE.QUERY)
                                   .setQrFlag(false)
                                   .setRecursionDesired(true)
                                   .setQuestion(q)
                                   .build();
        byte[] msgb = msg.toArray();
        String msgb64 = Base64.encode(msgb, true);
        // google (and only google) returns 400 for trailing unescaped '='
        // and rejects %3d also
        msgb64 = msgb64.replace("=", "");
        if (DEBUG) {
            log(msg.asTerminalOutput());
            log(msgb64);
        }
        int requests = 0;
        final String loopcheck = "https://" + host + '/';
        for (String url : toQuery) {
            if (requests >= MAX_REQUESTS)
                break;
            if (System.currentTimeMillis() >= timeout)
                break;
            if (url.startsWith(loopcheck))
                continue;
            if (fails.count(url) > MAX_FAILS)
                continue;
            String furl = url + "?dns=" + msgb64;
            log("Fetching " + furl);
            baos.reset();
            SSLEepGet eepget = new SSLEepGet(ctx, baos, furl, MAX_RESPONSE_SIZE, state);
            eepget.forceDNSOverHTTPS(false);
            eepget.addHeader("User-Agent", UA_CLEARNET);
            eepget.addHeader("Accept", "application/dns-message");
            if (ctx.isRouterContext())
                eepget.addStatusListener(this);
            else
                fetchStart = System.currentTimeMillis();  // debug
            String rv = fetch(eepget, host, isv6, q);
            if (rv != null) {
                fails.clear(url);
                return rv;
            }
            if (state == null)
                state = eepget.getSSLState();
            // we treat all fails the same, whether server responded or not
            requests++;
            fails.increment(url);
            log("No result from " + furl);
        }
        log("No result after " + requests + " attempts");
        return null;
    }

    /**
     *  @return null if not found
     */
    private String fetch(SSLEepGet eepget, String host, boolean isv6, Question q) {
        if (eepget.fetch(TIMEOUT, TIMEOUT, TIMEOUT) &&
            eepget.getStatusCode() == 200 && baos.size() > 0) {
            long end = System.currentTimeMillis();
            log("Got response in " + (end - fetchStart) + "ms");
            byte[] b = baos.toByteArray();
            try {
                DnsMessage msg = new DnsMessage(b);
                if (DEBUG) {
                    log("Response:\n" + msg.asTerminalOutput());
                }
                if (msg.responseCode != DnsMessage.RESPONSE_CODE.NO_ERROR) {
                    log("Response: " + msg.responseCode);
                    return null;
                }
                Set<Data> ans = msg.getAnswersFor(q);
                if (ans == null || ans.isEmpty()) {
                    // make another question to get the CNAME answers
                    q = new Question(host, TYPE.CNAME);
                    ans = msg.getAnswersFor(q);
                    if (ans == null || ans.isEmpty()) {
                        log("No answers");
                        return null;
                    }
                    // process CNAME
                    // we only do this once, we won't loop
                    for (Data d : ans) {
                        if (d.getType() == TYPE.CNAME) {
                            CNAME resp = (CNAME) d;
                            String tgt = resp.getTarget().toString();
                            log("CNAME is: " + tgt);
                            // make another question to get the real answers
                            q = new Question(tgt, isv6 ? TYPE.AAAA : TYPE.A);
                            ans = msg.getAnswersFor(q);
                            if (ans == null || ans.isEmpty()) {
                                log("CNAME but no answers");
                                return null;
                            }
                            break;
                        }
                    }
                }
                log(ans.size() + " answers");
                String data = null;
                for (Data d : ans) {
                    if (isv6) {
                        if (d.getType() != TYPE.AAAA)
                            continue;
                        AAAA resp = (AAAA) d;
                        byte[] ip = resp.getIp();
                        data = Addresses.toString(ip);
                        break;
                    } else {
                        if (d.getType() != TYPE.A)
                            continue;
                        A resp = (A) d;
                        byte[] ip = resp.getIp();
                        data = Addresses.toString(ip);
                        break;
                    }
                }
                if (data == null)
                    return null;
                long ttl = msg.getAnswersMinTtl();
                int ittl = (int) Math.min(ttl, MAX_TTL);
                long expires = end + (ittl * 1000L);
                Map<String, Result> cache = isv6 ? v6Cache : v4Cache;
                synchronized(cache) {
                    cache.put(host, new Result(data, expires));
                }
                log("Got answer: " + host + ' ' + ttl + ' ' + data + " in " + (end - fetchStart) + "ms");
                return data;
            } catch (Exception e) {
                log("Fail parsing", e);
            }
        } else {
            log("Fail fetching, rc: " + eepget.getStatusCode());
            if (DEBUG && baos.size() > 0) {
                // google says "the HTTP body should explain the error"
                log("Response body:\n" + DataHelper.getUTF8(baos.toByteArray()));
            }
        }
        return null;
    }

    // EepGet status listeners Reseeder
    public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {}
    public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {}
    public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {}
    public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {}

    public void attempting(String url) {
        if (gotDate < MAX_DATE_SETS)
            fetchStart = System.currentTimeMillis();
    }

    /**
     *  Use the Date header as a backup time source.
     *  Code from Reseeder.
     *  We set the stratum to a lower (better) value than in Reseeder,
     *  as Cloudflare and Google probably have a better idea than our reseeds.
     */
    public void headerReceived(String url, int attemptNum, String key, String val) {
        // We do this more than once, because
        // the first SSL handshake may take a while, and it may take the server
        // a while to render the index page.
        if (gotDate < MAX_DATE_SETS && "date".equals(key.toLowerCase(Locale.US))) {
            long timeRcvd = System.currentTimeMillis();
            long serverTime = RFC822Date.parse822Date(val);
            if (serverTime > 0) {
                // add 500ms since it's 1-sec resolution, and add half the RTT
                long now = serverTime + 500 + ((timeRcvd - fetchStart) / 2);
                long offset = now - ctx.clock().now();
                if (ctx.clock().getUpdatedSuccessfully()) {
                    // 2nd time better than the first
                    if (gotDate > 0)
                        ctx.clock().setNow(now, DEFAULT_STRATUM - 4);
                    else
                        ctx.clock().setNow(now, DEFAULT_STRATUM - 3);
                    log("DNSOverHTTPS adjusting clock by " +
                        DataHelper.formatDuration(Math.abs(offset)));
                } else {
                    // No peers or NTP yet, this is probably better than the peer average will be for a while
                    // default stratum - 1, so the peer average is a worse stratum
                    ctx.clock().setNow(now, DEFAULT_STRATUM - 3);
                    log("DNSOverHTTPS setting initial clock skew to " +
                        DataHelper.formatDuration(Math.abs(offset)));
                }
                gotDate++;
            }
        }
    }

    // End of EepGet status listeners

    private void log(String msg) {
        log(msg, null);
    }

    private void log(String msg, Throwable t) {
        int level = (t != null) ? Log.WARN : Log.INFO;
        _log.log(level, msg, t);
    }

    /**
     *  @since 0.9.49
     */
    private static void loadURLs() {
        BufferedReader in = null;
        try {
            InputStream is = DNSOverHTTPS.class.getResourceAsStream("/net/i2p/util/resources/dohservers.txt");
            if (is == null) {
                System.out.println("Warning: dohservers.txt resource not found, contact packager");
                return;
            }
            in = new BufferedReader(new InputStreamReader(is, "ISO-8859-1"), 4096);
            int count = 0;
            String line = null;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("https://"))
                    continue;
                try {
                    URI uri = new URI(line);
                    String host = uri.getHost();
                    if (host == null)
                        continue;
                    if (!Addresses.isIPv6Address(host))
                        v4urls.add(line);
                    if (!Addresses.isIPv4Address(host))
                        v6urls.add(line);
                    count++;
                } catch (Exception e) {
                    if (DEBUG) e.printStackTrace();
                }
            }
            if (DEBUG)
                System.out.println("Loaded " + count + " DoH server entries from resource");
        } catch (Exception e) {
            if (DEBUG) e.printStackTrace();
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }

    public static void main(String[] args) {
        Type type = Type.V4_ONLY;
        boolean error = false;
        boolean testall = false;
        boolean decode = false;
        boolean process = false;
        String url = null;
        Getopt g = new Getopt("dnsoverhttps", args, "46fstu:dp");
        try {
            int c;
            while ((c = g.getopt()) != -1) {
              switch (c) {
                case '4':
                    type = Type.V4_ONLY;
                    break;

                case '6':
                    type = Type.V6_ONLY;
                    break;

                case 'd':
                    if (decode || process || testall)
                        error = true;
                    else
                        decode = true;
                    break;

                case 'f':
                    type = Type.V4_PREFERRED;
                    break;

                case 'p':
                    if (decode || process || testall)
                        error = true;
                    else
                        process = true;
                    break;

                case 's':
                    type = Type.V6_PREFERRED;
                    break;

                case 't':
                    if (url != null)
                        error = true;
                    else
                        testall = true;
                    break;

                case 'u':
                    if (testall || url != null)
                        error = true;
                    else
                        url = g.getOptarg();
                    break;

                case '?':
                case ':':
                default:
                    error = true;
                    break;
              }  // switch
            } // while
        } catch (RuntimeException e) {
            e.printStackTrace();
            error = true;
        }
        if (error || args.length - g.getOptind() != 1) {
            usage();
            System.exit(1);
        }

        String hostname = args[g.getOptind()];
        if (testall) {
            List<String> totest;
            if (type == Type.V4_PREFERRED || type == Type.V4_ONLY) {
                type = Type.V4_ONLY;
                totest = v4urls;
            } else {
                type = Type.V6_ONLY;
                totest = v6urls;
            }
            Collections.sort(totest);
            DNSOverHTTPS doh = new DNSOverHTTPS(I2PAppContext.getGlobalContext());
            System.out.println("Testing " + totest.size() + " servers");
            int pass = 0, fail = 0;
            for (String test : totest) {
                String result = doh.lookup(hostname, type, test);
                if (result != null) {
                    pass++;
                    System.out.println(type + " lookup from " + test + " for " + hostname + " is " + result);
                } else {
                    fail++;
                    System.err.println(type + " lookup from " + test + " failed for " + hostname);
                }
                clearCaches();
            }
            System.out.println("Test complete: " + pass + " pass, " + fail + " fail");
        } else if (decode) {
            decodeStamp(hostname, true);
        } else if (process) {
            try {
                decodeStamps(hostname);
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        } else {
            String result = (new DNSOverHTTPS(I2PAppContext.getGlobalContext())).lookup(hostname, type, url);
            if (result != null)
                System.out.println(type + " lookup for " + hostname + " is " + result);
            else
                System.err.println(type + " lookup failed for " + hostname);
        }
    }
    
    private static void usage() {
        System.err.println("DNSOverHTTPS [-fstu46] hostname\n" +
                           "             [-f] (IPv4 preferred)\n" +
                           "             [-s] (IPv6 preferred)\n" +
                           "             [-t] (test all servers)\n" +
                           "             [-u 'https://host/dns-query?...&'] (request from this URL only)\n" +
                           "             [-4] (IPv4 only) (default)\n" +
                           "             [-6] (IPv6 only)\n" +
                           "DNSOverHTTPS -d sdns://... (decode server stamp)\n" +
                           "DNSOverHTTPS -p doh-resolvers.md (decode all server stamps in file)");
    }

    /**
     *  Decode sdns:// stamps
     *  e.g. sdns://AgMAAAAAAAAADjE2My40Ny4xMTcuMTc2oMwQYNOcgym2K2-8fQ1t-TCYabmB5-Y5LVzY-kCPTYDmIEROvWe7g_iAezkh6TiskXi4gr1QqtsRIx8ETPXwjffOEGFkbC5hZGZpbHRlci5uZXQKL2Rucy1xdWVyeQ
     *  Ref: https://dnscrypt.info/stamps-specifications/
     *
     *  @return the URL, or null on error or if not a DoH (type 2) stamp
     *  @since 0.9.62
     */
    private static String decodeStamp(String sdns, boolean log) {
        byte[] d = null;
        try {
            if (!sdns.startsWith("sdns://")) {
                if (log) System.out.println("Must start with sdns://");
                return null;
            }
            sdns = sdns.substring(7);
            sdns = sdns.replace("_", "~");
            d = Base64.decode(sdns);
            if (d == null) {
                if (log) System.out.println("Bad encoding");
                return null;
            }
            int type = d[0] & 0xff;
            // little endian, ignore last 7 bytes
            int props = d[1] & 0xff;
            int len = d[9] & 0xff;
            String addr = "n/a";
            if (len > 0) {
                try {
                    addr = new String(d, 10, len, "ISO-8859-1");
                } catch (IOException ioe) {}
            }
            String host = "";
            String path = "/";
            if (type == 0x02) {
                int off = 10 + len;
                int vlen = d[off++] & 0xff;
                // skip VLP of hashes
                while ((vlen & 0x80) != 0) {
                    off += vlen & 0x7f;
                    vlen = d[off++] & 0xff;
                }
                off += vlen;
                len = d[off++] & 0xff;
                if (len > 0) {
                    try {
                        host = new String(d, off, len, "ISO-8859-1");
                    } catch (IOException ioe) {}
                    off += len;
                }
                len = d[off++] & 0xff;
                if (len > 0) {
                    try {
                        path = new String(d, off, len, "ISO-8859-1");
                    } catch (IOException ioe) {}
                    off += len;
                }
            }
            String url = (type == 2 && host.length() > 0) ? "https://" + host + path : null;
            if (log) {
                if (url != null)
                    System.out.print(url + ' ');
                if (type == 1)
                    System.out.print("DNSCrypt");
                else if (type == 2)
                    System.out.print("DoH");
                else if (type == 3)
                    System.out.print("DNSoverTLS");
                else if (type == 4)
                    System.out.print("DNSoverQUIC");
                else if (type == 5)
                    System.out.print("oDoH");
                else if (type == 0x81)
                    System.out.print("DNSCrypt-relay");
                else if (type == 0x85)
                    System.out.print("oDoH-relay");
                else
                    System.out.print("unknown-" + type);
                System.out.println(" logs? " + ((props & 0x01) == 0) +
                                   " filters? " + ((props & 0x02) == 0) +
                                   " IP: " + addr);
            }
            return url;
        } catch (IndexOutOfBoundsException ioobe) {
            if (log) {
                System.out.println("Failed: " + ioobe);
                //ioobe.printStackTrace();
                System.out.println(HexDump.dump(d));
            }
            return null;
        }
    }

    /**
     *  Decode sdns:// stamps found in file
     *
     *  @return the URL, or null on error or if not a DoH (type 2) stamp
     *  @since 0.9.62
     */
    private static void decodeStamps(String file) throws IOException {
        BufferedReader in = null;
        try {
            FileInputStream fis = new FileInputStream(file);
            in = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            String line = null;
            while ( (line = in.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("sdns://"))
                    continue;
                decodeStamp(line, true);
            }
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }
}
