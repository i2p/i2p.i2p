package net.i2p.router.transport.udp;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.transport.Transport;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.stat.RateStat;

/**
 * Finite live-network smoke run, with a separate router identity and data directory.
 * Compile this against the jars being tested; it is deliberately outside the unit suite.
 */
public final class LiveRouterSmoke {
    private static final int SAMPLE_SECONDS = 15;
    private static final int MAX_INTRO_PROBES = 12;
    private static final String[] STATS = {
        "tunnel.buildExploratorySuccess", "tunnel.buildExploratoryReject", "tunnel.buildExploratoryExpire",
        "tunnel.buildClientSuccess", "tunnel.buildClientReject", "tunnel.buildClientExpire",
        "tunnel.testSuccessLength", "tunnel.testSuccessTime", "tunnel.testExploratoryFailedTime",
        "tunnel.testExploratoryFailedCompletelyTime", "tunnel.testAborted", "tunnel.buildFailFirstHop",
        "udp.outboundEstablishTime", "udp.inboundEstablishTime", "udp.sendIntroRelayTimeout",
        "udp.relayBadIP", "udp.establishBadIP", "udp.establishRejected", "udp.establishOverflow"
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 5)
            throw new IllegalArgumentException("Usage: LiveRouterSmoke REPO RUNTIME [SECONDS=720] [PORT=24741] [normal|firewalled|ssu2-only|ntcp2-only]");
        Path repo = Path.of(args[0]).toAbsolutePath();
        Path runtime = Path.of(args[1]).toAbsolutePath();
        int duration = args.length > 2 ? Integer.parseInt(args[2]) : 720;
        int port = args.length > 3 ? Integer.parseInt(args[3]) : 24741;
        String mode = args.length > 4 ? args[4] : "normal";
        if (duration < 30 || duration > 3600 || port < 1024 || port > 65535 ||
            !(mode.equals("normal") || mode.equals("firewalled") || mode.equals("ssu2-only") || mode.equals("ntcp2-only")))
            throw new IllegalArgumentException("Invalid duration, port or mode");
        prepare(repo, runtime, port, mode);
        System.setProperty("I2P_DISABLE_OUTPUT_OVERRIDE", "true");
        Properties env = new Properties();
        for (String kind : new String[] { "base", "config", "router", "app", "log", "pid" })
            env.setProperty("i2p.dir." + kind, runtime.toString());
        env.setProperty("i2p.dir.temp", runtime.resolve("tmp").toString());
        Router router = new Router(runtime.resolve("router.config").toString(), env);
        router.setKillVMOnEnd(false);
        List<Probe> probes = new ArrayList<Probe>();
        Set<Hash> attempted = new HashSet<Hash>();
        long started = System.nanoTime();
        try (PrintWriter samples = new PrintWriter(Files.newBufferedWriter(runtime.resolve("metrics.jsonl")))) {
            Map<String, Object> meta = new LinkedHashMap<String, Object>();
            meta.put("event", "start");
            meta.put("at", Instant.now().toString());
            meta.put("mode", mode);
            meta.put("durationSeconds", duration);
            meta.put("udpPort", port);
            meta.put("routerArtifact", artifact(Router.class));
            meta.put("coreArtifact", artifact(I2PAppContext.class));
            emit(samples, meta);
            router.runRouter();
            RouterContext context = router.getContext();
            while ((System.nanoTime() - started) / 1_000_000_000L <= duration &&
                   !Files.exists(runtime.resolve("STOP"))) {
                long elapsed = (System.nanoTime() - started) / 1_000_000_000L;
                UDPTransport udp = udp(context);
                if (elapsed >= 60 && udp != null && probes.size() < MAX_INTRO_PROBES &&
                    context.commSystem().countActivePeers() >= 4 && context.netDb().getKnownRouters() >= 50)
                    probe(context, udp, probes, attempted);
                emit(samples, snapshot(context, elapsed, probes));
                Thread.sleep(SAMPLE_SECONDS * 1000L);
            }
            emit(samples, snapshot(context, (System.nanoTime() - started) / 1_000_000_000L, probes));
            Map<String, Object> ending = new LinkedHashMap<String, Object>();
            ending.put("event", "shutdown");
            ending.put("at", Instant.now().toString());
            ending.put("reason", Files.exists(runtime.resolve("STOP")) ? "stop-file" : "duration-complete");
            emit(samples, ending);
        } finally {
            // This is the orderly router shutdown path, including goodbye packets
            // and persistence. Sharing is disabled, so no participating tunnels
            // need to drain before this isolated test instance exits.
            router.shutdown(Router.EXIT_GRACEFUL);
        }
        System.exit(0);
    }

    private static void prepare(Path repo, Path runtime, int port, String mode) throws IOException {
        Path marker = runtime.resolve("LIVE-ROUTER-SMOKE");
        if (Files.exists(runtime) && !Files.exists(marker)) {
            try (var entries = Files.list(runtime)) {
                if (entries.findAny().isPresent())
                    throw new IOException("Refusing to reuse an unmarked, nonempty runtime directory: " + runtime);
            }
        }
        Files.createDirectories(runtime);
        Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(marker, "Dedicated finite live router test; contains a disposable router identity.\n");
        Files.createDirectories(runtime.resolve("tmp"));
        Files.writeString(runtime.resolve("hosts.txt"), "");
        Files.writeString(runtime.resolve("clients.config"), "");
        Path source = repo.resolve("installer/resources/certificates");
        try (var paths = Files.walk(source)) {
            for (Path file : (Iterable<Path>) paths::iterator) {
                Path target = runtime.resolve("certificates").resolve(source.relativize(file));
                if (Files.isDirectory(file))
                    Files.createDirectories(target);
                else
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Properties config = new Properties();
        Path configPath = runtime.resolve("router.config");
        if (Files.exists(configPath)) {
            try (var input = Files.newInputStream(configPath)) { config.load(input); }
        }
        config.setProperty("i2cp.disableInterface", "true");
        config.setProperty("i2np.upnp.enable", "false");
        config.setProperty("i2np.upnp.ipv6.enable", "false");
        config.setProperty("router.sharePercentage", "0");
        config.setProperty("router.maxParticipatingTunnels", "0");
        config.setProperty("router.floodfillParticipant", "false");
        config.setProperty("router.updateDisabled", "true");
        config.setProperty("i2np.bandwidth.inboundKBytesPerSecond", "96");
        config.setProperty("i2np.bandwidth.outboundKBytesPerSecond", "96");
        config.setProperty("i2np.bandwidth.inboundBurstKBytesPerSecond", "96");
        config.setProperty("i2np.bandwidth.outboundBurstKBytesPerSecond", "96");
        config.setProperty("i2np.udp.internalPort", Integer.toString(port));
        config.setProperty("i2np.udp.port", Integer.toString(port));
        config.setProperty("i2np.udp.enable", Boolean.toString(!mode.equals("ntcp2-only")));
        config.setProperty("i2np.ntcp.enable", Boolean.toString(!mode.equals("ssu2-only")));
        config.setProperty("i2np.udp.forceIntroducers", Boolean.toString(!mode.equals("normal")));
        config.setProperty("i2np.udp.allowDirect", Boolean.toString(mode.equals("normal")));
        config.setProperty("i2np.ntcp.autoip", mode.equals("normal") ? "true" : "false");
        config.setProperty("router.inboundPool.length", "2");
        config.setProperty("router.inboundPool.lengthVariance", "0");
        config.setProperty("router.inboundPool.quantity", "2");
        config.setProperty("router.inboundPool.backupQuantity", "0");
        config.setProperty("router.outboundPool.length", "2");
        config.setProperty("router.outboundPool.lengthVariance", "0");
        config.setProperty("router.outboundPool.quantity", "2");
        config.setProperty("router.outboundPool.backupQuantity", "0");
        config.setProperty("stat.full", "true");
        try (OutputStream output = Files.newOutputStream(configPath)) {
            config.store(output, "Dedicated bounded live router smoke test");
        }
        Properties logging = new Properties();
        logging.setProperty("logger.defaultLevel", "WARN");
        logging.setProperty("logger.displayOnScreen", "false");
        logging.setProperty("logger.logFileName", runtime.resolve("router-@.log").toString());
        logging.setProperty("logger.logFileSize", "8m");
        logging.setProperty("logger.logRotationLimit", "3");
        logging.setProperty("logger.gzip", "false");
        logging.setProperty("logger.record.net.i2p.router.transport.udp.EstablishmentManager", "INFO");
        logging.setProperty("logger.record.net.i2p.router.transport.udp.IntroductionManager", "INFO");
        try (OutputStream output = Files.newOutputStream(runtime.resolve("logger.config"))) {
            logging.store(output, "Introduction diagnostics retained in private runtime");
        }
    }

    private static Map<String, Object> snapshot(RouterContext context, long elapsed, List<Probe> probes) {
        Map<String, Object> sample = new LinkedHashMap<String, Object>();
        sample.put("event", "sample");
        sample.put("at", Instant.now().toString());
        sample.put("elapsedSeconds", elapsed);
        sample.put("routerAlive", context.router().isAlive());
        sample.put("status", context.commSystem().getStatus().name());
        sample.put("knownRouters", context.netDb().getKnownRouters());
        sample.put("activePeers", context.commSystem().countActivePeers());
        sample.put("establishedPeers", context.commSystem().getEstablished().size());
        sample.put("inboundExploratoryLengths", lengths(context.tunnelManager().getInboundExploratoryPool()));
        sample.put("outboundExploratoryLengths", lengths(context.tunnelManager().getOutboundExploratoryPool()));
        sample.put("participatingTunnels", context.tunnelManager().getParticipatingCount());
        sample.put("inboundBytes", context.bandwidthLimiter().getTotalAllocatedInboundBytes());
        sample.put("outboundBytes", context.bandwidthLimiter().getTotalAllocatedOutboundBytes());
        UDPTransport udp = udp(context);
        if (udp != null) {
            sample.put("udpPeerCounts", udp.getPeerCounts());
            int tags = 0;
            Map<String, Integer> versions = new LinkedHashMap<String, Integer>();
            for (PeerState peer : udp.getPeers()) {
                String version = Integer.toString(peer.getVersion());
                versions.put(version, versions.getOrDefault(version, 0) + 1);
                if (peer.getVersion() >= 2 && peer.getTheyRelayToUsAs() != 0)
                    tags++;
            }
            sample.put("udpNegotiatedVersions", versions);
            sample.put("availableIntroducerTags", tags);
        }
        int published = 0;
        RouterInfo local = context.router().getRouterInfo();
        if (local != null) {
            for (RouterAddress address : local.getAddresses())
                for (int index = 0; index < 3; index++)
                    if (address.getOption("itag" + index) != null)
                        published++;
        }
        sample.put("publishedIntroducers", published);
        List<Map<String, Object>> probeSamples = new ArrayList<Map<String, Object>>();
        for (Probe probe : probes) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("index", probe.index);
            result.put("messageSent", probe.sent.get());
            result.put("messageFailed", probe.failed.get());
            result.put("udpEstablished", udp != null && udp.getPeerState(probe.hash) != null);
            result.put("state", probe.state == null ? null : probe.state.getState().name());
            probeSamples.add(result);
        }
        sample.put("introducedPeerProbes", probeSamples);
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        for (String name : STATS) {
            RateStat stat = context.statManager().getRate(name);
            if (stat != null) {
                Map<String, Object> values = new LinkedHashMap<String, Object>();
                values.put("events", stat.getLifetimeEventCount());
                values.put("average", stat.getLifetimeAverageValue());
                stats.put(name, values);
            }
        }
        sample.put("stats", stats);
        return sample;
    }

    private static List<Integer> lengths(TunnelPool pool) {
        List<Integer> lengths = new ArrayList<Integer>();
        if (pool != null)
            for (TunnelInfo tunnel : pool.listTunnels())
                lengths.add(tunnel.getLength() - 1);
        Collections.sort(lengths);
        return lengths;
    }

    private static UDPTransport udp(RouterContext context) {
        for (Transport transport : context.commSystem().getTransports().values())
            if (transport instanceof UDPTransport)
                return (UDPTransport) transport;
        return null;
    }

    private static void probe(RouterContext context, UDPTransport udp, List<Probe> probes, Set<Hash> attempted) {
        List<RouterInfo> candidates = new ArrayList<RouterInfo>(context.netDb().getRouters());
        Collections.shuffle(candidates, context.random());
        for (RouterInfo info : candidates) {
            Hash hash = info.getHash();
            if (hash.equals(context.routerHash()) || attempted.contains(hash) ||
                context.banlist().isBanlisted(hash) || udp.getPeerState(hash) != null)
                continue;
            RouterAddress address = udp.getTargetAddress(info);
            if (address == null || address.getOption("itag0") == null)
                continue;
            UDPAddress target = new UDPAddress(address);
            boolean valid = false;
            for (int i = 0; i < target.getIntroducerCount(); i++)
                if (target.getIntroducerHash(i) != null && target.getIntroducerExpiration(i) > context.clock().now())
                    valid = true;
            if (!valid || udp.getSSUVersion(address) < 2)
                continue;
            attempted.add(hash);
            Probe probe = new Probe(probes.size() + 1, hash);
            probes.add(probe);
            DatabaseLookupMessage lookup = new DatabaseLookupMessage(context);
            lookup.setSearchKey(hash);
            lookup.setSearchType(DatabaseLookupMessage.Type.RI);
            lookup.setFrom(context.routerHash());
            lookup.setMessageExpiration(context.clock().now() + 30000);
            OutNetMessage message = new OutNetMessage(context, lookup, context.clock().now() + 30000,
                                                       OutNetMessage.PRIORITY_MY_NETDB_LOOKUP, info);
            message.setOnSendJob(new JobImpl(context) {
                public String getName() { return "Live probe send success"; }
                public void runJob() { probe.sent.set(true); }
            });
            message.setOnFailedSendJob(new JobImpl(context) {
                public String getName() { return "Live probe send failure"; }
                public void runJob() { probe.failed.set(true); }
            });
            udp.send(message);
            probe.state = udp.getEstablisher().getOutboundState(new RemoteHostId(hash));
            return;
        }
    }

    private static Map<String, Object> artifact(Class<?> type) throws Exception {
        Path path = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("path", path.toString());
        if (Files.isRegularFile(path)) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest)
                hex.append(String.format("%02x", value & 0xff));
            result.put("sha256", hex.toString());
        }
        return result;
    }

    private static void emit(PrintWriter writer, Map<String, Object> values) {
        String line = json(values);
        writer.println(line);
        writer.flush();
        System.out.println(line);
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<String>();
            for (Map.Entry<?, ?> entry : map.entrySet())
                entries.add(json(entry.getKey().toString()) + ":" + json(entry.getValue()));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable<?> values) {
            List<String> entries = new ArrayList<String>();
            for (Object entry : values) entries.add(json(entry));
            return "[" + String.join(",", entries) + "]";
        }
        if (value instanceof int[] values) {
            List<String> entries = new ArrayList<String>();
            for (int entry : values) entries.add(Integer.toString(entry));
            return "[" + String.join(",", entries) + "]";
        }
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private static final class Probe {
        final int index;
        final Hash hash;
        final AtomicBoolean sent = new AtomicBoolean();
        final AtomicBoolean failed = new AtomicBoolean();
        OutboundEstablishState state;
        Probe(int index, Hash hash) { this.index = index; this.hash = hash; }
    }
}
