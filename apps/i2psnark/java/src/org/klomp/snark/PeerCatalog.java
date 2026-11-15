package org.klomp.snark;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEncoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;

/** Opt-in public torrent listings exchanged only over existing I2P peer streams. */
public final class PeerCatalog {
    public static final String EXTENSION = "i2p_catalog";
    public static final int MAX_SHARED = 100;
    public static final int MAX_RESULTS = 200;
    static final int PAGE_SIZE = 20;
    static final int MAX_MESSAGE = 8192;
    private static final int MAX_PAGES = MAX_SHARED / PAGE_SIZE;
    private static final int MAX_PEERS = 16;
    private static final int MAX_INBOUND = 128;
    private static final int MAX_NAME = 256;
    private static final long REQUEST_TTL = 60*1000;
    private static final long RESULT_TTL = 10*60*1000;
    private static final long REFRESH_INTERVAL = 30*1000;

    private final I2PAppContext context;
    private final Supplier<Collection<Snark>> torrents;
    private final LongSupplier clock;
    private Set<String> shared = Collections.emptySet();
    private final Map<Peer, Pending> pending = new HashMap<Peer, Pending>();
    private final Map<Peer, Incoming> incoming = new HashMap<Peer, Incoming>();
    private final Map<String, Cached> results = new LinkedHashMap<String, Cached>();
    private boolean active;
    private long lastRefresh;

    public PeerCatalog(I2PAppContext context, Supplier<Collection<Snark>> torrents) {
        this(context, torrents, new LongSupplier() {
            public long getAsLong() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
        });
    }

    PeerCatalog(I2PAppContext context, Supplier<Collection<Snark>> torrents, LongSupplier clock) {
        this.context = context;
        this.torrents = torrents;
        this.clock = clock;
    }

    public synchronized void start() { active = true; }

    public synchronized void stop() {
        active = false;
        pending.clear();
        incoming.clear();
        results.clear();
        lastRefresh = 0;
    }

    public synchronized void setSharedHashes(Collection<String> hashes) {
        Set<String> selected = new HashSet<String>();
        for (String hash : hashes) {
            if (selected.size() >= MAX_SHARED)
                break;
            if (hash != null && hash.matches("[a-fA-F0-9]{40}"))
                selected.add(hash.toLowerCase(Locale.US));
        }
        shared = selected;
    }

    public synchronized boolean isShared(byte[] hash) {
        return shared.contains(hex(hash));
    }

    public static boolean canShare(Snark snark) {
        MetaInfo meta = snark.getMetaInfo();
        return meta != null && !meta.isPrivate();
    }

    /** Local selections only; peer results are never forwarded to another peer. */
    private List<Entry> localEntries() {
        List<Entry> entries = new ArrayList<Entry>();
        for (Snark snark : torrents.get()) {
            if (entries.size() >= MAX_SHARED)
                break;
            MetaInfo meta = snark.getMetaInfo();
            if (meta == null || meta.isPrivate() || snark.isStopped() || !shared.contains(hex(snark.getInfoHash())))
                continue;
            String name = meta.getName();
            if (name.length() > MAX_NAME)
                name = name.substring(0, MAX_NAME);
            while (DataHelper.getUTF8(name).length > MAX_NAME)
                name = name.substring(0, name.offsetByCodePoints(name.length(), -1));
            if (validName(name))
                entries.add(new Entry(snark.getInfoHash(), name));
        }
        Collections.sort(entries, ENTRY_ORDER);
        return entries;
    }

    /** Start a bounded pull of explicitly shared listings. Search terms stay local. */
    public synchronized int refresh(Collection<Peer> peers) {
        long now = clock.getAsLong();
        clean(now);
        if (!active || (lastRefresh != 0 && now - lastRefresh < REFRESH_INTERVAL))
            return 0;
        lastRefresh = now;
        pending.clear();
        results.clear();
        Set<String> contacted = new HashSet<String>();
        int sent = 0;
        for (Peer peer : peers) {
            if (sent >= MAX_PEERS)
                break;
            if (!eligible(peer) || messageID(peer) == 0 || !contacted.add(hex(peer.getPeerID().getDestHash())))
                continue;
            byte[] id = new byte[8];
            context.random().nextBytes(id);
            Pending request = new Pending(id, now);
            pending.put(peer, request);
            sendRequest(peer, request);
            sent++;
        }
        return sent;
    }

