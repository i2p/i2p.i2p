package i2p.susi.webmail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;

/** Password-wrapped AES-256 data key and authenticated, path-bound local storage. */
final class MailVault implements AutoCloseable {
    static final int MAX_BLOB = 32 * 1024 * 1024;
    private static final int ITERATIONS = 600000;
    private static final byte[] DATA_MAGIC = { 'S', 'M', 'D', 1 };
    private static final byte[] KEY_MAGIC = { 'S', 'M', 'K', 1 };
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<Path, Object>();
    private static final ConcurrentHashMap<Path, java.util.concurrent.atomic.AtomicLong> GENERATIONS = new ConcurrentHashMap<Path, java.util.concurrent.atomic.AtomicLong>();
    final Path directory;
    final Object lock;
    private final String identity;
    private final byte[] key;
    private boolean closed;
    private long usageTime;
    private long savedBytes, pendingBytes;
    private int savedMessages;
    private long generation;

    private MailVault(Path directory, String identity, byte[] key) {
        this.directory = directory;
        this.identity = identity;
        this.key = key;
        lock = LOCKS.computeIfAbsent(directory, ignored -> new Object());
        generation = GENERATIONS.computeIfAbsent(directory, ignored -> new java.util.concurrent.atomic.AtomicLong()).get();
    }

    static Path accountDirectory(I2PAppContext context, String host, int port, String user) throws IOException {
        Path base = context.getConfigDir().toPath().resolve("susimail/cache");
        Path account = base.resolve("cache-" + Base64.encode(user + host + port));
        if (!new SecureDirectory(account.toString()).isDirectory() && !new SecureDirectory(account.toString()).mkdirs())
            throw new IOException("Unable to create local mail storage");
        return account.toAbsolutePath().normalize();
    }

    static boolean exists(Path directory) { return Files.isRegularFile(directory.resolve("vault.key"), LinkOption.NOFOLLOW_LINKS); }

