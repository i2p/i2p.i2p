package org.klomp.snark;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEncoder;
import org.klomp.snark.bencode.BEValue;

/** Real extension messages through controlled peers; no sockets or downloads. */
public final class PeerCatalogRegression {
    private static int checks;
    private static final AtomicLong clock = new AtomicLong(1000);
    private static I2PSnarkUtil util;
    private static I2PAppContext context;
    private static int peerID;

    public static void main(String[] args) throws Throwable {
        context = I2PAppContext.getGlobalContext();
        context.logManager().setDefaultLimit("CRIT");
        util = new I2PSnarkUtil(context, "catalog-regression", null);
        try {
            optInAndPrivacy();
            paginationAndUnsharing();
            malformedAndUnsolicited();
            boundsAndExpiry();
            cacheBound();
            negotiation();
            System.out.println("PEER_CATALOG_CHECKS=" + checks);
        } finally {
            context.logManager().shutdown();
        }
    }

    private static void optInAndPrivacy() throws Throwable {
        StubSnark publicTorrent = torrent("A public torrent", false, false);
        StubSnark privateTorrent = torrent("Private name must stay local", true, false);
        StubSnark stoppedTorrent = torrent("Stopped name must stay local", false, true);
        PeerCatalog server = catalog(List.of(publicTorrent, privateTorrent, stoppedTorrent));
        PeerCatalog client = catalog(List.of());
        Pair peers = pair(client, server);
        check(client.refresh(List.of(peers.client)) == 1, "request uses an existing peer connection");
        check(client.search("").isEmpty(), "nothing is advertised by default");
        server.setSharedHashes(List.of(hex(publicTorrent), hex(privateTorrent), hex(stoppedTorrent)));
        advance();
        client.refresh(List.of(peers.client));
        List<PeerCatalog.Entry> results = client.search("PUBLIC");
        check(results.size() == 1 && results.get(0).getName().equals(publicTorrent.getBaseName()),
                "only selected running public torrents are shared");
        check(client.search("private").isEmpty() && client.search("stopped").isEmpty(),
                "private and stopped names never enter peer results");
        check(results.get(0).getMagnet().contains(hex(publicTorrent)),
                "listing contains the torrent info hash");
        check(!results.get(0).getMagnet().contains("&tr="), "remote listings cannot inject trackers or download URLs");
        check(client.pendingCount() == 0, "immediate replies complete the registered request");
        for (Frame frame : peers.server.sent) {
            Map<String, BEValue> decoded = decode(frame.bytes);
            for (BEValue item : decoded.get("items").getList())
                check(item.getMap().keySet().equals(Set.of("ih", "name")), "only name and info hash leave the client");
        }
        int frames = peers.client.sent.size();
        client.search("this search phrase must stay local");
        check(peers.client.sent.size() == frames, "search terms are never sent to peers");
        check(client.refresh(List.of(peers.client)) == 0, "refresh requests are rate limited");
        server.setSharedHashes(List.of());
        advance();
        client.refresh(List.of(peers.client));
        check(client.search("").isEmpty(), "unsharing takes effect on the next refresh");

        FakePeer privateChannel = peer(privateTorrent.getMetaInfo(), 5);
        FakePeer unknownChannel = peer(null, 5);
        advance();
        check(client.refresh(List.of(privateChannel, unknownChannel)) == 0, "private and unknown torrent channels are excluded");
        server.receive(privateChannel, request(new byte[8], 0));
        server.receive(unknownChannel, request(new byte[8], 0));
        check(privateChannel.sent.isEmpty() && unknownChannel.sent.isEmpty(), "private channels receive no catalog response");
    }

    private static void paginationAndUnsharing() throws Throwable {
        List<Snark> library = new ArrayList<>();
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < PeerCatalog.MAX_SHARED; i++) {
            StubSnark torrent = torrent(String.format("Public %03d é", i), false, false);
            library.add(torrent);
            selected.add(hex(torrent));
        }
        PeerCatalog server = catalog(library);
        server.setSharedHashes(selected);
        PeerCatalog client = catalog(List.of());
        Pair peers = pair(client, server);
        advance();
        client.refresh(List.of(peers.client));
        check(client.search("").size() == PeerCatalog.MAX_SHARED, "all selected torrents arrive through bounded pages");
        check(peers.server.sent.size() == 5 && peers.client.sent.size() == 5, "catalog exchange stops after five pages");
        for (Frame frame : peers.server.sent)
            check(frame.bytes.length <= PeerCatalog.MAX_MESSAGE && decode(frame.bytes).get("items").getList().size() <= 20,
                    "response size and entry count are bounded");
        check(client.pendingCount() == 0, "pagination clears the pending request");

