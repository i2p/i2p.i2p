package i2p.susi.webmail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import i2p.susi.util.Buffer;
import i2p.susi.util.MemoryBuffer;
import i2p.susi.util.ReadBuffer;
import i2p.susi.webmail.pop3.POP3MailBox;
import i2p.susi.webmail.smtp.SMTPClient;
import net.i2p.I2PAppContext;
import net.i2p.data.Base64;

/** Synthetic local fixtures: cryptographic interoperability, failure recovery, policy and protocol limits. */
public final class SusiMailMFARegression {
    private static int checks, failures;
    private static final String PASSWORD = "Synthetic password with spaces é 123";
    private static final String HEADERS = "From: sender@example.test\r\nTo: user@mail.i2p\r\nSubject: ordinary message\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n";
    private static I2PAppContext context;

    private static void check(boolean ok, String reason) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL: " + reason); }
    }
    private interface Attempt { void run() throws Exception; }
    private static void rejects(Attempt action, String reason) throws Exception {
        boolean rejected = false;
        try { action.run(); }
        catch (IOException expected) { rejected = true; }
        catch (InvocationTargetException expected) { if (!(expected.getCause() instanceof IOException)) throw expected; rejected = true; }
        check(rejected, reason);
    }
    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args); method.setAccessible(true); return method;
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static ReadBuffer buffer(String raw) { byte[] bytes = raw.getBytes(StandardCharsets.UTF_8); return new ReadBuffer(bytes, 0, bytes.length); }
    private static MailVault vault(String user) throws IOException { return MailVault.open(context, "mail.example.test", 110, user, PASSWORD, true); }
    private static VaultFileBuffer sealed(MailVault vault, Path path, boolean gzip, String raw) throws IOException {
        VaultFileBuffer buffer = new VaultFileBuffer(vault, path.toFile(), gzip);
        try (OutputStream out = buffer.getOutputStream()) { out.write(raw.getBytes(StandardCharsets.UTF_8)); }
        buffer.writeComplete(true);
        if (!buffer.isCommitted()) throw new IOException("Fixture could not be saved");
        return buffer;
    }
    private static byte[] unwrapIndependently(Path path, String user) throws Exception {
        byte[] bytes = Files.readAllBytes(path.resolve("vault.key"));
        int iterations = ByteBuffer.wrap(bytes, 5, 4).getInt();
        check(iterations >= 600000, "password wrapping uses the configured PBKDF2 work factor");
        PBEKeySpec spec = new PBEKeySpec(PASSWORD.toCharArray(), Arrays.copyOfRange(bytes, 9, 25), iterations, 256);
        byte[] wrappingKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); spec.clearPassword();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(wrappingKey, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 25, 37)));
        cipher.updateAAD(("mail.example.test\0" + 110 + '\0' + user).getBytes(StandardCharsets.UTF_8));
        cipher.updateAAD(Arrays.copyOf(bytes, 37));
        byte[] key = cipher.doFinal(bytes, 37, bytes.length - 37); Arrays.fill(wrappingKey, (byte) 0); return key;
    }

    private static void encryption() throws Exception {
        MailVault vault = vault("crypto");
        Path file = vault.directory.resolve("sample.bin"), other = vault.directory.resolve("other.bin");
        byte[] plain = "PRIVATE BODY AND ATTACHMENT\0utf8 é".getBytes(StandardCharsets.UTF_8);
        vault.write(file, plain);
        byte[] first = Files.readAllBytes(file), key = unwrapIndependently(vault.directory, "crypto");
        check(key.length == 32, "wrapped data key is 256 bits");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(first, 4, 16)));
        cipher.updateAAD(("mail.example.test\0" + 110 + "\0crypto\0sample.bin").getBytes(StandardCharsets.UTF_8));
        cipher.updateAAD(new byte[] {'S','M','D',1});
        check(Arrays.equals(plain, cipher.doFinal(first, 16, first.length - 16)), "independent JDK AES-GCM decrypts path-bound stored content");
        check(!new String(first, StandardCharsets.ISO_8859_1).contains("PRIVATE BODY"), "disk content contains no plaintext body");
        vault.write(file, plain);
        check(!Arrays.equals(first, Files.readAllBytes(file)), "repeated writes use fresh nonces");
        Files.copy(file, other); rejects(() -> vault.read(other, 1024), "ciphertext cannot be transplanted to another path");
        byte[] original = Files.readAllBytes(file), changed = original.clone(); changed[changed.length - 1] ^= 1; Files.write(file, changed);
        rejects(() -> vault.read(file, 1024), "modified authentication tag is rejected before plaintext is returned");
        Files.write(file, original);
        rejects(() -> vault.read(file, 4), "bounded reads refuse an oversized plaintext");
        rejects(() -> vault.read(vault.directory.getParent().resolve("outside"), 1024), "account path traversal is rejected");
        rejects(() -> MailVault.open(context, "mail.example.test", 110, "crypto", "wrong password", false), "wrong password cannot unlock or reset an existing key");
        check(Arrays.equals(original, Files.readAllBytes(file)), "failed unlock leaves cached data intact");
        Path linked = vault.directory.resolve("linked.bin"); Files.createSymbolicLink(linked, file);
        rejects(() -> vault.read(linked, 1024), "symbolic mail links are rejected"); Files.delete(linked);
        VaultFileBuffer cache = sealed(vault, vault.directory.resolve("message.gz"), true, HEADERS + "cached body");
        byte[] previous = Files.readAllBytes(cache.getFile().toPath());
        VaultFileBuffer failed = new VaultFileBuffer(vault, cache.getFile(), true);
        failed.getOutputStream().write("incomplete replacement".getBytes(StandardCharsets.UTF_8));
        failed.getOutputStream().close();
        check(Arrays.equals(previous, Files.readAllBytes(cache.getFile().toPath())), "closing a staged stream does not replace a cached message");
        failed.writeComplete(false);
        check(!failed.isCommitted() && Arrays.equals(previous, Files.readAllBytes(cache.getFile().toPath())), "failed completion preserves previous ciphertext");
        try (InputStream a = cache.getInputStream(); InputStream b = cache.getInputStream()) {
            check(a.read() == 'F' && b.read() == 'F' && a.read() == 'r', "simultaneous authenticated readers have independent positions");
        }
        MailVault second = MailVault.open(context, "mail.example.test", 110, "crypto", PASSWORD, false);
        byte[] manifest = Files.readAllBytes(vault.directory.resolve("vault.key"));
        vault.changePassword("A replacement password 123");
        check(!second.isActive() && vault.isActive(), "password changes revoke other open vault sessions");
        check(!Arrays.equals(manifest, Files.readAllBytes(vault.directory.resolve("vault.key"))) && Arrays.equals(original, Files.readAllBytes(file)), "password change rewraps the key without rewriting message ciphertext");
        rejects(() -> MailVault.open(context, "mail.example.test", 110, "crypto", PASSWORD, false), "old encryption password stops unlocking the key");
        try (MailVault reopened = MailVault.open(context, "mail.example.test", 110, "crypto", "A replacement password 123", false)) {
            check(Arrays.equals(plain, reopened.read(file, 1024)), "new password opens the original cached contents");
        }
        Path quota = vault.directory.resolve("quota-fixture");
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(quota, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(513L * 1024 * 1024); channel.write(ByteBuffer.wrap(new byte[1]));
        }
        check(!vault.storageAvailable(), "storage accounting refuses an account beyond its quota");
        rejects(() -> vault.write(file, plain), "quota refusal prevents replacement while preserving original data");
        check(Arrays.equals(original, Files.readAllBytes(file)), "quota failures preserve the existing ciphertext");
        Files.delete(quota); check(vault.storageAvailable(), "deleting data immediately frees quota capacity");
        vault.close(); second.close(); Arrays.fill(key, (byte) 0);
        rejects(() -> vault.read(file, 1024), "closed sessions cannot decrypt cache data");
    }

    private static void migration() throws Exception {
        Path directory = MailVault.accountDirectory(context, "mail.example.test", 110, "legacy");
        Path old = directory.resolve("cur/sA/mail-legacy.full.txt.gz"); Files.createDirectories(old.getParent());
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(old))) { out.write((HEADERS + "LEGACY BODY").getBytes(StandardCharsets.UTF_8)); }
        Path attachment = directory.resolve("Drafts/attachments/legacy"); Files.createDirectories(attachment.getParent()); Files.writeString(attachment, "LEGACY ATTACHMENT");
        Path tooLarge = directory.resolve("oversized");
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(tooLarge, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(MailVault.MAX_BLOB); channel.write(ByteBuffer.wrap(new byte[1]));
        }
        rejects(() -> vault("legacy"), "interrupted migration refuses an over-limit legacy file");
        check(Files.exists(directory.resolve("vault.pending")) && !Files.exists(directory.resolve("vault.key")), "migration persists a resumable key before committing the final manifest");
        Files.delete(tooLarge);
        try (MailVault resumed = vault("legacy")) {
            check(MailVault.isEncrypted(old) && MailVault.isEncrypted(attachment), "resumed migration encrypts mail and existing draft attachments");
            VaultFileBuffer mail = new VaultFileBuffer(resumed, old.toFile(), true);
            try (InputStream in = mail.getInputStream()) { check(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("LEGACY BODY"), "legacy compressed mail survives migration intact"); }
            check(new String(resumed.read(attachment, 1024), StandardCharsets.UTF_8).equals("LEGACY ATTACHMENT"), "legacy attachment survives migration intact");
        }
        check(!Files.exists(directory.resolve("vault.pending")), "successful migration removes only the pending key");
        Files.delete(directory.resolve("vault.key"));
        rejects(() -> vault("legacy"), "missing keys never cause encrypted mail to be overwritten with a new key");
        check(Files.exists(old), "missing-key refusal preserves the encrypted message");
        MailVault damaged = vault("damaged-profile"); byte[] profile = Files.readAllBytes(damaged.directory.resolve("security.profile")); damaged.close();
        profile[profile.length - 1] ^= 1; Files.write(damaged.directory.resolve("security.profile"), profile);
        rejects(() -> vault("damaged-profile"), "tampered account security profile fails closed at unlock");
    }

    private static void authenticator() throws Exception {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        long[] times = {59,1111111109L,1111111111L,1234567890L,2000000000L,20000000000L};
        String[] vectors = {"94287082","07081804","14050471","89005924","69279037","65353130"};
        for (int i = 0; i < times.length; i++) check(AccountSecurity.hotp(secret, times[i] / 30, 8).equals(vectors[i]), "RFC 6238 SHA-1 test vector " + i);
        check(AccountSecurity.base32(secret).equals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"), "manual enrollment uses standard Base32");
        MailVault vault = vault("mfa"); AccountSecurity account = new AccountSecurity(vault);
        check(!account.mfaEnabled() && account.verifyLogin(null, 59000), "TOTP is optional and off by default");
        rejects(() -> account.enable(secret, "000000", 59000), "enrollment requires a valid current code");
        check(!account.mfaEnabled(), "failed enrollment leaves optional protection off");
        MailVault oldSession = MailVault.open(context, "mail.example.test", 110, "mfa", PASSWORD, false);
        List<String> recovery = account.enable(secret, "287082", 59000);
        check(account.mfaEnabled() && recovery.size() == 8 && new HashSet<String>(recovery).size() == 8, "confirmed enrollment returns eight distinct recovery codes");
        check(!oldSession.isActive(), "enabling MFA revokes other open sessions"); oldSession.close();
        check(!account.verifyLogin("287082", 59000), "accepted authenticator codes cannot be replayed");
        check(!account.verifyLogin(null, 59000), "enabled protection refuses a missing code");
        check(account.verifyLogin(recovery.get(0), 60000) && account.recoveryRemaining() == 7, "a recovery code authenticates once and is consumed");
        check(!account.verifyLogin(recovery.get(0), 60000), "consumed recovery codes cannot be reused");
        AtomicInteger accepted = new AtomicInteger(); CountDownLatch ready = new CountDownLatch(1); List<Thread> workers = new ArrayList<Thread>();
        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> { try { ready.await(); if (account.verifyLogin(recovery.get(1), 61000)) accepted.incrementAndGet(); } catch (Exception failure) { throw new RuntimeException(failure); } });
            workers.add(thread); thread.start();
        }
        ready.countDown(); for (Thread thread : workers) thread.join();
        check(accepted.get() == 1 && account.recoveryRemaining() == 6, "concurrent sessions cannot spend the same recovery code twice");
        byte[] ciphertext = Files.readAllBytes(vault.directory.resolve("security.profile")); String disk = new String(ciphertext, StandardCharsets.ISO_8859_1);
        check(!disk.contains(AccountSecurity.base32(secret)) && !disk.contains(java.util.Base64.getEncoder().encodeToString(secret)) && recovery.stream().noneMatch(disk::contains), "TOTP secrets and recovery codes are absent from plaintext storage");
        List<String> replacement = account.regenerate(recovery.get(2), 62000);
        check(account.recoveryRemaining() == 8 && !account.verifyLogin(recovery.get(3), 62000), "regeneration invalidates every old recovery code");
        for (int i = 0; i < 5; i++) account.verifyLogin("invalid", 63000);
        check(!account.verifyLogin(replacement.get(0), 63000), "repeated failures temporarily lock even a correct code");
        check(account.verifyLogin(replacement.get(0), 400000), "temporary lockout expires without losing unused recovery codes");
        rejects(() -> account.disable("invalid", 401000), "disabling optional TOTP requires a valid second factor");
        account.disable(replacement.get(1), 402000);
        check(!account.mfaEnabled() && account.recoveryRemaining() == 0 && account.verifyLogin(null, 402000), "disable clears secret/recovery state and restores optional password-only login");
        vault.close();
    }

    

    private static final class Wire extends Socket {
        private InputStream incoming;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        Wire(String reply) { incoming = new ByteArrayInputStream(reply.getBytes(StandardCharsets.US_ASCII)); }
        @Override public InputStream getInputStream() { return incoming; }
        @Override public OutputStream getOutputStream() { return sent; }
        @Override public boolean isConnected() { return true; }
        @Override public synchronized int getSoTimeout() { return 120000; }
        @Override public synchronized void setSoTimeout(int timeout) {}
    }
    private static void protocolLimits() throws Exception {
        POP3MailBox pop = new POP3MailBox("unused", 110, "synthetic", "synthetic");
        Method body = method(POP3MailBox.class, "getResultNa", Buffer.class), listing = method(POP3MailBox.class, "getResultNl");
        MemoryBuffer buffer = new MemoryBuffer(); set(pop, "socket", new Wire("first\r\n\r\n..stuffed\r\n.\r\n")); body.invoke(pop, buffer);
        check(new String(buffer.getContent(), StandardCharsets.US_ASCII).equals("first\r\n\r\n.stuffed\r\n"), "complete POP3 messages preserve blank lines and remove dot stuffing");
        MemoryBuffer missing = new MemoryBuffer(); set(pop, "socket", new Wire("truncated\r\n"));
        rejects(() -> body.invoke(pop, missing), "POP3 EOF cannot be mistaken for successful delivery");
        check(missing.getContent() == null, "truncated POP3 data is not committed");
        set(pop, "socket", new Wire("\r\n.\r\n")); body.invoke(pop, new MemoryBuffer()); check(true, "empty message lines do not terminate a POP3 response");
        set(pop, "socket", new Wire("x".repeat(1000).concat("\r\n").repeat(300) + ".\r\n"));
        rejects(() -> body.invoke(pop, new MemoryBuffer()), "POP3 header buffers are capped before excessive allocation");
        set(pop, "socket", new Wire("1 one\r\n")); rejects(() -> listing.invoke(pop), "mail listing requires a final dot terminator");
        set(pop, "socket", new Wire("1 one\r\n".repeat(50001) + ".\r\n")); rejects(() -> listing.invoke(pop), "mail listing row count is bounded");
        set(pop, "connected", true);
        method(POP3MailBox.class, "updateUIDLs", List.class).invoke(pop, List.of("1 one\r", "-1 negative\r", "2 bad uidl\r", "3 one\r", "1 duplicate-id\r", "4 " + "x".repeat(71)));
        check(pop.getUIDLs().equals(Set.of("one")) || new HashSet<String>(pop.getUIDLs()).equals(Set.of("one")), "UIDL mapping rejects negative IDs, duplicate IDs, controls, spaces and overlong identities");
        SMTPClient smtp = new SMTPClient();
        set(smtp, "socket", new Wire("250-partial\r\n"));
        check((Integer) method(SMTPClient.class, "getResult").invoke(smtp) == 0, "truncated SMTP continuation cannot become success");
        set(smtp, "socket", new Wire("250-continuation\r\n".repeat(101) + "250 final\r\n"));
        check((Integer) method(SMTPClient.class, "getResult").invoke(smtp) == 0, "SMTP continuation count is bounded");
        set(smtp, "socket", new Wire("250-first status\r\n550 delivery refused\r\n"));
        check((Integer) method(SMTPClient.class, "getResult").invoke(smtp) == 0, "different SMTP final status cannot report false delivery success");
        Path account = Files.createTempDirectory("fetch-budget"); FetchBudget budget = FetchBudget.forAccount(account);
        check(budget == FetchBudget.forAccount(account), "download budget is shared between sessions for one account");
        for (int i = 0; i < 100; i++) if (!budget.message()) throw new AssertionError("early budget refusal");
        check(!budget.message(), "account message attempts are rate limited");
        set(budget, "window", System.nanoTime() - 61000000000L); check(budget.message(), "download rate window recovers automatically");
        budget.received(32 * 1024 * 1024); rejects(() -> budget.received(1), "actual downloaded bytes are capped independently of advertised sizes");
        Wire timed = new Wire("complete");
        i2p.susi.util.TimedResponseInputStream deadline = new i2p.susi.util.TimedResponseInputStream(timed, 1000);
        check(deadline.read() == 'c', "bounded protocol stream preserves reply bytes");
        Field end = deadline.getClass().getDeclaredField("deadline"); end.setAccessible(true); end.setLong(deadline, System.nanoTime() - 1);
        rejects(() -> deadline.read(), "absolute protocol deadlines stop partial-line trickles");
        pop.destroy();
    }

    private static void draftsAndCache() throws Exception {
        MailVault vault = vault("drafts");
        MailCache drafts = new MailCache(context, null, WebMail.DIR_DRAFTS, "mail.example.test", 110, "drafts", PASSWORD, vault);
        Path attachment = drafts.getAttachmentDir().toPath().resolve("fixture"); sealed(vault, attachment, false, "ATTACHMENT DATA");
        String attachmentHeader = "x-i2p-attachment: " + Base64.encode("report.txt") + ",text/plain,base64," + Base64.encode(attachment.toString()) + "\r\n";
        String raw = "From: user@mail.i2p\r\nTo: recipient@mail.i2p\r\nBcc: hidden@mail.i2p\r\n" + attachmentHeader + "Content-Type: text/plain; charset=UTF-8\r\n\r\nDraft body";
        Buffer written = drafts.getFullWriteBuffer("draft-one"); try (OutputStream out = written.getOutputStream()) { out.write(raw.getBytes(StandardCharsets.UTF_8)); }
        check(drafts.writeComplete("draft-one", written, true), "normal draft save commits encrypted content");
        Draft draft = (Draft) drafts.getMail("draft-one", MailCache.FetchMode.CACHE_ONLY);
        check(draft.getBcc() != null && Arrays.asList(draft.getBcc()).contains("<hidden@mail.i2p>"), "Bcc survives lowercase decoded field names and reopening");
        check(draft.getAttachments().size() == 1 && draft.getAttachments().get(0).getSize() == 15, "attachments survive ordinary header ordering and encrypted reopening");
        try (InputStream in = draft.getAttachments().get(0).getData()) { check(new String(in.readAllBytes(), StandardCharsets.UTF_8).equals("ATTACHMENT DATA"), "outgoing attachment reader transparently decrypts its bytes"); }
        draft.setBody(draft.getBody()); check(draft.getAttachments().size() == 1, "draft reparsing does not duplicate attachments");
        MailCache sent = new MailCache(context, null, WebMail.DIR_SENT, "mail.example.test", 110, "drafts", PASSWORD, vault);
        Buffer saved = sent.getFullWriteBuffer("sent-one"); try (OutputStream out = saved.getOutputStream()) { out.write((HEADERS + "Sent copy").getBytes(StandardCharsets.UTF_8)); }
        check(sent.writeComplete("sent-one", saved, true), "normal Sent copy commits encrypted mail");
        PersistentMailCache disk = new PersistentMailCache(context, "mail.example.test", 110, "drafts", PASSWORD, WebMail.DIR_SENT, vault);
        check(MailVault.isEncrypted(disk.getHeaderFile("sent-one").toPath()), "a small encrypted preview accompanies the full message");
        check(disk.getMails().size() == 1, "startup does not duplicate messages with companion previews");
        MailCache trash = new MailCache(context, null, "Trash", "mail.example.test", 110, "drafts", PASSWORD, vault);
        check(sent.moveTo("sent-one", trash), "moving mail re-encrypts it for the destination path");
        check(trash.getMail("sent-one", MailCache.FetchMode.CACHE_ONLY).hasBody() && !disk.getFullFile("sent-one").exists() && !disk.getHeaderFile("sent-one").exists(), "successful move keeps the readable destination and removes both source files");
        check(trash.getMail("unknown", MailCache.FetchMode.CACHE_ONLY) == null, "cache-only requests never create untrusted message placeholders");
        vault.close();
    }

    private static void composeValidation() throws Exception {
        Class<?> sessionType = Class.forName("i2p.susi.webmail.WebMail$SessionObject");
        java.lang.reflect.Constructor<?> constructor = sessionType.getDeclaredConstructor(net.i2p.util.Log.class); constructor.setAccessible(true);
        Object session = constructor.newInstance(context.logManager().getLog(WebMail.class)); set(session, "user", "synthetic");
        Method compose = method(WebMail.class, "composeDraft", sessionType, String.class, String.class, String.class, String.class, String.class, String.class, List.class);
        for (int field = 0; field < 5; field++) {
            Object[] fields = {session,"sender@mail.i2p","recipient@mail.i2p","","","ordinary subject","ordinary body",null};
            fields[field + 1] = "value\r\nBcc: injected@example.test";
            check(compose.invoke(null, fields) == null, "composer rejects header injection in identity field " + field);
        }
        check(compose.invoke(null,session,"sender@mail.i2p","recipient@mail.i2p","","","ordinary subject","",null) != null, "ordinary empty-body drafts remain supported");
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("susimail-protection-"); Properties properties = new Properties();
        properties.setProperty("i2p.dir.config", temporary.toString()); properties.setProperty("i2p.dir.base", temporary.toString());
        context = new I2PAppContext(properties);
        encryption(); migration(); authenticator(); protocolLimits(); draftsAndCache(); composeValidation();
        System.out.println("SUSIMAIL_MFA_CHECKS=" + checks + "; FAILURES=" + failures);
        if (failures != 0) System.exit(1);
    }
}
