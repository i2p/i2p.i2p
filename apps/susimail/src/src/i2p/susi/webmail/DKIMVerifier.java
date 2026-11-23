package i2p.susi.webmail;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import i2p.susi.util.Buffer;
import i2p.susi.util.Config;
import i2p.susi.util.EOFOnMatchInputStream;

/**
 * Local DKIM verification with explicitly pinned domain keys and no network lookups.
 * Supports rsa-sha256, simple/relaxed canonicalization, and complete-body signatures.
 * This deliberately requires strict From-domain alignment and signed presentation headers.
 */
final class DKIMVerifier {
    enum State { UNVERIFIED, VERIFIED, FAILED }
    static final class Result {
        final State state;
        final String domain;
        final String reason;
        long validUntil = Long.MAX_VALUE;
        Result(State state, String domain, String reason) {
            this.state = state;
            this.domain = domain;
            this.reason = reason;
        }
    }
    static final Result UNVERIFIED = new Result(State.UNVERIFIED, "", "No signature verified with a trusted key.");
    private static final int MAX_SIGNATURES = 4;
    private static final long MAX_BODY = 64L * 1024 * 1024;
    private static final int MAX_BODY_LINE = 1024 * 1024;
    private static final Set<String> PROTECTED = Set.of("from", "sender", "reply-to", "subject", "to", "cc",
        "date", "message-id", "mime-version", "content-type", "content-transfer-encoding");

    static Result verify(Buffer buffer, MailHeaders expected, SenderIdentity from, long nowSeconds, boolean enforceRequired) {
        if (from == null || expected == null || expected.ambiguousField() != null) return UNVERIFIED;
        List<Candidate> candidates = new ArrayList<Candidate>();
        Result failure = UNVERIFIED;
        int count = 0;
        for (MailHeaders.Field field : expected.fields) {
            if (!field.name.equals("dkim-signature")) continue;
            if (++count > MAX_SIGNATURES) break;
            try {
                Candidate candidate = new Candidate(field, expected, from, nowSeconds);
                if (candidate.key != null) candidates.add(candidate);
            } catch (GeneralSecurityException | IllegalArgumentException invalid) {
                // A forged or unsupported signature must never turn an unverified message green.
            }
        }
        if (candidates.isEmpty()) {
            if (enforceRequired && Boolean.parseBoolean(Config.getProperty("dkim.require." + from.domain, "false")))
                return new Result(State.FAILED, from.domain, "This sender domain requires a valid signature from a trusted key.");
            return UNVERIFIED;
        }
        try (InputStream in = new BufferedInputStream(buffer.getInputStream())) {
            EOFOnMatchInputStream headerStream = new EOFOnMatchInputStream(in, Mail.HEADER_MATCH);
            MailHeaders actual = MailHeaders.read(headerStream);
            if (!headerStream.wasFound() || in.read() != '\n' || !actual.raw.equals(expected.raw))
                return new Result(State.FAILED, "", "Message headers changed during verification.");
            BodyHash simple = new BodyHash(false);
            BodyHash relaxed = new BodyHash(true);
            hashBody(in, simple, relaxed);
            byte[] simpleHash = simple.finish();
            byte[] relaxedHash = relaxed.finish();
            for (Candidate candidate : candidates) {
                if (candidate.expired) {
                    failure = new Result(State.FAILED, candidate.domain, "The signature has expired or has an invalid timestamp.");
                    continue;
                }
                if (!MessageDigest.isEqual(candidate.bodyHash, candidate.relaxedBody ? relaxedHash : simpleHash)) {
                    failure = new Result(State.FAILED, candidate.domain, "The message body does not match its signature.");
                    continue;
                }
                try {
                    if (verifyHeaders(candidate, expected)) {
                        Result verified = new Result(State.VERIFIED, candidate.domain, "Signature and complete message body verified with a pinned domain key.");
                        verified.validUntil = candidate.expiry;
                        return verified;
                    }
                } catch (GeneralSecurityException invalid) {
                    // A bad signature must not suppress another valid signature on the same message.
                }
                failure = new Result(State.FAILED, candidate.domain, "The signed message headers do not match their signature.");
            }
        } catch (IOException unavailable) {
            return new Result(State.FAILED, "", "The signed message could not be checked safely within verification limits.");
        } catch (GeneralSecurityException invalid) {
            return new Result(State.FAILED, "", "The message signature could not be verified.");
        } finally {
            buffer.readComplete(true);
        }
        return failure;
    }

