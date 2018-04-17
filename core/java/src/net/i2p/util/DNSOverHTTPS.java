package net.i2p.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import gnu.getopt.Getopt;

import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

/**
 *  Simple implemetation of DNS over HTTPS.
 *  Also sets the local clock from the received date header.
 *
 *  https://developers.google.com/speed/public-dns/docs/dns-over-https
 *  https://developers.cloudflare.com/1.1.1.1/dns-over-https/json-format/
 *
 *  @since 0.9.35
 */
public class DNSOverHTTPS implements EepGet.StatusListener {
    private final I2PAppContext ctx;
    private final Log _log;
    private final JSONParser parser;
    private final ByteArrayOutputStream baos;
    private SSLEepGet.SSLState state;
    private long fetchStart;
    private int gotDate;

    private static final Map<String, Result> v4Cache = new LHMCache<String, Result>(32);
    private static final Map<String, Result> v6Cache = new LHMCache<String, Result>(32);
    // v4 URLs to query, ending with '&'
    private static final List<String> v4urls = new ArrayList<String>(4);
    // v6 URLs to query, ending with '&'
    private static final List<String> v6urls = new ArrayList<String>(4);
    // consecutive failures
    private static final ObjectCounter<String> fails = new ObjectCounter<String>();

    // Don't look up any of these TLDs
    // RFC 2606, 6303, 7393
    // https://www.iana.org/assignments/locally-served-dns-zones/locally-served-dns-zones.xhtml
    private static final List<String> locals = Arrays.asList(new String[] {
        "localhost",
        "in-addr.arpa", "ip6.arpa", "home.arpa",
        "i2p", "onion",
        "i2p.arpa", "onion.arpa",
        "corp", "home", "internal", "intranet", "lan", "local", "private",
        "test", "example", "invalid",
        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
        "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z"
    } );

    static {
        // Warning: All hostnames MUST be in loop check in lookup() below
        // Google
        // Certs for 8.8.8.8 and 8.8.4.4 don't work
        v4urls.add("https://dns.google.com/resolve?edns_client_subnet=0.0.0.0/0&");
        v6urls.add("https://dns.google.com/resolve?edns_client_subnet=0.0.0.0/0&");
        // Cloudflare cloudflare-dns.com
        // https://developers.cloudflare.com/1.1.1.1/nitty-gritty-details/
        // 1.1.1.1 is a privacy centric resolver so it does not send any client IP information
        // and does not send the EDNS Client Subnet Header to authoritative servers
        v4urls.add("https://1.1.1.1/dns-query?ct=application/dns-json&");
        v4urls.add("https://1.0.0.1/dns-query?ct=application/dns-json&");
        v6urls.add("https://[2606:4700:4700::1111]/dns-query?ct=application/dns-json&");
        v6urls.add("https://[2606:4700:4700::1001]/dns-query?ct=application/dns-json&");
    }

    // keep the timeout very short, as we try multiple addresses,
    // and will be falling back to regular DNS.
    private static final long TIMEOUT = 3*1000;
    private static final int MAX_TTL = 24*60*60;
    // don't use a URL after this many consecutive failures
    private static final int MAX_FAILS = 3;
    private static final int V4_CODE = 1;
    private static final int CNAME_CODE = 5;
    private static final int V6_CODE = 28;
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
        parser = new JSONParser(JSONParser.MODE_PERMISSIVE);
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
     *  V4_ONLY
     *  @return null if not found
     */
    public String lookup(String host) {
        return lookup(host, Type.V4_ONLY);
    }