    static boolean hasLegacyMail(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return false;
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.anyMatch(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && (path.getFileName().toString().endsWith(".txt.gz") || path.getFileName().toString().endsWith(".eml")));
        }
    }

    static MailVault open(I2PAppContext context, String host, int port, String user, String password, boolean create) throws IOException {
        if (password == null || password.isEmpty() || password.length() > 1024)
            throw new IOException("Enter a valid local encryption password");
        Path directory = accountDirectory(context, host, port, user);
        String identity = host + '\0' + port + '\0' + user;
        Object lock = LOCKS.computeIfAbsent(directory, ignored -> new Object());
        synchronized (lock) {
            Path manifest = directory.resolve("vault.key");
            Path pending = directory.resolve("vault.pending");
            if (Files.isSymbolicLink(manifest) || Files.isSymbolicLink(pending)) throw new IOException("Linked encryption keys were blocked");
            if (Files.exists(manifest)) {
                MailVault vault = new MailVault(directory, identity, unwrap(manifest, identity, password, (byte) 1));
                try { new AccountSecurity(vault).mfaEnabled(); return vault; }
                catch (IOException failure) { vault.close(); throw failure; }
            }
            byte[] dataKey;
            if (Files.exists(pending)) {
                dataKey = unwrap(pending, identity, password, (byte) 0);
            } else {
                if (!create) throw new IOException("No encrypted mailbox is available to unlock");
                try (Stream<Path> paths = Files.walk(directory)) {
                    for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator)
                        if (isEncrypted(path)) throw new IOException("The local encryption key is missing; existing mail has been preserved");
                }
                dataKey = new byte[32];
                RANDOM.nextBytes(dataKey);
                atomicWrite(pending, wrap(identity, password, dataKey, (byte) 0));
            }
            MailVault vault = new MailVault(directory, identity, dataKey);
            try {
                // Persist the wrapped key before replacing any legacy file. Interrupted migrations resume with that key.
                List<Path> legacy;
                try (Stream<Path> paths = Files.walk(directory)) {
                    if (paths.anyMatch(Files::isSymbolicLink)) throw new IOException("Linked legacy mail storage was blocked");
                }
                try (Stream<Path> paths = Files.walk(directory)) {
                    legacy = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && !path.equals(pending) && !path.equals(manifest)).toList();
                }
                for (Path path : legacy) {
                    if (Files.isSymbolicLink(path)) throw new IOException("Linked mail storage is not supported");
                    if (!isEncrypted(path)) {
                        byte[] content = readBounded(path, MAX_BLOB);
                        try { vault.write(path, content); }
                        finally { Arrays.fill(content, (byte) 0); }
                    }
                }
                if (!Files.exists(directory.resolve("security.profile")))
                    vault.write(directory.resolve("security.profile"), AccountSecurity.initialProfile());
                new AccountSecurity(vault).mfaEnabled();
                atomicWrite(manifest, wrap(identity, password, dataKey, (byte) 1));
                Files.deleteIfExists(pending);
                return vault;
            } catch (IOException failure) {
                vault.close();
                throw failure;
            }
        }
    }

    private static byte[] wrap(String identity, String password, byte[] key, byte state) throws IOException {
        byte[] salt = new byte[16], nonce = new byte[12];
        RANDOM.nextBytes(salt); RANDOM.nextBytes(nonce);
        byte[] header = ByteBuffer.allocate(4 + 1 + 4 + 16 + 12).put(KEY_MAGIC).put(state).putInt(ITERATIONS).put(salt).put(nonce).array();
        byte[] derived = derive(password, salt, ITERATIONS);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, derived, nonce, identity.getBytes(StandardCharsets.UTF_8), header);
            return join(header, cipher.doFinal(key));
        } catch (GeneralSecurityException failure) { throw new IOException("Unable to protect the local encryption key", failure); }
        finally { Arrays.fill(derived, (byte) 0); }
    }

    private static byte[] unwrap(Path file, String identity, String password, byte state) throws IOException {
        byte[] bytes = readBounded(file, 256);
        if (bytes.length != 85) throw new IOException("Invalid local encryption key file");
        ByteBuffer encoded = ByteBuffer.wrap(bytes);
        byte[] magic = new byte[4]; encoded.get(magic);
        byte actualState = encoded.get();
        int iterations = encoded.getInt();
        if (!Arrays.equals(magic, KEY_MAGIC) || actualState != state || iterations < ITERATIONS || iterations > 1000000)
            throw new IOException("Unsupported local encryption key format");
        byte[] salt = new byte[16], nonce = new byte[12];
        encoded.get(salt); encoded.get(nonce);
        byte[] derived = derive(password, salt, iterations);
        try {
            return cipher(Cipher.DECRYPT_MODE, derived, nonce, identity.getBytes(StandardCharsets.UTF_8), Arrays.copyOf(bytes, 37))
                .doFinal(bytes, 37, bytes.length - 37);
        } catch (AEADBadTagException wrongPassword) {
            throw new IOException("The local mailbox could not be unlocked. Check the encryption password.");
        } catch (GeneralSecurityException failure) { throw new IOException("Unable to unlock local mail storage", failure); }
        finally { Arrays.fill(derived, (byte) 0); }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) throws IOException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException failure) { throw new IOException("Password key derivation is unavailable", failure); }
        finally { spec.clearPassword(); }
    }

    void changePassword(String password) throws IOException {
        if (password == null || password.isEmpty() || password.length() > 1024) throw new IOException("Invalid encryption password");
        synchronized (lock) {
            ensureOpen(); atomicWrite(directory.resolve("vault.key"), wrap(identity, password, key, (byte) 1));
            revokeOtherSessions();
        }
    }

    boolean matchesPassword(String password) {
        if (password == null || password.length() > 1024) return false;
        synchronized (lock) {
            byte[] unlocked = null;
            try {
                ensureOpen();
                unlocked = unwrap(directory.resolve("vault.key"), identity, password, (byte) 1);
                return java.security.MessageDigest.isEqual(key, unlocked);
            } catch (IOException invalid) { return false; }
            finally { if (unlocked != null) Arrays.fill(unlocked, (byte) 0); }
        }
    }

    byte[] read(Path file, int maximum) throws IOException {
        synchronized (lock) {
            ensureOpen();
            Path path = checked(file);
            byte[] encoded = readBounded(path, Math.min(maximum, MAX_BLOB) + 32);
            if (encoded.length < 32 || !Arrays.equals(Arrays.copyOf(encoded, 4), DATA_MAGIC))
                throw new IOException("Unencrypted or damaged local mail was blocked");
            byte[] nonce = Arrays.copyOfRange(encoded, 4, 16);
            try {
                byte[] plain = cipher(Cipher.DECRYPT_MODE, key, nonce, binding(path), DATA_MAGIC).doFinal(encoded, 16, encoded.length - 16);
                if (plain.length > maximum) { Arrays.fill(plain, (byte) 0); throw new IOException("Local mail exceeds its read limit"); }
                return plain;
            } catch (GeneralSecurityException altered) { throw new IOException("Local mail integrity check failed. The original file was preserved."); }
        }
    }

    void write(Path file, byte[] plain) throws IOException {
        if (plain.length > MAX_BLOB) throw new IOException("Local mail exceeds its encrypted storage limit");
        synchronized (lock) {
            ensureOpen();
            Path path = checked(file);
            byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
            try {
                byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, key, nonce, binding(path), DATA_MAGIC).doFinal(plain);
                byte[] encoded = join(join(DATA_MAGIC, nonce), ciphertext);
                if (exists(directory) && !path.getFileName().toString().equals("security.profile")) checkQuota(path, encoded.length);
                atomicWrite(path, encoded);
                usageTime = 0;
            } catch (GeneralSecurityException failure) { throw new IOException("Unable to encrypt local mail", failure); }
        }
    }

    private void measureUsage() throws IOException {
        savedBytes = pendingBytes = 0;
        java.util.Set<String> messages = new java.util.HashSet<String>();
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))::iterator) {
                long size = Files.size(path);
                if (path.startsWith(directory.resolve("pending"))) pendingBytes += size;
                else {
                    savedBytes += size;
                    String name = path.toString();
                    if (name.endsWith(".full.txt.gz")) messages.add(name.substring(0, name.length() - 12));
                    else if (name.endsWith(".hdr.txt.gz")) messages.add(name.substring(0, name.length() - 11));
                }
            }
        }
        savedMessages = messages.size(); usageTime = System.nanoTime();
    }
    private void checkQuota(Path path, long size) throws IOException {
        // Recount before a refusal so deleting mail immediately frees capacity.
        if (usageTime == 0 || System.nanoTime() - usageTime > 60000000000L) measureUsage();
        long old = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? Files.size(path) : 0;
        boolean pending = path.startsWith(directory.resolve("pending"));
        boolean full = path.toString().endsWith(".full.txt.gz");
        boolean header = path.toString().endsWith(".hdr.txt.gz");
        boolean newMessage = !Files.exists(path) && (full || header);
        if (newMessage) {
            String alternate = path.toString().replace(full ? ".full.txt.gz" : ".hdr.txt.gz", full ? ".hdr.txt.gz" : ".full.txt.gz");
            newMessage = !Files.exists(Path.of(alternate));
        }
        if ((pending ? pendingBytes : savedBytes) - old + size > (pending ? 32L : 512L) * 1024 * 1024
                || (newMessage && savedMessages >= FetchBudget.MAX_MESSAGES)) {
            measureUsage();
            if ((pending ? pendingBytes : savedBytes) - old + size > (pending ? 32L : 512L) * 1024 * 1024
                    || (newMessage && savedMessages >= FetchBudget.MAX_MESSAGES))
                throw new IOException("Local mail storage is full. Existing messages have been preserved.");
        }
    }
    boolean storageAvailable() {
        synchronized (lock) {
            try { ensureOpen(); measureUsage(); return savedBytes < 512L * 1024 * 1024 && pendingBytes < 32L * 1024 * 1024 && savedMessages < FetchBudget.MAX_MESSAGES; }
            catch (IOException failure) { return false; }
        }
    }

    private byte[] binding(Path file) {
        return (identity + '\0' + directory.relativize(file).toString().replace('\\', '/')).getBytes(StandardCharsets.UTF_8);
    }

    private Path checked(Path file) throws IOException {
        Path path = file.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(directory)) throw new IOException("Linked account storage was blocked");
        if (!path.startsWith(directory) || path.equals(directory)) throw new IOException("Mail storage path is outside this account");
        for (Path parent = path; parent != null && !parent.equals(directory); parent = parent.getParent())
            if (Files.isSymbolicLink(parent)) throw new IOException("Linked mail storage was blocked");
        return path;
    }

    static boolean isEncrypted(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try (InputStream in = Files.newInputStream(path)) { return Arrays.equals(in.readNBytes(4), DATA_MAGIC); }
    }

    static byte[] readBounded(Path path, int limit) throws IOException {
        if (Files.size(path) > limit) throw new IOException("Local file exceeds the safe read limit");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Local file exceeds the safe read limit");
            return bytes;
        }
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, byte[] binding, byte[] header) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(binding); cipher.updateAAD(header);
        return cipher;
    }

    static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (!new SecureDirectory(parent.toString()).isDirectory() && !new SecureDirectory(parent.toString()).mkdirs())
            throw new IOException("Unable to create protected mail storage");
        Path temporary = Files.createTempFile(parent, ".sealed-", ".tmp");
        try {
            try (OutputStream out = new SecureFileOutputStream(temporary.toFile())) { out.write(bytes); }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            // Check the durable temporary file before replacing an existing cache/key.
            try (InputStream in = Files.newInputStream(temporary)) {
                byte[] block = new byte[8192]; int offset = 0, count;
                while ((count = in.read(block)) != -1) {
                    if (offset + count > bytes.length) throw new IOException("Encrypted write verification failed");
                    for (int i = 0; i < count; i++) if (block[i] != bytes[offset + i]) throw new IOException("Encrypted write verification failed");
                    offset += count;
                }
                if (offset != bytes.length) throw new IOException("Encrypted write was incomplete");
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Directory fsync is supported on Unix. Other platforms still get atomic replacement.
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) { directory.force(true); }
            catch (IOException | UnsupportedOperationException unsupportedDirectorySync) { /* Atomic replacement succeeded. */ }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    void revokeOtherSessions() {
        synchronized (lock) { generation = GENERATIONS.computeIfAbsent(directory, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet(); }
    }
    boolean isActive() {
        synchronized (lock) { return !closed && generation == GENERATIONS.get(directory).get(); }
    }
    private void ensureOpen() throws IOException { if (!isActive()) throw new IOException("The local mailbox is locked. Sign in again after the security change."); }
    @Override public void close() { synchronized (lock) { if (!closed) { Arrays.fill(key, (byte) 0); closed = true; } } }
}