        // Receiving a listing never opts it into forwarding to other peers.
        FakePeer visitor = peer(publicMeta("other channel"), 5);
        client.receive(visitor, request(new byte[8], 0));
        check(decode(visitor.sent.get(0).bytes).get("items").getList().isEmpty(), "received listings are never reshared");

        PeerCatalog secondClient = catalog(List.of());
        Pair second = pair(secondClient, server);
        second.server.sink = frame -> {
            server.setSharedHashes(List.of());
            secondClient.receive(second.client, frame.bytes);
        };
        advance();
        secondClient.refresh(List.of(second.client));
        check(secondClient.search("").size() == 20, "unsharing during pagination stops disclosing later pages");
    }

    private static void malformedAndUnsolicited() throws Throwable {
        PeerCatalog catalog = catalog(List.of());
        FakePeer peer = peer(publicMeta("channel"), 23);
        byte[] unsolicited = response(new byte[8], 0, List.of(item(hash(1), "unsolicited")), 0);
        catalog.receive(peer, unsolicited);
        check(catalog.search("").isEmpty(), "unsolicited replies are ignored");
        advance();
        catalog.refresh(List.of(peer));
        check(peer.sent.get(0).id == 23, "outgoing messages use the remote peer's negotiated ID");
        byte[] id = decode(peer.sent.get(0).bytes).get("id").getBytes();
        List<byte[]> malformed = new ArrayList<>();
        malformed.add(response(new byte[8], 0, List.of(item(hash(1), "wrong request")), 0));
        malformed.add(response(id, 1, List.of(item(hash(1), "wrong page")), 0));
        malformed.add(response(id, 0, List.of(item(new byte[19], "bad hash")), 0));
        malformed.add(response(id, 0, List.of(item(hash(1), "bad\nname")), 0));
        malformed.add(response(id, 0, List.of(item(hash(1), "bad\u202ename")), 0));
        malformed.add(response(id, 0, List.of(item(hash(1), "x".repeat(257))), 0));
        Map<String, Object> invalidUTF8 = new HashMap<>();
        invalidUTF8.put("ih", hash(1));
        invalidUTF8.put("name", new byte[] {(byte) 0xc0, (byte) 0xaf});
        malformed.add(response(id, 0, List.of(invalidUTF8), 0));
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) tooMany.add(item(hash(i), "entry"));
        malformed.add(response(id, 0, tooMany, 0));
        malformed.add(response(id, 0, List.of(item(hash(1), "bad more")), 2));
        byte[] good = response(id, 0, List.of(item(hash(1), "good")), 0);
        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        trailing[trailing.length - 1] = 'x';
        malformed.add(trailing);
        malformed.add(new byte[PeerCatalog.MAX_MESSAGE + 1]);
        // Small frame, huge declared string: must be rejected before allocation.
        malformed.add(DataHelper.getUTF8("d4:name2147483647:"));
        malformed.add(DataHelper.getUTF8("d1:v" + "l".repeat(200) + "e".repeat(201)));
        for (byte[] bytes : malformed) {
            catalog.receive(peer, bytes);
            check(catalog.search("").isEmpty(), "malformed frame cannot populate results");
        }
        catalog.receive(peer, good);
        check(catalog.search("").size() == 1, "valid correlated reply still works after malformed frames");
        catalog.receive(peer, response(id, 0, List.of(item(hash(2), "replayed")), 0));
        check(catalog.search("").size() == 1, "completed replies cannot be replayed");

        PeerCatalog server = catalog(List.of());
        FakePeer requester = peer(publicMeta("channel"), 5);
        byte[] request = request(new byte[8], 0);
        server.receive(requester, request);
        server.receive(requester, request);
        server.receive(requester, request(new byte[8], 2));
        check(requester.sent.size() == 1, "request replay and out-of-order pages do not amplify responses");
    }

    private static void boundsAndExpiry() throws Throwable {
        PeerCatalog catalog = catalog(List.of());
        List<Peer> peers = new ArrayList<>();
        for (int i = 0; i < 30; i++) peers.add(peer(publicMeta("channel"), 5));
        advance();
        check(catalog.refresh(peers) == 16 && catalog.pendingCount() == 16, "refresh fanout and pending requests are bounded");
        catalog.disconnected(peers.get(0));
        check(catalog.pendingCount() == 15, "disconnect removes a pending request");
        clock.addAndGet(60_000);
        check(catalog.pendingCount() == 0, "pending requests expire");

        List<Snark> library = new ArrayList<>();
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            StubSnark torrent = torrent("Title " + i + " " + "é".repeat(200), false, false);
            library.add(torrent);
            selected.add(hex(torrent));
        }
        PeerCatalog server = catalog(library);
        server.setSharedHashes(selected);
        PeerCatalog client = catalog(List.of());
        Pair pair = pair(client, server);
        client.refresh(List.of(pair.client));
        check(client.search("").size() == 100, "long names are bounded without losing the catalog");
        for (Frame frame : pair.server.sent)
            check(frame.bytes.length <= PeerCatalog.MAX_MESSAGE, "worst-case name pages stay below frame limit");
        clock.addAndGet(10*60_000);
        check(client.search("").isEmpty(), "remote result cache expires");
        advance();
        catalog.refresh(peers);
        catalog.stop();
        check(catalog.pendingCount() == 0 && catalog.search("").isEmpty(), "stop clears peer state and results");
        FakePeer stopped = peer(publicMeta("channel"), 5);
        catalog.receive(stopped, request(new byte[8], 0));
        check(stopped.sent.isEmpty(), "stopped catalog never responds");
    }

    private static void negotiation() throws Throwable {
        Map<String, BEValue> standard = decode(ExtensionHandler.getHandshake(1, true, true, false, true));
        check(!standard.get("m").getMap().containsKey(PeerCatalog.EXTENSION), "legacy handshake API does not opt into catalog support");
        Map<String, BEValue> enabled = decode(ExtensionHandler.getHandshake(1, true, true, false, true, true));
        check(enabled.get("m").getMap().get(PeerCatalog.EXTENSION).getInt() == ExtensionHandler.ID_CATALOG,
                "new extension is negotiated through BEP 10");
        for (Number code : List.of(0, 256, new BigInteger("4294967301"))) {
            FakePeer peer = peer(publicMeta("channel"), code);
            PeerCatalog catalog = catalog(List.of());
            check(catalog.refresh(List.of(peer)) == 0 && peer.sent.isEmpty(), "disabled or invalid extension IDs are rejected");
        }
        PeerCatalog service = catalog(List.of());
        util.setPeerCatalog(service);
        PeerListener listener = (PeerListener) java.lang.reflect.Proxy.newProxyInstance(
                PeerListener.class.getClassLoader(), new Class<?>[] {PeerListener.class},
                (proxy, method, args) -> method.getName().equals("getUtil") ? util : null);
        FakePeer channel = peer(publicMeta("dispatch"), 23);
        ExtensionHandler.handleMessage(channel, listener, ExtensionHandler.ID_CATALOG, request(new byte[8], 0));
        check(channel.sent.size() == 1 && channel.sent.get(0).id == 23,
                "production extension dispatch routes replies using negotiated remote ID");
        FakePeer privateChannel = peer(meta("private dispatch", true), 23);
        ExtensionHandler.handleMessage(privateChannel, listener, ExtensionHandler.ID_CATALOG, request(new byte[8], 0));
        check(privateChannel.sent.isEmpty(), "production dispatch excludes private channels");
    }

    private static void cacheBound() throws Throwable {
        PeerCatalog catalog = catalog(List.of());
        List<Peer> peers = new ArrayList<>();
        for (int i = 0; i < 3; i++) peers.add(peer(publicMeta("cache channel"), 5));
        catalog.refresh(peers);
        for (int source = 0; source < peers.size(); source++) {
            FakePeer peer = (FakePeer) peers.get(source);
            byte[] id = decode(peer.sent.get(0).bytes).get("id").getBytes();
            for (int page = 0; page < 5; page++) {
                List<Map<String, Object>> items = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    int index = source * 100 + page * 20 + i;
                    items.add(item(hash(index), "Cache entry " + index));
                }
                catalog.receive(peer, response(id, page, items, page < 4 ? 1 : 0));
            }
        }
        check(catalog.search("").size() == PeerCatalog.MAX_RESULTS && catalog.pendingCount() == 0,
                "multiple sources cannot exceed the cache bound and all pulls terminate");
    }

    private static PeerCatalog catalog(Collection<Snark> library) {
        PeerCatalog catalog = new PeerCatalog(context, () -> library, clock::get);
        catalog.start();
        return catalog;
    }

    private static Pair pair(PeerCatalog client, PeerCatalog server) throws Exception {
        Pair pair = new Pair(peer(publicMeta("channel"), 5), peer(publicMeta("channel"), 5));
        pair.client.sink = frame -> server.receive(pair.server, frame.bytes);
        pair.server.sink = frame -> client.receive(pair.client, frame.bytes);
        return pair;
    }

    private static final class Pair {
        final FakePeer client, server;
        Pair(FakePeer client, FakePeer server) { this.client = client; this.server = server; }
    }

    private static final class Frame {
        final int id;
        final byte[] bytes;
        Frame(int id, byte[] bytes) { this.id = id; this.bytes = bytes.clone(); }
    }

    static final class FakePeer extends Peer {
        final List<Frame> sent = new ArrayList<>();
        Consumer<Frame> sink;
        FakePeer(MetaInfo meta, Number messageID) throws Exception {
            super(new PeerID(destination(++peerID), util), new byte[20], new byte[20], meta);
            setHandshakeMap(Map.of("m", new BEValue(Map.of(PeerCatalog.EXTENSION, new BEValue(messageID)))));
        }
        @Override public boolean isConnected() { return true; }
        @Override public void sendExtension(int id, byte[] payload) {
            Frame frame = new Frame(id, payload);
            sent.add(frame);
            if (sink != null) sink.accept(frame);
        }
    }

    private static FakePeer peer(MetaInfo meta, Number code) throws Exception { return new FakePeer(meta, code); }
    private static byte[] destination(int id) { byte[] bytes = new byte[32]; DataHelper.toLong(bytes, 28, 4, id); return bytes; }
    private static byte[] hash(int id) { byte[] hash = new byte[20]; DataHelper.toLong(hash, 16, 4, id); return hash; }
    private static void advance() { clock.addAndGet(31_000); }
    private static String hex(Snark torrent) { return I2PSnarkUtil.toHex(torrent.getInfoHash()).toLowerCase(java.util.Locale.US); }

    private static StubSnark torrent(String name, boolean privateTorrent, boolean stopped) throws Exception {
        return new StubSnark(meta(name, privateTorrent), stopped);
    }

    private static final class StubSnark extends Snark {
        final MetaInfo info;
        final boolean stopped;
        StubSnark(MetaInfo info, boolean stopped) {
            super(util, info.getName(), info.getInfoHash(), null, null, null, null, null);
            this.info = info;
            this.stopped = stopped;
        }
        @Override public MetaInfo getMetaInfo() { return info; }
        @Override public boolean isStopped() { return stopped; }
        @Override public String getBaseName() { return info.getName(); }
    }

    static MetaInfo publicMeta(String name) throws Exception { return meta(name, false); }
    static MetaInfo meta(String name, boolean privateTorrent) throws Exception {
        Map<String, BEValue> info = new HashMap<>();
        info.put("name", new BEValue(DataHelper.getUTF8(name)));
        info.put("length", new BEValue(1));
        info.put("piece length", new BEValue(16384));
        info.put("pieces", new BEValue(new byte[20]));
        if (privateTorrent) info.put("private", new BEValue(1));
        return new MetaInfo(Map.of("info", new BEValue(info)));
    }

    private static Map<String, Object> item(byte[] hash, String name) { return Map.of("ih", hash, "name", name); }
    private static byte[] request(byte[] id, int page) { return BEncoder.bencode(Map.of("v", 1, "msg_type", 0, "id", id, "page", page)); }
    private static byte[] response(byte[] id, int page, List<Map<String, Object>> items, int more) {
        return BEncoder.bencode(Map.of("v", 1, "msg_type", 1, "id", id, "page", page, "items", items, "more", more));
    }
    private static Map<String, BEValue> decode(byte[] bytes) throws Throwable {
        return new BDecoder(new ByteArrayInputStream(bytes), PeerCatalog.MAX_MESSAGE).bdecodeMap().getMap();
    }
    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        checks++;
    }
}