    /**
     *  Lookup in cache, then query servers
     *  @return null if not found
     */
    public String lookup(String host, Type type) {
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
        if (host.equals("dns.google.com"))
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
        return query(host, type);
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
     *  @return null if not found
     */
    private String query(String host, Type type) {
        List<String> toQuery = new ArrayList<String>((type == Type.V6_ONLY) ? v6urls : v4urls);
        Collections.shuffle(toQuery);
        if (type == Type.V4_ONLY || type == Type.V4_PREFERRED) {
            // v4 query
            String rv = query(host, false, toQuery);
            if (rv != null)
                return rv;
        }
        if (type != Type.V4_ONLY) {
            // v6 query
            String rv = query(host, true, toQuery);
            if (rv != null)
                return rv;
        }
        if (type == Type.V6_PREFERRED) {
            // v4 query after v6 query
            String rv = query(host, false, toQuery);
            if (rv != null)
                return rv;
        }
        return null;
    }

    /**
     *  @return null if not found
     */
    private String query(String host, boolean isv6, List<String> toQuery) {
        for (String url : toQuery) {
            if (fails.count(url) > MAX_FAILS)
                continue;
            int tcode = isv6 ? V6_CODE : V4_CODE;
            String furl = url + "name=" + host + "&type=" + tcode;
            log("Fetching " + furl);
            baos.reset();
            SSLEepGet eepget = new SSLEepGet(ctx, baos, furl, state);
            if (ctx.isRouterContext())
                eepget.addStatusListener(this);
            else
                fetchStart = System.currentTimeMillis();  // debug
            String rv = fetch(eepget, host, isv6);
            if (rv != null) {
                fails.clear(url);
                return rv;
            }
            if (state == null)
                state = eepget.getSSLState();
            // we treat all fails the same, whether server responded or not
            fails.increment(url);
            log("No result from " + furl);
        }
        return null;
    }

    /**
     *  @return null if not found
     */
    private String fetch(SSLEepGet eepget, String host, boolean isv6) {
        if (eepget.fetch(TIMEOUT, TIMEOUT, TIMEOUT) &&
            eepget.getStatusCode() == 200 && baos.size() > 0) {
            long end = System.currentTimeMillis();
            log("Got response in " + (end - fetchStart) + "ms");
            byte[] b = baos.toByteArray();
            try {
                JSONObject map = (JSONObject) parser.parse(b);
                if (map == null) {
                    log("No map");
                    return null;
                }
                Integer status = (Integer) map.get("Status");
                if (status == null || status.intValue() != 0) {
                    log("Bad status: " + status);
                    return null;
                }
                JSONArray list = (JSONArray) map.get("Answer");
                if (list == null || list.isEmpty()) {
                    log("No answer");
                    return null;
                }
                log(list.size() + " answers");
                String hostAnswer = host + '.';
                for (Object o : list) {
                    try {
                        JSONObject a = (JSONObject) o;
                        String data = (String) a.get("data");
                        if (data == null) {
                            log("no data");
                            continue;
                        }
                        Integer typ = (Integer) a.get("type");
                        if (typ == null)
                            continue;
                        String name = (String) a.get("name");
                        if (name == null)
                            continue;
                        if (typ.intValue() == CNAME_CODE) {
                            log("CNAME is: " + data);
                            hostAnswer = data;
                            continue;
                        }
                        if (isv6) {
                            if (typ.intValue() != V6_CODE) {
                                log("type mismatch: " + typ);
                                continue;
                            }
                            if (!Addresses.isIPv6Address(data)) {
                                log("bad addr: " + data);
                                continue;
                            }
                        } else {
                            if (typ.intValue() != V4_CODE) {
                                log("type mismatch: " + typ);
                                continue;
                            }
                            if (!Addresses.isIPv4Address(data)) {
                                log("bad addr: " + data);
                                continue;
                            }
                        }
                        if (!hostAnswer.equals(name)) {
                            log("name mismatch: " + name);
                            continue;
                        }
                        Integer ttl = (Integer) a.get("TTL");
                        int ittl = (ttl != null) ? Math.min(ttl.intValue(), MAX_TTL) : 3600;
                        long expires = end + (ittl * 1000L);
                        Map<String, Result> cache = isv6 ? v6Cache : v4Cache;
                        synchronized(cache) {
                            cache.put(host, new Result(data, expires));
                        }
                        log("Got answer: " + name + ' ' + typ + ' ' + ttl + ' ' + data + " in " + (end - fetchStart) + "ms");
                        return data;
                    } catch (Exception e) {
                        log("Fail parsing", e);
                    }
                }
            } catch (Exception e) {
                log("Fail parsing", e);
            }
            log("Bad response:\n" + new String(b));
        } else {
            log("Fail fetching");
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
        if (gotDate < MAX_DATE_SETS && "Date".equals(key)) {
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

    public static void main(String[] args) {
        Type type = Type.V4_PREFERRED;
        boolean error = false;
        Getopt g = new Getopt("dnsoverhttps", args, "46fs");
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

                case 'f':
                    type = Type.V4_PREFERRED;
                    break;

                case 's':
                    type = Type.V6_PREFERRED;
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

        String url = args[g.getOptind()];
        String result = (new DNSOverHTTPS(I2PAppContext.getGlobalContext())).lookup(url, type);
        if (result != null)
            System.out.println(type + " lookup for " + url + " is " + result);
        else
            System.err.println(type + " lookup failed for " + url);
    }
    
    private static void usage() {
        System.err.println("DNSOverHTTPS [-fs46] hostname\n" +
                           "             [-f] (IPv4 preferred) (default)\n" +
                           "             [-s] (IPv6 preferred)\n" +
                           "             [-4] (IPv4 only)\n" +
                           "             [-6] (IPv6 only)");
    }
}