    public synchronized List<Entry> search(String query) {
        clean(clock.getAsLong());
        String term = query == null ? "" : query.trim().toLowerCase(Locale.US);
        if (term.length() > 80)
            term = term.substring(0, 80);
        List<Entry> found = new ArrayList<Entry>();
        for (Cached cached : results.values()) {
            if (cached.entry.name.toLowerCase(Locale.US).contains(term))
                found.add(cached.entry);
        }
        Collections.sort(found, ENTRY_ORDER);
        return found;
    }

    public synchronized int pendingCount() {
        clean(clock.getAsLong());
        return pending.size();
    }

    synchronized void disconnected(Peer peer) {
        pending.remove(peer);
        incoming.remove(peer);
    }

    /** Called only for the negotiated extension on an existing peer connection. */
    synchronized void receive(Peer peer, byte[] payload) {
        if (!active || !eligible(peer) || messageID(peer) == 0 || payload.length > MAX_MESSAGE)
            return;
        long now = clock.getAsLong();
        clean(now);
        try {
            ByteArrayInputStream stream = new ByteArrayInputStream(payload);
            Map<String, BEValue> message = new BDecoder(stream, MAX_MESSAGE).bdecodeMap().getMap();
            if (stream.available() != 0 || number(message, "v") != 1)
                return;
            byte[] id = message.get("id").getBytes();
            int page = number(message, "page");
            if (id.length != 8 || page < 0 || page >= MAX_PAGES)
                return;
            int type = number(message, "msg_type");
            if (type == 0)
                respond(peer, id, page, now);
            else if (type == 1)
                accept(peer, id, page, message, now);
        } catch (Throwable malformed) {
            if (malformed instanceof Error)
                throw (Error) malformed;
            // Untrusted listings never affect torrent state or execute an action.
        }
    }

    private void respond(Peer peer, byte[] id, int page, long now) throws IOException {
        Incoming previous = incoming.get(peer);
        if (page == 0) {
            if ((previous != null && now - previous.started < REFRESH_INTERVAL) ||
                (previous == null && incoming.size() >= MAX_INBOUND))
                return;
            previous = new Incoming(id, now);
            incoming.put(peer, previous);
        }
        if (previous == null || previous.nextPage != page || !MessageDigest.isEqual(previous.id, id) ||
            now - previous.started >= REQUEST_TTL)
            return;
        previous.nextPage++;
        List<Entry> local = localEntries();
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int end = Math.min(local.size(), (page + 1) * PAGE_SIZE);
        for (int i = page * PAGE_SIZE; i < end; i++) {
            Entry entry = local.get(i);
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("ih", entry.hash);
            item.put("name", entry.name);
            items.add(item);
        }
        Map<String, Object> response = message(1, id, page);
        response.put("items", items);
        response.put("more", Integer.valueOf(end < local.size() ? 1 : 0));
        send(peer, response);
    }

    private void accept(Peer peer, byte[] id, int page, Map<String, BEValue> message, long now) throws IOException {
        Pending request = pending.get(peer);
        if (request == null || request.page != page || !MessageDigest.isEqual(request.id, id))
            return;
        List<BEValue> items = message.get("items").getList();
        int more = number(message, "more");
        if (items.size() > PAGE_SIZE || more < 0 || more > 1)
            return;
        List<Entry> accepted = new ArrayList<Entry>(items.size());
        for (BEValue value : items) {
            Map<String, BEValue> item = value.getMap();
            byte[] hash = item.get("ih").getBytes();
            byte[] bytes = item.get("name").getBytes();
            if (hash.length != 20 || bytes.length == 0 || bytes.length > MAX_NAME)
                return;
            String name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (!validName(name))
                return;
            accepted.add(new Entry(hash, name));
        }
        for (Entry entry : accepted) {
            String hash = hex(entry.hash);
            if (results.containsKey(hash) || results.size() < MAX_RESULTS)
                results.put(hash, new Cached(entry, now));
        }
        if (more == 1 && page + 1 < MAX_PAGES && items.size() == PAGE_SIZE) {
            request.page++;
            sendRequest(peer, request);
        } else {
            pending.remove(peer);
        }
    }

