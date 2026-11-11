package net.i2p.router.transport.udp;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import net.i2p.data.Hash;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.*;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.transport.Transport;

/** Isolated transport integration lab: real encrypted sessions, loopback packet faults. */
public final class TransportRecoveryLab {
    static final AtomicLong IDS = new AtomicLong();
    static final List<Process> CHILDREN = new CopyOnWriteArrayList<>();
    static final long DEADLINE_MS = 45000;
    static final long LOSS_WINDOW_MS = 3000;
    static class Node implements AutoCloseable {
        final Router router;
        final RouterContext context;
        final UDPTransport udp;
        final Set<Long> replies = ConcurrentHashMap.newKeySet();
        Node(Path path, int internal, int external, int tcp, boolean enableTCP) throws Exception {
            Files.createDirectories(path);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            Properties p = new Properties();
            for (String kind : new String[]{"base", "config", "router", "app", "log", "pid"})
                p.setProperty("i2p.dir." + kind, path.toString());
            p.setProperty("i2p.dir.temp", path.resolve("tmp").toString());
            p.setProperty("router.networkID", "222");
            p.setProperty("router.reseedDisable", "true");
            p.setProperty("i2np.allowLocal", "true");
            p.setProperty("i2np.upnp.enable", "false");
            p.setProperty("time.disabled", "true");
            p.setProperty("i2p.dummyClientFacade", "true");
            p.setProperty("i2p.dummyNetDb", "true");
            p.setProperty("i2p.dummyPeerManager", "true");
            p.setProperty("i2p.dummyTunnelManager", "true");
            p.setProperty("router.floodfillParticipant", "false");
            p.setProperty("router.updateDisabled", "true");
            p.setProperty("i2np.udp.host", "127.0.0.1");
            p.setProperty("i2np.udp.internalPort", "" + internal);
            p.setProperty("i2np.udp.port", "" + external);
            p.setProperty("i2np.udp.bindInterface", "127.0.0.1");
            p.setProperty("i2np.udp.ipv6", "false");
            p.setProperty("i2np.udp.mtu", "1500");
            p.setProperty("i2np.ntcp.enable", Boolean.toString(enableTCP));
            p.setProperty("i2np.ntcp.hostname", "127.0.0.1");
            p.setProperty("i2np.ntcp.bindInterface", "127.0.0.1");
            p.setProperty("i2np.ntcp.autoip", "false");
            p.setProperty("i2np.ntcp.autoport", "false");
            p.setProperty("i2np.ntcp.port", "" + tcp);
            p.setProperty("i2np.ntcp.ipv6", "false");
            p.setProperty("i2cp.disableInterface", "true");
            p.setProperty("i2p.naming.impl", "net.i2p.client.naming.HostsTxtNamingService");
            p.setProperty("router.maxParticipatingTunnels", "0");
            p.setProperty("router.sharePercentage", "0");
            p.setProperty("logger.defaultLevel", "WARN");
            Properties log = new Properties();
            log.setProperty("logger.defaultLevel", "WARN");
            log.setProperty("logger.displayOnScreen", "false");
            log.setProperty("logger.logFileName", path.resolve("router-@.log").toString());
            try (OutputStream out = Files.newOutputStream(path.resolve("logger.config"))) { log.store(out, "Transport lab"); }
            try (OutputStream out = Files.newOutputStream(path.resolve("router.config"))) { p.store(out, "Isolated network 222"); }
            router = new Router(path.resolve("router.config").toString(), p);
            router.setKillVMOnEnd(false);
            router.runRouter();
            context = router.getContext();
            await(() -> router.getRouterInfo() != null && router.getRouterInfo().getTargetAddress("SSU2") != null,
                  30000, "router transport startup");
            udp = (UDPTransport) ((CommSystemFacadeImpl)context.commSystem()).getTransports().values().stream()
                    .filter(t -> t instanceof UDPTransport).findFirst().orElseThrow();
            context.inNetMessagePool().registerHandlerJobBuilder(DataMessage.MESSAGE_TYPE, (message, from, hash) -> {
                DataMessage data = (DataMessage)message;
                String payload = new String(data.getData(), StandardCharsets.US_ASCII);
                if (!payload.startsWith("recovery:")) return null;
                String[] parts = payload.split(":", 5);
                long id = Long.parseLong(parts[2]);
                if (parts[1].equals("ack")) replies.add(id);
                else {
                    RouterInfo peer = context.netDb().lookupRouterInfoLocally(hash);
                    if (peer != null) send(peer, id, parts[3], true);
                }
                return null;
            });
        }
        void send(RouterInfo peer, long id, String mode, boolean ack) {
            DataMessage message = new DataMessage(context);
            message.setData(("recovery:" + (ack ? "ack:" : "request:") + id + ":" + mode + ":" + "x".repeat(1400))
                            .getBytes(StandardCharsets.US_ASCII));
            message.setMessageExpiration(context.clock().now() + DEADLINE_MS);
            OutNetMessage out = new OutNetMessage(context, message, context.clock().now() + DEADLINE_MS, 500, peer);
            if (mode.equals("UDP")) udp.send(out);
            else context.commSystem().processMessage(out);
        }
        long sendProbe(RouterInfo peer, String mode) {
            long id = IDS.incrementAndGet();
            send(peer, id, mode, false);
            return id;
        }
        long probe(RouterInfo peer, String mode, long deadline) throws Exception {
            return probeRoundTrip(() -> sendProbe(peer, mode), id -> replies.contains(id), deadline, 1000);
        }
        int states(String name) throws Exception {
            Field field = EstablishmentManager.class.getDeclaredField(name); field.setAccessible(true);
            return ((Map<?,?>)field.get(udp.getEstablisher())).size();
        }
        int peerIndexSize(String name) throws Exception {
            Field field = UDPTransport.class.getDeclaredField(name); field.setAccessible(true);
            Map<?,?> values = (Map<?,?>)field.get(udp);
            return values != null ? values.size() : 0;
        }
        void forget(Hash peer) { udp.dropPeer(peer, false, "controlled recovery test"); }
        @Override public void close() { router.shutdown(Router.EXIT_GRACEFUL); }
    }
    /** Two public sockets preserve peer identity while forwarding to private sockets. */
    static class Link implements AutoCloseable {
        final List<DatagramSocket> sockets = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new ArrayList<>();
        final int internalA, internalB;
        final DatagramSocket publicB;
        volatile DatagramSocket publicA;
        volatile boolean closed, blocked, staleMapping, loss, reorder;
        final AtomicLong dropped = new AtomicLong(), forwarded = new AtomicLong(), reordered = new AtomicLong();
        final AtomicLong blockedDrops = new AtomicLong(), expiredMappingDrops = new AtomicLong();
        final long seed;
        Link(int a, int b, long seed) throws Exception {
            internalA = a; internalB = b; this.seed = seed;
            publicA = socket(); publicB = socket();
            start(publicA, false); start(publicB, true);
        }
        DatagramSocket socket() throws Exception {
            DatagramSocket socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            socket.setSoTimeout(50); sockets.add(socket); return socket;
        }
        void rotate() throws Exception { publicA = socket(); start(publicA, false); }
        void start(DatagramSocket receiver, boolean aToB) {
            Thread t = new Thread(() -> {
                byte[] buffer = new byte[65536];
                byte[] held = null; long heldAt = 0, count = 0;
                while (!closed) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length); receiver.receive(packet);
                        if (!packet.getAddress().isLoopbackAddress() || packet.getPort() != (aToB ? internalA : internalB)) continue;
                        count++;
                        if (blocked) {
                            blockedDrops.incrementAndGet(); dropped.incrementAndGet(); continue;
                        }
                        if (!aToB && staleMapping && receiver != publicA) {
                            expiredMappingDrops.incrementAndGet(); dropped.incrementAndGet(); continue;
                        }
                        if (loss && Math.floorMod(count * 17 + seed, 5) == 0) {
                            dropped.incrementAndGet(); continue;
                        }
                        byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                        if (reorder && held == null && count % 3 == 0) { held = data; heldAt = System.nanoTime(); continue; }
                        forward(data, aToB);
                        if (held != null) { forward(held, aToB); held = null; reordered.incrementAndGet(); }
                    } catch (SocketTimeoutException timeout) {
                        if (held != null && System.nanoTime() - heldAt > 400000000L) {
                            try { forward(held, aToB); } catch (IOException ignored) {} held = null;
                        }
                    } catch (IOException e) { if (!closed) e.printStackTrace(System.err); }
                }
            }, "controlled-link");
            t.setDaemon(true); threads.add(t); t.start();
        }
        void forward(byte[] data, boolean aToB) throws IOException {
            DatagramSocket sender = aToB ? publicA : publicB;
            sender.send(new DatagramPacket(data, data.length, new InetSocketAddress("127.0.0.1", aToB ? internalB : internalA)));
            forwarded.incrementAndGet();
        }
        @Override public void close() {
            closed = true; for (DatagramSocket s : sockets) s.close();
            for (Thread t : threads) try { t.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    static int freePort() throws Exception { try (DatagramSocket s = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) { return s.getLocalPort(); } }
    static long probeRoundTrip(LongSupplier send, LongPredicate acknowledged, long timeoutMs, long intervalMs) throws Exception {
        long start = System.nanoTime(), end = start + TimeUnit.MILLISECONDS.toNanos(timeoutMs), next = start;
        List<Long> sent = new ArrayList<>();
        while (System.nanoTime() < end) {
            boolean answered = false;
            for (long id : sent)
                if (acknowledged.test(id)) {
                    answered = true;
                    break;
                }
            long now = System.nanoTime();
            if (now >= end) break;
            if (answered) return TimeUnit.NANOSECONDS.toMillis(now - start);
            if (now >= next) {
                // SSU2 permits individual I2NP messages to expire. A fresh
                // numbered request tests connection recovery after such loss.
                sent.add(send.getAsLong());
                next = now + TimeUnit.MILLISECONDS.toNanos(intervalMs);
            }
            Thread.sleep(5);
        }
        throw new TimeoutException("authenticated message round trip exceeded " + timeoutMs + " ms");
    }
    static void await(BooleanSupplier condition, long timeout, String label) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= end) throw new TimeoutException(label + " exceeded " + timeout + " ms");
            Thread.sleep(10);
        }
    }
    /** Each router runs in a separate JVM: global random/timer cleanup cannot affect its peer. */
    static final class RemoteNode implements AutoCloseable {
        final Process process;
        final PrintWriter input;
        final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        final Thread reader;
        final Path path;
        final String info;
        RemoteNode(Path path, int internal, int external, int tcp, boolean enableTCP) throws Exception {
            this.path = path;
            Files.createDirectories(path);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx256m", "-cp", System.getProperty("java.class.path"), TransportRecoveryLab.class.getName(),
                    "--node", path.toString(), ""+internal, ""+external, ""+tcp, Boolean.toString(enableTCP));
            builder.redirectErrorStream(true);
            process = builder.start();
            CHILDREN.add(process);
            input = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8);
            reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter log = new PrintWriter(Files.newBufferedWriter(path.resolve("node-process.log")))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.startsWith("LAB_REPLY ")) replies.add(line.substring(10));
                        else { log.println(line); log.flush(); }
                    }
                } catch (IOException e) { replies.add("ERROR " + e); }
                finally { replies.add("ERROR Node output closed; inspect private logs"); }
            }, "lab-node-output");
            reader.setDaemon(true); reader.start();
            try { info = reply(40000); }
            catch (Exception e) { process.destroyForcibly(); throw e; }
        }
        String reply(long timeout) throws Exception {
            String response = replies.poll(timeout, TimeUnit.MILLISECONDS);
            if (response == null) throw new IOException("Node timed out; inspect " + path.resolve("node-process.log"));
            if (response.startsWith("ERROR ")) throw new IOException(response);
            return response;
        }
        String call(String command) throws Exception { input.println(command); return reply(DEADLINE_MS + 15000); }
        void connect(RemoteNode peer) throws Exception { call("PEER " + peer.info); }
        long probe(String mode) throws Exception {
            String response = call("PROBE " + mode);
            if (response.startsWith("FAILED ")) throw new TimeoutException(response.substring(7));
            return Long.parseLong(response);
        }
        void send(String mode) throws Exception { Long.parseLong(call("SEND " + mode)); }
        long[] state() throws Exception {
            long[] values = Arrays.stream(call("STATE").split(",")).mapToLong(Long::parseLong).toArray();
            if (values.length != 12) throw new IOException("Malformed node state");
            return values;
        }
        void forget() throws Exception { call("FORGET"); }
        @Override public void close() throws Exception {
            try {
                if (!process.isAlive()) throw new IOException("Node exited prematurely: " + process.exitValue());
                String stopped = call("STOP");
                if (!stopped.equals("STOPPED")) throw new IOException("Shutdown failed: " + stopped);
                if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0)
                    throw new IOException("Node failed to exit after shutdown");
            } finally {
                if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
                input.close(); reader.join(1000); CHILDREN.remove(process);
            }
        }
    }
    static void reply(String value) { System.out.println("LAB_REPLY " + value); System.out.flush(); }
    static void node(String[] args) throws Exception {
        try (Node node = new Node(Path.of(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]), Boolean.parseBoolean(args[5]))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            node.router.getRouterInfo().writeBytes(bytes);
            reply(java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()));
            RouterInfo peer = null;
            BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = commands.readLine()) != null) {
                if (line.startsWith("PEER ")) {
                    peer = new RouterInfo();
                    peer.readBytes(new ByteArrayInputStream(java.util.Base64.getDecoder().decode(line.substring(5))));
                    if (peer.getNetworkId() != 222) throw new IOException("Wrong lab network");
                    for (net.i2p.data.router.RouterAddress address : peer.getAddresses())
                        if (address.getIP() != null && !InetAddress.getByAddress(address.getIP()).isLoopbackAddress())
                            throw new IOException("Non-loopback peer forbidden");
                    node.context.netDb().store(peer.getHash(), peer); reply("OK");
                } else if (line.startsWith("PROBE ")) {
                    try { reply("" + node.probe(peer, line.substring(6), DEADLINE_MS)); }
                    catch (TimeoutException e) { reply("FAILED " + e.getMessage()); }
                } else if (line.startsWith("SEND ")) {
                    reply("" + node.sendProbe(peer, line.substring(5)));
                } else if (line.equals("STATE")) {
                    int tcp = ((CommSystemFacadeImpl)node.context.commSystem()).getTransports().values().stream()
                            .filter(t -> t.getStyle().equals("NTCP")).mapToInt(Transport::countPeers).sum();
                    PeerState session = node.udp.getPeerState(peer.getHash());
                    reply(node.states("_inboundStates") + "," + node.states("_outboundStates") + "," + node.states("_inboundBans") +
                          "," + tcp + "," + node.udp.countPeers() + "," + (session != null ? session.getRemotePort() : 0) +
                          "," + (session != null ? session.getVersion() : 0) + "," + node.peerIndexSize("_peersByConnID") +
                          "," + node.peerIndexSize("_peersByRemoteHost") +
                          "," + (session instanceof PeerState2 ? ((PeerState2)session).getSendConnID() : 0) +
                          "," + (session instanceof PeerState2 ? ((PeerState2)session).getRcvConnID() : 0) + "," + IDS.get());
                } else if (line.equals("FORGET")) { node.forget(peer.getHash()); reply("OK"); }
                else if (line.equals("STOP")) { node.close(); reply("STOPPED"); System.exit(0); }
                else throw new IOException("Unknown command");
            }
        }
        throw new EOFException("Controller exited before orderly STOP");
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("I2P_DISABLE_OUTPUT_OVERRIDE", "true");
        if (args.length == 6 && args[0].equals("--node")) { node(args); return; }
        if (args.length != 3) throw new IllegalArgumentException("RUNTIME SCENARIO SEED");
        Path runtime = Path.of(args[0]).toAbsolutePath();
        if (Files.exists(runtime)) throw new IOException("Runtime must be a new directory");
        Files.createDirectories(runtime);
        Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwx------"));
        String scenario = args[1]; long seed = Long.parseLong(args[2]);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Also clean up children when this controller is interrupted outside
            // the Python runner. The runner additionally owns the process group.
            for (Process child : CHILDREN) child.destroyForcibly();
        }, "recovery-child-cleanup"));
        int a = freePort(), b = freePort();
        String result;
        boolean passed;
        try (Link link = new Link(a, b, seed);
             RemoteNode alice = new RemoteNode(runtime.resolve("a"), a, link.publicA.getLocalPort(), freePort(), scenario.equals("udp-blocked"));
             RemoteNode bob = new RemoteNode(runtime.resolve("b"), b, link.publicB.getLocalPort(), freePort(), scenario.equals("udp-blocked"))) {
            alice.connect(bob); bob.connect(alice);
            long warmup = alice.probe("UDP");
            long[] warmA = alice.state(), warmB = bob.state();
            if (warmA[4] != 1 || warmB[4] != 1 || warmB[5] != link.publicA.getLocalPort() ||
                warmA[6] < 2 || warmB[6] < 2)
                throw new AssertionError("Warmup did not establish the controlled UDP path");
            List<String> failures = new ArrayList<>();
            long faultStarted = System.nanoTime();
            switch (scenario) {
                case "control": break;
                case "packet-loss-reordering":
                    link.loss = true; link.reorder = true;
                    // A burst gives the proxy several packets to reorder even
                    // when bandwidth pacing separates the two fragments of one
                    // message. Sending one request can otherwise test only loss.
                    for (int i = 0; i < 8; i++) alice.send("UDP");
                    // Restore the link before measuring recovery. A sustained
                    // loss pattern can repeatedly discard one particular probe.
                    Thread.sleep(LOSS_WINDOW_MS);
                    link.loss = false; link.reorder = false;
                    break;
                case "nat-port-change": link.rotate(); break;
                case "expired-mapping":
                    link.staleMapping = true; link.rotate();
                    bob.send("UDP");
                    try { await(() -> link.expiredMappingDrops.get() > 0, 5000, "packet to expired mapping"); }
                    catch (TimeoutException e) { failures.add("expired-mapping-not-observed"); }
                    break;
                case "reconnect": alice.forget(); bob.forget(); break;
                case "udp-blocked":
                    // Leave the established session in place. The next AUTO
                    // probe must encounter the blocked path and fall back using
                    // the normal transport failure handling.
                    link.blocked = true;
                    break;
                default: throw new IllegalArgumentException("Unknown scenario");
            }
            long elapsed;
            try {
                alice.probe(scenario.equals("udp-blocked") ? "AUTO" : "UDP");
                elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - faultStarted);
                if (elapsed > DEADLINE_MS) failures.add("recovery-deadline");
                alice.probe(scenario.equals("udp-blocked") ? "AUTO" : "UDP");
            } catch (TimeoutException e) {
                failures.add("recovery-deadline");
                elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - faultStarted);
            }
            Thread.sleep(500);
            long[] as = alice.state(), bs = bob.state();
            long states = as[0] + as[1] + bs[0] + bs[1];
            long udpSessions = as[4] + bs[4], connectionIds = as[7] + bs[7], endpoints = as[8] + bs[8];
            boolean tcp = as[3] > 0 && bs[3] > 0;
            boolean migrated = bs[5] == link.publicA.getLocalPort() && bs[5] != warmB[5];
            boolean sameConnectionIds = as[9] == warmA[9] && as[10] == warmA[10] &&
                                        bs[9] == warmB[9] && bs[10] == warmB[10];
            if (scenario.equals("udp-blocked") && !tcp) failures.add("tcp-fallback-not-observed");
            if (scenario.equals("udp-blocked") && link.blockedDrops.get() == 0)
                failures.add("blocked-udp-not-observed");
            if (!scenario.equals("udp-blocked") && (as[4] != 1 || bs[4] != 1 || as[6] < 2 || bs[6] < 2 || tcp))
                failures.add("udp-session-not-retained");
            if (states > 0 || connectionIds > udpSessions || endpoints > udpSessions || as[2] + bs[2] > 0)
                failures.add("residual-state-bound");
            if ((scenario.equals("nat-port-change") || scenario.equals("expired-mapping")) && !migrated)
                failures.add("peer-endpoint-not-migrated");
            if ((scenario.equals("nat-port-change") || scenario.equals("expired-mapping")) && !sameConnectionIds)
                failures.add("connection-ids-changed-during-migration");
            if (scenario.equals("packet-loss-reordering") && (link.dropped.get() == 0 || link.reordered.get() == 0))
                failures.add("packet-faults-not-observed");
            passed = failures.isEmpty();
            result = "{\"scenario\":\"" + scenario + "\",\"seed\":" + seed + ",\"warmupMs\":" + warmup +
                ",\"recoveryMs\":" + elapsed + ",\"faultWindowMs\":" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - faultStarted) +
                ",\"injectedLossWindowMs\":" + (scenario.equals("packet-loss-reordering") ? LOSS_WINDOW_MS : 0) +
                ",\"probeRequestsSent\":" + (as[11] + bs[11]) +
                ",\"deadlineMs\":" + DEADLINE_MS + ",\"pendingEstablishments\":" + states + ",\"inboundBans\":" + (as[2]+bs[2]) +
                ",\"connectionIdEntries\":" + connectionIds + ",\"endpointEntries\":" + endpoints +
                ",\"droppedPackets\":" + link.dropped + ",\"forwardedPackets\":" + link.forwarded +
                ",\"reorderedPackets\":" + link.reordered + ",\"blockedUDPPackets\":" + link.blockedDrops +
                ",\"expiredMappingPackets\":" + link.expiredMappingDrops + ",\"peerEndpointMigrated\":" + migrated +
                ",\"connectionIdsRetained\":" + sameConnectionIds +
                ",\"warmupSSUVersionA\":" + warmA[6] + ",\"warmupSSUVersionB\":" + warmB[6] +
                ",\"recoveredSSUVersionA\":" + as[6] + ",\"recoveredSSUVersionB\":" + bs[6] +
                ",\"udpSessions\":" + udpSessions + ",\"tcpFallback\":" + tcp + ",\"passed\":" + passed +
                ",\"failure\":\"" + String.join(";", failures) + "\",\"shutdownConfirmed\":true}";
        }
        // Emit only after both child JVMs report shutdown and exit zero.
        System.out.println("RECOVERY_RESULT " + result);
        System.exit(passed ? 0 : 1);
    }
}
