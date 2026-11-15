package org.klomp.snark;

import java.lang.reflect.Field;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.crypto.SigType;

import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.dht.DHT;

/** Exercises real tracker/coordinator lifecycle without sockets or disk storage. */
public final class SnarkDiscoveryRegression {
    private static int checks;

    public static void main(String[] args) throws Throwable {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        context.logManager().setDefaultLimit("CRIT");
        try {
            asynchronousTracker(context);
            changedDHT(context);
            privateTorrent(context);
            pendingConnections(context);
            cancelledConnection(context);
            System.out.println("SNARK_DISCOVERY_CHECKS=" + checks);
        } finally {
            context.logManager().shutdown();
        }
    }

    private static void asynchronousTracker(I2PAppContext context) throws Throwable {
        TestUtil util = new TestUtil(context);
        Snark snark = magnet(util);
        RecordingCoordinator coordinator = new RecordingCoordinator(util, snark);
        TrackerClient tracker = new TrackerClient(util, null, null, coordinator, snark);
        try {
            set(tracker, "stop", false);
            long start = System.nanoTime();
            discover(tracker);
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500,
                    "tracker does not wait for DHT completion");
            check(util.dht.tasks.size() == 1 && util.dht.announceWait == 0,
                    "DHT discovery does not wait for announce acknowledgements");
            discover(tracker);
            check(util.dht.tasks.size() == 1, "one DHT discovery at a time per torrent");
            util.dht.complete(0, List.of(hash(1), hash(2)));
            check(coordinator.accepted.size() == 2, "DHT callback connects peers independently of tracker loop");
            check(snark.getTrackerSeenPeers() == 2, "asynchronous discovery updates visible peer count");
            discover(tracker);
            check(util.dht.tasks.size() == 2, "completed magnet discovery permits another pass");
            tracker.halt(true);
            check(util.dht.tasks.get(1).isCancelled(), "halting torrent cancels its DHT future");
            util.dht.callbacks.get(1).accept(List.of(hash(3)));
            check(coordinator.accepted.size() == 2, "stopped torrent rejects late completion");
            set(tracker, "stop", false);
            discover(tracker);
            util.dht.callbacks.get(1).accept(List.of(hash(3)));
            check(coordinator.accepted.size() == 2, "restarted torrent rejects previous generation's completion");
            util.dht.complete(2, List.of(hash(4)));
            check(coordinator.accepted.size() == 3, "restarted torrent accepts current discovery");
        } finally {
            tracker.halt(true);
            coordinator.halt();
        }
    }

    private static void changedDHT(I2PAppContext context) throws Throwable {
        TestUtil util = new TestUtil(context);
        Snark snark = magnet(util);
        RecordingCoordinator coordinator = new RecordingCoordinator(util, snark);
        TrackerClient tracker = new TrackerClient(util, null, null, coordinator, snark);
        try {
            set(tracker, "stop", false);
            discover(tracker);
            FakeDHT old = util.dht;
            util.dht = new FakeDHT();
            discover(tracker);
            check(old.tasks.get(0).isCancelled(), "replacing DHT cancels previous discovery");
            check(util.dht.tasks.size() == 1, "replacement DHT can start without waiting for old task");
            old.callbacks.get(0).accept(List.of(hash(1)));
            check(coordinator.accepted.isEmpty(), "previous DHT cannot deliver peers after replacement");
            util.dht.complete(0, List.of(hash(2)));
            check(coordinator.accepted.size() == 1, "replacement DHT delivers peers");
        } finally {
            tracker.halt(true);
            coordinator.halt();
        }
    }

    private static void privateTorrent(I2PAppContext context) throws Throwable {
        TestUtil util = new TestUtil(context);
        Snark snark = magnet(util);
        RecordingCoordinator coordinator = new RecordingCoordinator(util, snark);
        MetaInfo privateMeta = privateMeta();
        TrackerClient tracker = new TrackerClient(util, privateMeta, null, coordinator, snark);
        try {
            set(tracker, "stop", false);
            discover(tracker);
            check(util.dht.tasks.isEmpty(), "private torrent does not start DHT discovery");
        } finally {
            tracker.halt(true);
            coordinator.halt();
        }
        snark = magnet(util);
        coordinator = new RecordingCoordinator(util, snark);
        tracker = new TrackerClient(util, null, null, coordinator, snark);
        try {
            set(tracker, "stop", false);
            discover(tracker);
            set(snark, "meta", privateMeta);
            tracker.gotMetaInfo(privateMeta);
            check(util.dht.tasks.get(0).isCancelled(), "private metadata immediately cancels public discovery");
            util.dht.callbacks.get(0).accept(List.of(hash(1)));
            check(coordinator.accepted.isEmpty(), "magnet becoming private rejects pending DHT results");
        } finally {
            tracker.halt(true);
            coordinator.halt();
        }
    }

    private static void pendingConnections(I2PAppContext context) throws Throwable {
        TestUtil util = new TestUtil(context);
        Snark snark = magnet(util);
        PeerCoordinator coordinator = new PeerCoordinator(util, snark.getID(), snark.getInfoHash(),
                null, null, null, snark, bandwidth());
        List<BlockingPeer> peers = new ArrayList<>();
        try {
            BlockingPeer first = new BlockingPeer(1, snark, util);
            peers.add(first);
            check(coordinator.addPeer(first), "first pending connection accepted");
            check(first.entered.await(1, TimeUnit.SECONDS), "asynchronous handshake has started");
            check(!coordinator.addPeer(new BlockingPeer(1, snark, util)), "duplicate pending handshake rejected");
            for (int i = 2; i <= 6; i++) {
                BlockingPeer peer = new BlockingPeer(i, snark, util);
                peers.add(peer);
                check(coordinator.addPeer(peer), "pending connection within magnet limit");
            }
            check(!coordinator.addPeer(new BlockingPeer(7, snark, util)), "pending handshakes count toward connection limit");
            check(!coordinator.needOutboundPeers(), "discovery pauses when pending slots are full");
            first.release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (pending(coordinator).size() == 6 && System.nanoTime() < deadline) Thread.sleep(5);
            BlockingPeer replacement = new BlockingPeer(7, snark, util);
            peers.add(replacement);
            check(coordinator.addPeer(replacement), "failed handshake releases its connection slot");
            coordinator.halt();
            check(pending(coordinator).isEmpty(), "halt removes all pending reservations");
            check(peers.stream().filter(peer -> peer != first).allMatch(peer -> peer.disconnected),
                    "halt disconnects pending peers");
            check(!coordinator.addPeer(new BlockingPeer(8, snark, util)), "halted coordinator refuses new work");
        } finally {
            coordinator.halt();
            for (BlockingPeer peer : peers) peer.release.countDown();
        }
    }

    private static final class BlockingPeer extends Peer {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean disconnected;

        BlockingPeer(int number, Snark snark, I2PSnarkUtil util) throws Exception {
            super(new PeerID(hash(number).getData(), util), snark.getID(), snark.getInfoHash(), null);
        }

        @Override
        public void runConnection(I2PSnarkUtil util, PeerListener listener, BandwidthListener bandwidth,
                                  BitField bitfield, MagnetState state, boolean uploadOnly) {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }

        @Override
        public void disconnect(boolean deregister) {
            disconnect();
        }

        @Override
        void disconnect() {
            disconnected = true;
            release.countDown();
        }
    }

    private static void cancelledConnection(I2PAppContext context) throws Throwable {
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        I2PSocket socket = (I2PSocket) Proxy.newProxyInstance(I2PSocket.class.getClassLoader(),
                new Class<?>[] {I2PSocket.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("close")) { closed.set(true); return null; }
                    if (method.getName().equals("isClosed")) return closed.get();
                    throw new AssertionError("cancelled connection used socket: " + method.getName());
                });
        I2PSnarkUtil util = new I2PSnarkUtil(context, "cancel-test", null) {
            @Override
            I2PSocket connect(PeerID id) throws IOException {
                connecting.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { throw new IOException(interrupted); }
                return socket;
            }
        };
        Snark snark = magnet(util);
        RecordingCoordinator coordinator = new RecordingCoordinator(util, snark);
        Destination destination = I2PClientFactory.createClient().createDestination(new ByteArrayOutputStream(),
                SigType.EdDSA_SHA512_Ed25519);
        Peer peer = new Peer(new PeerID(new byte[20], destination), snark.getID(), snark.getInfoHash(), null);
        Thread worker = new Thread(() -> peer.runConnection(util, coordinator, bandwidth(), null,
                new MagnetState(snark.getInfoHash(), null), false));
        try {
            worker.start();
            check(connecting.await(1, TimeUnit.SECONDS), "peer connection is pending before cancellation");
            peer.disconnect(false);
            release.countDown();
            worker.join(2000);
            check(!worker.isAlive() && closed.get(), "socket arriving after cancellation is closed immediately");
            check(coordinator.accepted.isEmpty(), "cancelled handshake never becomes a connected peer");
        } finally {
            release.countDown();
            worker.join(2000);
            coordinator.halt();
        }
    }

    private static final class RecordingCoordinator extends PeerCoordinator {
        final List<PeerID> accepted = new CopyOnWriteArrayList<>();

        RecordingCoordinator(I2PSnarkUtil util, Snark snark) {
            super(util, snark.getID(), snark.getInfoHash(), null, null, null, snark, bandwidth());
        }

        @Override
        public boolean needOutboundPeers() { return !halted(); }

        @Override
        public boolean addPeer(Peer peer) { accepted.add(peer.getPeerID()); return true; }

        @Override
        public boolean isBanned(Hash peer) { return false; }
    }

    private static final class TestUtil extends I2PSnarkUtil {
        volatile FakeDHT dht = new FakeDHT();
        TestUtil(I2PAppContext context) { super(context, "discovery-test", null); }
        @Override
        public DHT getDHT() { return dht; }
    }

    private static final class FakeDHT implements DHT {
        final List<FutureTask<Void>> tasks = new ArrayList<>();
        final List<Consumer<Collection<Hash>>> callbacks = new ArrayList<>();
        long announceWait;

        public Future<?> getPeersAndAnnounceAsync(byte[] ih, int max, long wait, int annMax,
                long annWait, boolean seed, boolean noSeeds, Consumer<Collection<Hash>> callback) {
            FutureTask<Void> task = new FutureTask<>(() -> null);
            tasks.add(task);
            callbacks.add(callback);
            announceWait = annWait;
            return task;
        }
        void complete(int number, Collection<Hash> peers) {
            callbacks.get(number).accept(peers);
            tasks.get(number).run();
        }
        public Collection<Hash> getPeersAndAnnounce(byte[] ih, int max, long wait, int annMax,
                long annWait, boolean seed, boolean noSeeds) { throw new AssertionError("synchronous discovery called"); }
        public int getPort() { return 7000; }
        public int getRPort() { return 7001; }
        public void ping(Destination destination, int port) { }
        public void announce(byte[] ih, boolean seed) { }
        public void announce(byte[] ih, byte[] peer, boolean seed) { }
        public int announce(byte[] ih, int max, long wait, boolean seed) { return 0; }
        public void unannounce(byte[] ih) { }
        public void stop() { }
        public int size() { return 8; }
        public String renderStatusHTML() { return ""; }
    }

    private static MetaInfo privateMeta() throws Exception {
        Map<String, BEValue> info = new HashMap<>();
        info.put("name", new BEValue(new byte[] {'x'}));
        info.put("length", new BEValue(1));
        info.put("piece length", new BEValue(16384));
        info.put("pieces", new BEValue(new byte[20]));
        info.put("private", new BEValue(1));
        return new MetaInfo(Collections.singletonMap("info", new BEValue(info)));
    }

    private static BandwidthListener bandwidth() {
        return (BandwidthListener) Proxy.newProxyInstance(BandwidthListener.class.getClassLoader(),
                new Class<?>[] {BandwidthListener.class}, (proxy, method, arguments) -> {
                    if (method.getReturnType() == long.class) return 1024L * 1024;
                    if (method.getReturnType() == boolean.class) return method.getName().startsWith("should");
                    return null;
                });
    }

    private static Snark magnet(I2PSnarkUtil util) {
        return new Snark(util, "test magnet", new byte[20], null, null, null, null, null);
    }

    private static Hash hash(int value) {
        byte[] data = new byte[32];
        data[31] = (byte) value;
        return new Hash(data);
    }

    private static void discover(TrackerClient tracker) throws Throwable {
        Method method = TrackerClient.class.getDeclaredMethod("getPeersFromDHT");
        method.setAccessible(true);
        method.invoke(tracker);
    }

    private static Map<?, ?> pending(PeerCoordinator coordinator) throws Exception {
        Field field = PeerCoordinator.class.getDeclaredField("pendingPeers");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(coordinator);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        checks++;
    }
}
