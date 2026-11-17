package i2p.susi.webmail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Account policy and optional local MFA, always encrypted and updated under the account lock. */
final class AccountSecurity {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_PROFILE = 256 * 1024;
    private final MailVault vault;
    private static final java.util.concurrent.ConcurrentHashMap<java.nio.file.Path, java.util.concurrent.atomic.AtomicLong> REVISIONS = new java.util.concurrent.ConcurrentHashMap<java.nio.file.Path, java.util.concurrent.atomic.AtomicLong>();

    AccountSecurity(MailVault vault) { this.vault = vault; }

    static byte[] initialProfile() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("mfa.enabled", "false");
        properties.setProperty("spam.enabled", "true");
        properties.setProperty("spam.threshold", "7");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        properties.store(out, null);
        return out.toByteArray();
    }

    private Properties load() throws IOException {
        byte[] plain = vault.read(vault.directory.resolve("security.profile"), MAX_PROFILE);
        try {
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(plain));
            if (!"1".equals(properties.getProperty("version"))) throw new IOException("Unsupported account security profile");
            return properties;
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    private void save(Properties properties) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        properties.store(out, null);
        if (out.size() > MAX_PROFILE) throw new IOException("The account policy limit has been reached");
        byte[] plain = out.toByteArray();
        try {
            vault.write(vault.directory.resolve("security.profile"), plain);
            REVISIONS.computeIfAbsent(vault.directory, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    boolean mfaEnabled() throws IOException {
        synchronized (vault.lock) { return Boolean.parseBoolean(load().getProperty("mfa.enabled", "false")); }
    }

    boolean verifyLogin(String code, long now) throws IOException {
        synchronized (vault.lock) {
            Properties properties = load();
            if (!Boolean.parseBoolean(properties.getProperty("mfa.enabled", "false"))) return true;
            boolean accepted = accept(properties, code, now);
            save(properties);
            return accepted;
        }
    }

    private boolean accept(Properties properties, String code, long now) throws IOException {
        long lockedUntil = number(properties, "mfa.lockedUntil", 0);
        if (now < lockedUntil) return false;
        boolean accepted = false;
        if (code != null && code.matches("[0-9]{6}")) {
            byte[] secret;
            try { secret = Base64.getDecoder().decode(properties.getProperty("mfa.secret", "")); }
            catch (IllegalArgumentException invalid) { throw new IOException("The two-factor profile is damaged"); }
            if (secret.length != 20) throw new IOException("The two-factor secret is damaged");
            long last = number(properties, "mfa.lastCounter", -1);
            for (long counter = now / 30000 - 1; counter <= now / 30000 + 1; counter++) {
                if (counter >= 0 && counter > last && equal(code, hotp(secret, counter, 6))) {
                    properties.setProperty("mfa.lastCounter", Long.toString(counter));
                    accepted = true;
                    break;
                }
            }
            Arrays.fill(secret, (byte) 0);
        } else if (code != null && code.matches("[A-Fa-f0-9-]{32,40}")) {
            String normalized = code.replace("-", "").toLowerCase(java.util.Locale.US);
            if (normalized.length() == 32) {
                String digest = recoveryHash(normalized);
                List<String> hashes = new ArrayList<String>(List.of(properties.getProperty("mfa.recovery", "").split(",")));
                for (int i = 0; i < hashes.size(); i++) {
                    if (equal(hashes.get(i), digest)) {
                        hashes.remove(i);
                        properties.setProperty("mfa.recovery", String.join(",", hashes));
                        accepted = true;
                        break;
                    }
                }
            }
        }
        if (accepted) {
            properties.setProperty("mfa.failures", "0");
            properties.remove("mfa.lockedUntil");
        } else {
            long failures = Math.min(20, number(properties, "mfa.failures", 0) + 1);
            properties.setProperty("mfa.failures", Long.toString(failures));
            if (failures >= 5) properties.setProperty("mfa.lockedUntil", Long.toString(now + Math.min(300000, 30000L * (failures - 4))));
        }
        return accepted;
    }

    List<String> enable(byte[] secret, String code, long now) throws IOException {
        if (secret == null || secret.length != 20 || code == null || !code.matches("[0-9]{6}"))
            throw new IOException("Enter the six-digit code from your authenticator");
        synchronized (vault.lock) {
            Properties properties = load();
            if (Boolean.parseBoolean(properties.getProperty("mfa.enabled", "false"))) throw new IOException("Two-factor protection is already enabled");
            properties.setProperty("mfa.secret", Base64.getEncoder().encodeToString(secret));
            properties.setProperty("mfa.enabled", "true");
            properties.setProperty("mfa.lastCounter", "-1");
            if (!accept(properties, code, now)) throw new IOException("The authenticator code did not match");
            List<String> codes = recoveryCodes(properties);
            save(properties);
            vault.revokeOtherSessions();
            return codes;
        }
    }

    List<String> regenerate(String code, long now) throws IOException {
        synchronized (vault.lock) {
            Properties properties = load();
            if (!Boolean.parseBoolean(properties.getProperty("mfa.enabled", "false"))) throw new IOException("Enable two-factor protection first");
            if (!accept(properties, code, now)) { save(properties); throw new IOException("The authenticator or recovery code was not accepted"); }
            List<String> codes = recoveryCodes(properties);
            save(properties);
            vault.revokeOtherSessions();
            return codes;
        }
    }

    void disable(String code, long now) throws IOException {
        synchronized (vault.lock) {
            Properties properties = load();
            if (!Boolean.parseBoolean(properties.getProperty("mfa.enabled", "false"))) throw new IOException("Two-factor protection is not enabled");
            if (!accept(properties, code, now)) { save(properties); throw new IOException("The authenticator or recovery code was not accepted"); }
            properties.setProperty("mfa.enabled", "false");
            properties.remove("mfa.secret"); properties.remove("mfa.recovery"); properties.remove("mfa.lastCounter");
            save(properties);
            vault.revokeOtherSessions();
        }
    }

    private List<String> recoveryCodes(Properties properties) throws IOException {
        List<String> codes = new ArrayList<String>(), hashes = new ArrayList<String>();
        for (int i = 0; i < 8; i++) {
            byte[] random = new byte[16]; RANDOM.nextBytes(random);
            String raw = java.util.HexFormat.of().formatHex(random);
            hashes.add(recoveryHash(raw));
            codes.add(raw.substring(0, 8) + '-' + raw.substring(8, 16) + '-' + raw.substring(16, 24) + '-' + raw.substring(24));
            Arrays.fill(random, (byte) 0);
        }
        properties.setProperty("mfa.recovery", String.join(",", hashes));
        return codes;
    }

    int recoveryRemaining() throws IOException {
        synchronized (vault.lock) {
            String value = load().getProperty("mfa.recovery", "");
            return value.isEmpty() ? 0 : value.split(",").length;
        }
    }

    static byte[] newSecret() { byte[] secret = new byte[20]; RANDOM.nextBytes(secret); return secret; }

    static String hotp(byte[] secret, long counter, int digits) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = digest[digest.length - 1] & 15;
            int binary = ((digest[offset] & 127) << 24) | ((digest[offset + 1] & 255) << 16)
                | ((digest[offset + 2] & 255) << 8) | (digest[offset + 3] & 255);
            int modulus = digits == 8 ? 100000000 : 1000000;
            return String.format(java.util.Locale.US, "%0" + digits + "d", binary % modulus);
        } catch (GeneralSecurityException failure) { throw new IOException("The authenticator algorithm is unavailable", failure); }
    }

    static String base32(byte[] secret) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder result = new StringBuilder();
        int accumulator = 0, bits = 0;
        for (byte value : secret) {
            accumulator = (accumulator << 8) | (value & 255); bits += 8;
            while (bits >= 5) { bits -= 5; result.append(alphabet.charAt((accumulator >>> bits) & 31)); }
        }
        if (bits > 0) result.append(alphabet.charAt((accumulator << (5 - bits)) & 31));
        return result.toString();
    }

    List<String> blockedSenders() throws IOException {
        synchronized (vault.lock) { return new ArrayList<String>(blocked(load())); }
    }

    void block(String value, boolean add) throws IOException {
        String entry = normalizeBlock(value);
        synchronized (vault.lock) {
            Properties properties = load();
            TreeSet<String> blocked = blocked(properties);
            if (add && blocked.size() >= 5000 && !blocked.contains(entry)) throw new IOException("The blocklist limit has been reached");
            if (add) blocked.add(entry); else blocked.remove(entry);
            properties.setProperty("blocked", String.join("\n", blocked));
            save(properties);
        }
    }

    boolean isBlocked(String address) throws IOException {
        SenderIdentity identity = SenderIdentity.parse(address);
        if (identity == null) return false;
        synchronized (vault.lock) {
            TreeSet<String> blocked = blocked(load());
            return blocked.contains(identity.address.toLowerCase(java.util.Locale.US)) || blocked.contains('@' + identity.domain);
        }
    }

    private static TreeSet<String> blocked(Properties properties) {
        TreeSet<String> blocked = new TreeSet<String>();
        for (String entry : properties.getProperty("blocked", "").split("\n")) if (!entry.isEmpty()) blocked.add(entry);
        return blocked;
    }

    private static String normalizeBlock(String input) throws IOException {
        if (input == null || input.length() > 254 || SenderIdentity.hasInvisible(input)) throw new IOException("Enter one email address or @domain");
        input = input.trim().toLowerCase(java.util.Locale.US);
        if (input.startsWith("@")) {
            String domain = input.substring(1);
            if (!SenderIdentity.validDomain(domain)) throw new IOException("Enter a valid @domain");
            return input;
        }
        SenderIdentity identity = SenderIdentity.parse(input);
        if (identity == null) throw new IOException("Enter one complete email address");
        return identity.address.toLowerCase(java.util.Locale.US);
    }

    boolean spamEnabled() throws IOException { synchronized (vault.lock) { return Boolean.parseBoolean(load().getProperty("spam.enabled", "true")); } }
    int spamThreshold() throws IOException { synchronized (vault.lock) { return (int) Math.max(3, Math.min(20, number(load(), "spam.threshold", 7))); } }
    void spamSettings(boolean enabled, int threshold) throws IOException {
        if (threshold < 3 || threshold > 20) throw new IOException("Choose a spam threshold between 3 and 20");
        synchronized (vault.lock) {
            Properties properties = load();
            properties.setProperty("spam.enabled", Boolean.toString(enabled));
            properties.setProperty("spam.threshold", Integer.toString(threshold));
            save(properties);
        }
    }

    String revision() throws IOException {
        java.nio.file.Path profile = vault.directory.resolve("security.profile");
        return Files.size(profile) + ":" + Files.getLastModifiedTime(profile).toMillis() + ":"
            + REVISIONS.computeIfAbsent(vault.directory, ignored -> new java.util.concurrent.atomic.AtomicLong()).get();
    }

    boolean isReleased(String uidl) throws IOException {
        synchronized (vault.lock) { return Boolean.parseBoolean(load().getProperty(releaseKey(uidl), "false")); }
    }
    void release(String uidl) throws IOException {
        synchronized (vault.lock) {
            Properties properties = load();
            if (properties.size() >= 6000) throw new IOException("The saved mail-policy limit has been reached");
            properties.setProperty(releaseKey(uidl), "true"); save(properties);
        }
    }
    private static String releaseKey(String uidl) throws IOException {
        try { return "allow." + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(uidl.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException failure) { throw new IOException("Local policy hashing is unavailable", failure); }
    }

    private static boolean equal(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII), second.getBytes(StandardCharsets.US_ASCII));
    }
    private static String recoveryHash(String code) throws IOException {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(("SusiMail recovery v1\0" + code).getBytes(StandardCharsets.US_ASCII))); }
        catch (GeneralSecurityException failure) { throw new IOException("Recovery code hashing is unavailable", failure); }
    }
    private static long number(Properties properties, String key, long fallback) {
        try { return Long.parseLong(properties.getProperty(key, Long.toString(fallback))); }
        catch (NumberFormatException invalid) { return fallback; }
    }
}