    private static boolean verifyHeaders(Candidate candidate, MailHeaders headers) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(candidate.key);
        Set<Integer> used = new HashSet<Integer>();
        for (String name : candidate.signedHeaders) {
            for (int i = headers.fields.size() - 1; i >= 0; i--) {
                MailHeaders.Field field = headers.fields.get(i);
                if (!used.contains(i) && field != candidate.field && field.name.equals(name)) {
                    verifier.update(canonicalHeader(field.raw, candidate.relaxedHeader));
                    used.add(i);
                    break;
                }
            }
        }
        byte[] signatureHeader = canonicalHeader(candidate.withoutSignature, candidate.relaxedHeader);
        // RFC 6376: no final CRLF on the DKIM-Signature header itself.
        verifier.update(signatureHeader, 0, signatureHeader.length - 2);
        return verifier.verify(candidate.signature);
    }

    private static final class Candidate {
        final MailHeaders.Field field;
        final String domain, withoutSignature;
        final List<String> signedHeaders;
        final byte[] bodyHash, signature;
        final boolean relaxedHeader, relaxedBody, expired;
        final RSAPublicKey key;
        final long expiry;

        Candidate(MailHeaders.Field field, MailHeaders headers, SenderIdentity from, long now)
                throws GeneralSecurityException {
            this.field = field;
            Map<String, String> tags = new HashMap<String, String>();
            String raw = field.raw;
            int start = raw.indexOf(':') + 1;
            int bStart = -1, bEnd = -1;
            for (int offset = start; offset < raw.length() - 2;) {
                int end = raw.indexOf(';', offset);
                if (end < 0) end = raw.length() - 2;
                String tag = raw.substring(offset, end);
                if (!tag.trim().isEmpty()) {
                    int equals = tag.indexOf('=');
                    if (equals <= 0) throw new IllegalArgumentException("Invalid DKIM tag");
                    String name = tag.substring(0, equals).trim();
                    if (!name.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid DKIM tag name");
                    if (tags.isEmpty() && !name.equals("v")) throw new IllegalArgumentException("Missing first version tag");
                    if (tags.put(name, tag.substring(equals + 1).replaceAll("\r\n[ \t]+", " ").trim()) != null)
                        throw new IllegalArgumentException("Duplicate DKIM tag");
                    if (name.equals("b")) { bStart = offset + equals + 1; bEnd = end; }
                }
                offset = end + 1;
            }
            if (!"1".equals(tags.get("v")) || !"rsa-sha256".equals(tags.get("a")) || tags.containsKey("l"))
                throw new IllegalArgumentException("Unsupported signature profile");
            if (tags.containsKey("q") && !tags.get("q").equals("dns/txt"))
                throw new IllegalArgumentException("Unsupported key query profile");
            domain = required(tags, "d").toLowerCase(Locale.US);
            String selector = required(tags, "s").toLowerCase(Locale.US);
            if (!SenderIdentity.validDomain(domain) || !domain.equals(from.domain)
                    || selector.length() > 253 || !SenderIdentity.validDomain(selector + ".invalid"))
                throw new IllegalArgumentException("Signature domain is not aligned");
            String identity = tags.get("i");
            if (identity != null) {
                int at = identity.lastIndexOf('@');
                String identityDomain = at >= 0 ? identity.substring(at + 1).toLowerCase(Locale.US) : "";
                if (!identityDomain.equals(domain) && !identityDomain.endsWith('.' + domain))
                    throw new IllegalArgumentException("Invalid signing identity domain");
            }
            String[] canonical = tags.getOrDefault("c", "simple/simple").split("/", -1);
            if (canonical.length > 2 || !(canonical[0].equals("simple") || canonical[0].equals("relaxed"))
                    || (canonical.length == 2 && !(canonical[1].equals("simple") || canonical[1].equals("relaxed"))))
                throw new IllegalArgumentException("Unsupported canonicalization");
            relaxedHeader = canonical[0].equals("relaxed");
            relaxedBody = canonical.length == 2 && canonical[1].equals("relaxed");
            signedHeaders = new ArrayList<String>();
            for (String name : required(tags, "h").split(":")) {
                name = name.trim().toLowerCase(Locale.US);
                if (!name.matches("[!-9;-~]+")) throw new IllegalArgumentException("Invalid signed field name");
                signedHeaders.add(name);
            }
            if (!signedHeaders.contains("from") || signedHeaders.size() > 128)
                throw new IllegalArgumentException("From must be signed");
            for (String name : PROTECTED) {
                int signed = 0;
                for (String included : signedHeaders) if (included.equals(name)) signed++;
                if (headers.count(name) > signed)
                    throw new IllegalArgumentException("An important message header is unsigned");
            }
            bodyHash = decode(required(tags, "bh"));
            signature = decode(required(tags, "b"));
            if (bodyHash.length != 32 || signature.length < 256 || signature.length > 1024 || bStart < 0)
                throw new IllegalArgumentException("Invalid signature size");
            withoutSignature = raw.substring(0, bStart) + raw.substring(bEnd);
            long timestamp = tags.containsKey("t") ? timestamp(tags.get("t")) : 0;
            expiry = tags.containsKey("x") ? timestamp(tags.get("x")) : Long.MAX_VALUE;
            expired = timestamp > now + 300 || expiry <= now || expiry < timestamp;
            String configured = Config.getProperty("dkim.key." + selector + "._domainkey." + domain);
            if (configured == null || configured.trim().isEmpty()) { key = null; return; }
            if (configured.length() > 4096) throw new IllegalArgumentException("Invalid pinned key size");
            key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(configured)));
            if (key.getModulus().bitLength() < 2048 || key.getModulus().bitLength() > 8192)
                throw new IllegalArgumentException("Unsupported RSA key size");
        }
    }

    private static String required(Map<String, String> tags, String name) {
        String value = tags.get(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing DKIM tag");
        return value;
    }

    private static long timestamp(String value) {
        if (!value.matches("[0-9]{1,12}")) throw new IllegalArgumentException("Invalid timestamp");
        return Long.parseLong(value);
    }

    private static byte[] decode(String value) {
        // MIME Base64 decoding silently skips arbitrary punctuation; DKIM only permits folding whitespace.
        return Base64.getDecoder().decode(value.replaceAll("[ \t\r\n]", ""));
    }

    /** Invalidate cached results immediately when the local trust configuration changes. */
    static String configurationStamp() {
        java.util.TreeMap<String, String> settings = new java.util.TreeMap<String, String>();
        for (String name : Config.getProperties().stringPropertyNames())
            if (name.startsWith("dkim.")) settings.put(name, Config.getProperty(name, ""));
        for (String name : System.getProperties().stringPropertyNames())
            if (name.startsWith("susimail.dkim.")) settings.put(name.substring(9), System.getProperty(name));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (GeneralSecurityException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static byte[] canonicalHeader(String raw, boolean relaxed) {
        if (!relaxed) return raw.getBytes(StandardCharsets.ISO_8859_1);
        int colon = raw.indexOf(':');
        String value = raw.substring(colon + 1, raw.length() - 2)
            .replaceAll("\r\n[ \t]+", " ").replaceAll("[ \t]+", " ").trim();
        return (raw.substring(0, colon).toLowerCase(Locale.US) + ':' + value + "\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void hashBody(InputStream in, BodyHash simple, BodyHash relaxed) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(1024);
        long total = 0;
        boolean cr = false;
        int c;
        while ((c = in.read()) != -1) {
            if (++total > MAX_BODY) throw new IOException("Body verification limit exceeded");
            if (cr) {
                if (c != '\n') throw new IOException("Invalid body line ending");
                byte[] bytes = line.toByteArray();
                simple.line(bytes);
                relaxed.line(bytes);
                line.reset();
                cr = false;
            } else if (c == '\r') cr = true;
            else {
                if (c == '\n' || line.size() >= MAX_BODY_LINE) throw new IOException("Invalid or oversized body line");
                line.write(c);
            }
        }
        if (cr) throw new IOException("Incomplete body line ending");
        if (line.size() > 0) {
            byte[] bytes = line.toByteArray();
            simple.line(bytes);
            relaxed.line(bytes);
        }
    }

    private static final class BodyHash {
        final MessageDigest digest;
        final boolean relaxed;
        int empty;
        boolean nonempty;
        BodyHash(boolean relaxed) throws GeneralSecurityException {
            this.relaxed = relaxed;
            digest = MessageDigest.getInstance("SHA-256");
        }
        void line(byte[] bytes) {
            int length = bytes.length;
            if (relaxed) {
                int write = 0;
                boolean space = false;
                for (int i = 0; i < length; i++) {
                    int c = bytes[i] & 255;
                    if (c == ' ' || c == '\t') space = true;
                    else {
                        if (space) bytes[write++] = ' ';
                        bytes[write++] = (byte) c;
                        space = false;
                    }
                }
                length = write;
            }
            if (length == 0) { empty++; return; }
            while (empty > 0) { digest.update((byte) '\r'); digest.update((byte) '\n'); empty--; }
            digest.update(bytes, 0, length);
            digest.update((byte) '\r'); digest.update((byte) '\n');
            nonempty = true;
        }
        byte[] finish() {
            if (!relaxed && !nonempty) { digest.update((byte) '\r'); digest.update((byte) '\n'); }
            return digest.digest();
        }
    }
}
