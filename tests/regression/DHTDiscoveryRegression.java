package org.klomp.snark.dht;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.crypto.SigType;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

import org.klomp.snark.SnarkManager;
import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEncoder;
import org.klomp.snark.bencode.BEValue;

/** Real KRPC messages over a controlled in-memory I2PSession; no network sockets. */
public final class DHTDiscoveryRegression {
    private static int checks;

    public static void main(String[] args) throws Throwable {
        immediateRepliesAndPersistence();
        parallelDiscovery();
        iterativeDiscovery();
        deadlinesAndCancellation();
        boundedQueueAndStop();
        System.out.println("DHT_DISCOVERY_CHECKS=" + checks);
    }

    private static void immediateRepliesAndPersistence() throws Throwable {
        try (Harness h = new Harness()) {
            for (int i = 0; i < 3; i++) h.node(true);
            h.announces = new CountDownLatch(3);
            AtomicReference<Collection<Hash>> result = new AtomicReference<>();
            Future<?> future = h.krpc.getPeersAndAnnounceAsync(new byte[20], 3, 2000, 3, 0,
                    false, false, result::set);
            future.get(3, TimeUnit.SECONDS);
            check(result.get() != null && result.get().size() == 3, "immediate replies are retained");
            check(h.announces.await(2, TimeUnit.SECONDS), "zero-wait announce reaches all three responders");
            check(((Map<?, ?>) field(h.krpc, "_incomingTokens")).size() == 3,
                    "info hashes are registered before token replies arrive");
            check(h.krpc.size() == 3, "responders enter known DHT nodes");
            h.krpc.stop();
            Path saved = h.work.resolve("discovery.config" + SnarkManager.CONFIG_DIR_SUFFIX)
                    .resolve("i2psnark" + KRPC.DHT_FILE_SUFFIX);
            check(Files.isRegularFile(saved), "known DHT nodes are saved");
            h.krpc = new KRPC(h.context, "discovery", h.session);
            List<NodeInfo> loaded = h.krpc.findClosest(new byte[20], 8);
            check(loaded.size() == 3, "saved DHT nodes are reused on restart");
            for (NodeInfo node : loaded)
                check(node.getDestination() != null && node.getDestination().calculateHash().equals(node.getHash()),
                        "saved destination matches its node hash");
        }
    }