    private static boolean validName(String name) {
        if (name.trim().length() == 0)
            return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069'))
                return false;
        }
        return true;
    }

    private static int number(Map<String, BEValue> message, String key) throws InvalidBEncodingException {
        Number value = message.get(key).getNumber();
        if (!(value instanceof Integer))
            throw new InvalidBEncodingException("Invalid catalog number");
        return value.intValue();
    }

    private static boolean eligible(Peer peer) {
        return peer.isConnected() && peer.metainfo != null && !peer.metainfo.isPrivate();
    }

    private static int messageID(Peer peer) {
        try {
            Number value = peer.getHandshakeMap().get("m").getMap().get(EXTENSION).getNumber();
            if (!(value instanceof Integer))
                return 0;
            int id = value.intValue();
            return id > 0 && id <= 255 ? id : 0;
        } catch (Exception unsupported) {
            return 0;
        }
    }

    private void sendRequest(Peer peer, Pending request) {
        send(peer, message(0, request.id, request.page));
    }

    private static Map<String, Object> message(int type, byte[] id, int page) {
        Map<String, Object> message = new HashMap<String, Object>();
        message.put("v", Integer.valueOf(1));
        message.put("msg_type", Integer.valueOf(type));
        message.put("id", id);
        message.put("page", Integer.valueOf(page));
        return message;
    }

    private static void send(Peer peer, Map<String, Object> message) {
        byte[] encoded = BEncoder.bencode(message);
        int id = messageID(peer);
        if (id != 0 && encoded.length <= MAX_MESSAGE)
            peer.sendExtension(id, encoded);
    }

    private void clean(long now) {
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
            if (now - it.next().started >= REQUEST_TTL)
                it.remove();
        }
        for (Iterator<Incoming> it = incoming.values().iterator(); it.hasNext(); ) {
            if (now - it.next().started >= 2*REQUEST_TTL)
                it.remove();
        }
        for (Iterator<Cached> it = results.values().iterator(); it.hasNext(); ) {
            if (now - it.next().received >= RESULT_TTL)
                it.remove();
        }
    }

    private static String hex(byte[] hash) {
        return I2PSnarkUtil.toHex(hash).toLowerCase(Locale.US);
    }

    private static final Comparator<Entry> ENTRY_ORDER = new Comparator<Entry>() {
        public int compare(Entry a, Entry b) {
            int result = a.name.compareToIgnoreCase(b.name);
            return result != 0 ? result : hex(a.hash).compareTo(hex(b.hash));
        }
    };

    public static final class Entry {
        private final byte[] hash;
        private final String name;
        Entry(byte[] hash, String name) { this.hash = hash.clone(); this.name = name; }
        public String getName() { return name; }
        public String getInfoHash() { return hex(hash); }
        public String getMagnet() {
            try {
                return MagnetURI.MAGNET_FULL + hex(hash) + "&dn=" + URLEncoder.encode(name, "UTF-8");
            } catch (java.io.UnsupportedEncodingException impossible) {
                throw new AssertionError(impossible);
            }
        }
    }

    private static class Pending {
        final byte[] id;
        final long started;
        int page;
        Pending(byte[] id, long now) { this.id = id.clone(); started = now; }
    }
    private static final class Incoming extends Pending {
        int nextPage;
        Incoming(byte[] id, long now) { super(id, now); }
    }
    private static final class Cached {
        final Entry entry;
        final long received;
        Cached(Entry entry, long now) { this.entry = entry; received = now; }
    }
}