    private static void parallelDiscovery() throws Throwable {
        try (Harness h = new Harness()) {
            for (int i = 0; i < 3; i++) h.node(false);
            h.lookupGate = new CountDownLatch(1);
            h.lookupEntered = new CountDownLatch(3);
            AtomicReference<Collection<Hash>> result = new AtomicReference<>();
            long started = System.nanoTime();
            Future<?> future = h.krpc.getPeersAndAnnounceAsync(new byte[20], 3, 2000, 0, 0,
                    false, true, result::set);
            check(h.lookupEntered.await(1, TimeUnit.SECONDS), "three destination lookups run concurrently");
            check(!future.isDone(), "caller returns while destination lookups are outstanding");
            check(h.peakLookups.get() == 3, "destination lookup concurrency is bounded at three");
            h.lookupGate.countDown();
            future.get(3, TimeUnit.SECONDS);
            check(result.get().size() == 3, "peers from multiple DHT replies are combined");
            check(h.noSeeds.get() == 3, "seed filtering is sent on every query");
            System.out.println("CONTROLLED_DHT_LOOKUP_MS=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
        try (Harness h = new Harness()) {
            List<NodeInfo> nodes = new ArrayList<>();
            for (int i = 0; i < 3; i++) nodes.add(h.node(true));
            nodes.sort(new NodeInfoComparator(new InfoHash(new byte[20])));
            h.silent = nodes.get(0).getHash();
            long started = System.nanoTime();
            Collection<Hash> peers = h.krpc.getPeersAndAnnounce(new byte[20], 2, 2000, 0, 0, false, false);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            check(peers.size() == 2 && elapsed < 1500, "unresponsive closest node does not delay other responders");
            check(((Map<?, ?>) field(h.krpc, "_sentQueries")).isEmpty(), "surplus queries are abandoned");
        }
    }

    private static void iterativeDiscovery() throws Throwable {
        try (Harness h = new Harness()) {
            NodeInfo initial = h.node(true, false);
            NodeInfo closer = h.node(false, false);
            h.krpc.heardAbout(initial);
            h.referrals.put(initial.getHash(), List.of(closer));
            Hash first = hash(1);
            Hash second = hash(2);
            h.values.put(closer.getHash(), List.of(h.session.getMyDestination().calculateHash().getData(),
                    first.getData(), first.getData(), second.getData()));
            Collection<Hash> peers = h.krpc.getPeersAndAnnounce(closer.getNID().getData(), 2, 2000, 0, 0,
                    false, false);
            check(peers.size() == 2 && peers.contains(first) && peers.contains(second),
                    "iterative lookup filters self and duplicate peers");
            check(h.sent.get() == 2, "closer node referral is queried once");
            List<NodeInfo> known = h.krpc.findClosest(closer.getNID().getData(), 8);
            check(known.size() == 2 && known.get(0).equals(closer), "discovered nodes join routing table");
            check(known.get(0).getDestination() != null, "discovered node destination is retained");
        }
    }

    private static void deadlinesAndCancellation() throws Throwable {
        try (Harness h = new Harness()) {
            h.node(false);
            h.lookupGate = new CountDownLatch(1);
            h.lookupEntered = new CountDownLatch(1);
            long started = System.nanoTime();
            Collection<Hash> peers = h.krpc.getPeersAndAnnounce(new byte[20], 2, 100, 0, 0, false, false);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            check(peers.isEmpty() && elapsed < 1000, "lookup deadline includes destination resolution");
            check(h.sent.get() == 0, "blocked destination lookup sends no query");
            check(((Map<?, ?>) field(h.krpc, "_sentQueries")).isEmpty(), "deadline removes pending transaction");
            h.lookupGate.countDown();
        }
        try (Harness h = new Harness()) {
            h.node(false);
            h.lookupGate = new CountDownLatch(1);
            h.lookupEntered = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            Future<?> future = h.krpc.getPeersAndAnnounceAsync(new byte[20], 2, 5000, 0, 0,
                    false, false, peers -> callbacks.incrementAndGet());
            check(h.lookupEntered.await(1, TimeUnit.SECONDS), "cancellable lookup has started");
            check(future.cancel(true), "active discovery can be cancelled");
            await(() -> ((Map<?, ?>) field(h.krpc, "_sentQueries")).isEmpty(), "cancel removes pending transactions");
            h.lookupGate.countDown();
            await(() -> h.activeLookups.get() == 0, "cancelled destination worker exits");
            check(callbacks.get() == 0 && h.sent.get() == 0, "cancelled lookup delivers no peers or late query");
        }
    }

    private static void boundedQueueAndStop() throws Throwable {
        try (Harness h = new Harness()) {
            h.node(false);
            h.lookupGate = new CountDownLatch(1);
            h.lookupEntered = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            AtomicInteger callbacks = new AtomicInteger();
            boolean rejected = false;
            for (int i = 0; i < 37; i++) {
                try {
                    byte[] infoHash = new byte[20];
                    infoHash[19] = (byte) i;
                    tasks.add(h.krpc.getPeersAndAnnounceAsync(infoHash, 2, 5000, 0, 0,
                            false, false, peers -> callbacks.incrementAndGet()));
                } catch (RejectedExecutionException expected) {
                    rejected = true;
                }
            }
            check(rejected && tasks.size() == 36, "search executor bounds running and queued work");
            check(h.lookupEntered.await(1, TimeUnit.SECONDS), "search workers are active before stop");
            h.krpc.stop();
            await(() -> tasks.stream().allMatch(Future::isDone), "stop finishes or cancels every search future");
            check(tasks.stream().anyMatch(Future::isCancelled), "queued futures are explicitly cancelled");
            check(callbacks.get() == 0, "stop suppresses completion callbacks");
            check(((Map<?, ?>) field(h.krpc, "_sentQueries")).isEmpty(), "stop clears all pending queries");
            try {
                h.krpc.getPeersAndAnnounceAsync(new byte[20], 1, 100, 0, 0, false, false, peers -> {});
                throw new AssertionError("stopped DHT accepted work");
            } catch (RejectedExecutionException expected) {
                checks++;
            }
        }
    }

    private static final class Harness implements AutoCloseable {
        final Path work = Files.createTempDirectory("dht-regression-");
        final I2PAppContext context;
        final I2PSession session;
        final Map<Hash, NodeInfo> nodes = new ConcurrentHashMap<>();
        final Map<Integer, byte[]> incoming = new ConcurrentHashMap<>();
        final Map<Hash, List<NodeInfo>> referrals = new ConcurrentHashMap<>();
        final Map<Hash, List<byte[]>> values = new ConcurrentHashMap<>();
        final AtomicInteger sent = new AtomicInteger();
        final AtomicInteger messageIDs = new AtomicInteger();
        final AtomicInteger activeLookups = new AtomicInteger();
        final AtomicInteger peakLookups = new AtomicInteger();
        final AtomicInteger noSeeds = new AtomicInteger();
        volatile CountDownLatch lookupGate;
        volatile CountDownLatch lookupEntered;
        volatile CountDownLatch announces;
        volatile Hash silent;
        KRPC krpc;

        Harness() throws Exception {
            Properties properties = new Properties();
            properties.setProperty("i2p.dir.config", work.toString());
            properties.setProperty("i2p.dir.base", work.toString());
            context = new TestContext(properties);
            context.logManager().setDefaultLimit("CRIT");
            Files.createDirectories(work.resolve("discovery.config" + SnarkManager.CONFIG_DIR_SUFFIX));
            I2PSession keys = keys();
            session = (I2PSession) Proxy.newProxyInstance(I2PSession.class.getClassLoader(),
                    new Class<?>[] {I2PSession.class}, (proxy, method, arguments) -> {
                        String name = method.getName();
                        if (name.equals("isClosed") || name.equals("isOffline")) return false;
                        if (name.equals("addMuxedSessionListener") || name.equals("removeListener")) return null;
                        if (name.equals("receiveMessage")) return incoming.remove((Integer) arguments[0]);
                        if (name.equals("lookupDest")) {
                            int active = activeLookups.incrementAndGet();
                            peakLookups.accumulateAndGet(active, Math::max);
                            try {
                                if (lookupEntered != null) lookupEntered.countDown();
                                if (lookupGate != null) lookupGate.await();
                                NodeInfo node = nodes.get(arguments[0]);
                                return node == null ? null : node.getDestination();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return null;
                            } finally {
                                activeLookups.decrementAndGet();
                            }
                        }
                        if (name.equals("sendMessage")) {
                            send(arguments);
                            return true;
                        }
                        return method.invoke(keys, arguments);
                    });
            krpc = new KRPC(context, "discovery", session);
        }

        NodeInfo node(boolean resolved) throws Exception {
            return node(resolved, true);
        }

        NodeInfo node(boolean resolved, boolean remember) throws Exception {
            Destination destination = keys().getMyDestination();
            int port = 7000 + nodes.size() * 2;
            NID nid = NodeInfo.generateNID(destination.calculateHash(), port, context.random());
            NodeInfo full = new NodeInfo(nid, destination, port);
            nodes.put(full.getHash(), full);
            NodeInfo candidate = resolved ? full : new NodeInfo(nid, full.getHash(), port);
            if (remember) krpc.heardAbout(candidate);
            return candidate;
        }

        private void send(Object[] arguments) throws Throwable {
            NodeInfo node = nodes.get(((Destination) arguments[0]).calculateHash());
            byte[] payload = Arrays.copyOfRange((byte[]) arguments[1], (Integer) arguments[2],
                    (Integer) arguments[2] + (Integer) arguments[3]);
            if ((Integer) arguments[4] == I2PSession.PROTO_DATAGRAM) {
                I2PDatagramDissector datagram = new I2PDatagramDissector();
                datagram.loadI2PDatagram(payload);
                payload = datagram.getPayload();
            }
            Map<String, BEValue> query = new BDecoder(new ByteArrayInputStream(payload)).bdecodeMap().getMap();
            if ("announce_peer".equals(query.get("q").getString())) {
                if (announces != null) announces.countDown();
            } else if ("get_peers".equals(query.get("q").getString())) {
                sent.incrementAndGet();
                if (query.get("a").getMap().containsKey("noseed")) noSeeds.incrementAndGet();
            } else {
                return;
            }
            if (node.getHash().equals(silent)) return;
            Map<String, Object> response = new HashMap<>();
            response.put("id", node.getNID().getData());
            if ("get_peers".equals(query.get("q").getString())) {
                response.put("token", new byte[] {1, 2, 3, 4});
                if (referrals.containsKey(node.getHash())) {
                    ByteArrayOutputStream compact = new ByteArrayOutputStream();
                    for (NodeInfo referred : referrals.get(node.getHash())) compact.write(referred.getData());
                    response.put("nodes", compact.toByteArray());
                } else {
                    response.put("values", values.getOrDefault(node.getHash(), Collections.singletonList(node.getHash().getData())));
                }
            }
            Map<String, Object> reply = new HashMap<>();
            reply.put("y", "r");
            reply.put("t", query.get("t").getBytes());
            reply.put("r", response);
            byte[] encoded = BEncoder.bencode(reply);
            int id = messageIDs.incrementAndGet();
            incoming.put(id, encoded);
            // Deliberately deliver inline, before sendMessage returns.
            krpc.messageAvailable(session, id, encoded.length, I2PSession.PROTO_DATAGRAM_RAW,
                    node.getPort() + 1, krpc.getRPort());
        }

        public void close() {
            krpc.stop();
            if (lookupGate != null) lookupGate.countDown();
            context.logManager().shutdown();
        }
    }

    private static final class TestContext extends I2PAppContext {
        TestContext(Properties properties) { super(false, properties); }
    }

    private static I2PSession keys() throws Exception {
        I2PClient client = I2PClientFactory.createClient();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        client.createDestination(encoded, SigType.EdDSA_SHA512_Ed25519);
        return client.createSession(new ByteArrayInputStream(encoded.toByteArray()), new Properties());
    }

    private static Hash hash(int value) {
        byte[] bytes = new byte[32];
        bytes[31] = (byte) value;
        return new Hash(bytes);
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private interface Condition { boolean ready() throws Exception; }

    private static void await(Condition condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.ready() && System.nanoTime() < deadline) Thread.sleep(5);
        check(condition.ready(), message);
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        checks++;
    }
}
