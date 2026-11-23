package i2p.susi.webmail;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import i2p.susi.util.Buffer;
import i2p.susi.util.Config;
import i2p.susi.util.MemoryBuffer;
import i2p.susi.util.ReadBuffer;
import i2p.susi.webmail.pop3.POP3MailBox;
import i2p.susi.webmail.pop3.SockMgr;
import net.i2p.I2PAppContext;
import net.i2p.servlet.RequestWrapper;
import net.i2p.util.Log;

/** Cryptographic and spoofing regressions using synthetic keys and messages only. */
public final class SusiMailIdentityRegression {
    private static int checks, failures;
    private static final String KEY = "susimail.dkim.key.test._domainkey.example.test";
    private static final String HEADERS = "From: \"Accounts\" <accounts@example.test>\r\nTo: user@mail.i2p\r\n"
        + "Subject: invoice\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n";
    private static final String H = "from:to:subject:mime-version:content-type";
    private static KeyPair keys;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL: " + message); }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static Object call(Mail mail, String name) throws Exception {
        try { return method(Mail.class, name).invoke(mail); }
        catch (NoSuchMethodException missing) { return null; }
    }

    private static String status(Mail mail) throws Exception {
        Object result = call(mail, "getAuthentication");
        return result == null ? "MISSING" : field(result, "state").toString();
    }

    private static ReadBuffer buffer(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        return new ReadBuffer(bytes, 0, bytes.length);
    }

    private static Mail mail(String raw) {
        Mail mail = new Mail("identity-test");
        mail.setBody(buffer(raw));
        return mail;
    }

    /** Independent signing fixture: expected canonical bytes are constructed explicitly. */
    private static String signed(boolean relaxed, String body, String canonicalBody, String extraTags) throws Exception {
        return signed(relaxed ? "relaxed/relaxed" : "simple/simple", body, canonicalBody, extraTags);
    }

    private static String signed(String canonicalization, String body, String canonicalBody, String extraTags) throws Exception {
        boolean relaxed = canonicalization.startsWith("relaxed");
        String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(canonicalBody.getBytes(StandardCharsets.UTF_8)));
        String dkim = "DKIM-Signature: v=1; a=rsa-sha256; c=" + canonicalization
            + "; d=example.test; s=test; h=" + H + "; bh=" + hash + "; " + extraTags + "b=";
        String canonicalHeaders = relaxed
            ? "from:\"Accounts\" <accounts@example.test>\r\nto:user@mail.i2p\r\nsubject:invoice\r\n"
                + "mime-version:1.0\r\ncontent-type:text/plain; charset=UTF-8\r\n"
            : HEADERS;
        String canonicalSignature = relaxed ? "dkim-signature:" + dkim.substring("DKIM-Signature: ".length()) : dkim;
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update((canonicalHeaders + canonicalSignature).getBytes(StandardCharsets.US_ASCII));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        return dkim + signature + "\r\n" + HEADERS + "\r\n" + body;
    }

    private static void signatures() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        System.setProperty(KEY, Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        for (boolean relaxed : new boolean[] { false, true }) {
            String body = relaxed ? "  invoice\t paid \t\r\n\r\n \t\r\n" : "invoice paid\r\n\r\n";
            String canonical = relaxed ? " invoice paid\r\n" : "invoice paid\r\n";
            String raw = signed(relaxed, body, canonical, "");
            Mail original = mail(raw);
            check(status(original).equals("VERIFIED"), "valid " + (relaxed ? "relaxed" : "simple") + " DKIM signature verifies");
            check(Boolean.TRUE.equals(call(original, "canReplySafely")), "a valid signed message retains safe reply behavior");
            check(status(mail(raw.replace("invoice paid", "invoice unpaid").replace("invoice\t paid", "invoice\t unpaid"))).equals("FAILED"), "body mutation fails signature verification");
            check(status(mail(raw.replace("Subject: invoice", "Subject: payment changed"))).equals("FAILED"), "signed subject mutation fails verification");
            check(!status(mail(raw.replace("accounts@example.test", "accounts@evil.test"))).equals("VERIFIED"), "signatures from another domain cannot authenticate From");
            check(!status(mail(raw.replace("From: \"Accounts\"", "From: attacker@example.test\r\nFrom: \"Accounts\""))).equals("VERIFIED"), "an injected duplicate From cannot receive verified status");
            check(!status(mail(raw.replace("; h=from:", "; h=x-from:"))).equals("VERIFIED"), "a signature without signed From is rejected");
            check(!status(mail(raw.replace("; bh=", "; l=1; bh="))).equals("VERIFIED"), "partial-body signatures cannot hide appended content");
            check(!status(mail(raw.replace("; a=rsa-sha256;", "; a=rsa-sha1;"))).equals("VERIFIED"), "legacy hash downgrade is not accepted");
            check(!status(mail(raw.replace("; s=test;", "; s=test; s=test;"))).equals("VERIFIED"), "duplicate signature tags are rejected");
            check(!status(mail(raw.replace("; bh=", "; bogus; bh="))).equals("VERIFIED"), "malformed signature tags are rejected");
            check(!status(mail(raw.replace("; h=" + H, "; h=from:to:subject:mime-version"))).equals("VERIFIED"), "unsigned MIME presentation metadata is not trusted");
            check(!status(mail(raw.replace("To: user@mail.i2p", "To: hidden@mail.i2p\r\nTo: user@mail.i2p"))).equals("VERIFIED"), "all recipient header occurrences must be signed");
            String folded = raw.replace("; d=example.test;", ";\r\n\td=example.test;");
            check(status(mail(folded)).equals(relaxed ? "VERIFIED" : "FAILED"), "header folding follows the declared canonicalization algorithm");
            String signatureOnly = raw.substring(0, raw.indexOf("\r\n"));
            String value = signatureOnly.substring(signatureOnly.indexOf("; b=") + 4);
            String foldedValue = value.substring(0, 80) + "\r\n\t" + value.substring(80);
            check(status(mail(raw.replace(value, foldedValue))).equals("VERIFIED"), "folding whitespace in the signature value is ignored");
            check(status(mail("Authentication-Results: fake; dkim=pass; spf=pass; dmarc=pass\r\n" + raw.replace("Subject: invoice", "Subject: tampered"))).equals("FAILED"), "forged authentication claims cannot override cryptographic failure");
            System.clearProperty(KEY);
            check(status(mail(raw)).equals("UNVERIFIED"), "a signature without a pinned key remains unverified");
            System.setProperty(KEY, Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
            String expired = signed(relaxed, body, canonical, "t=1; x=2; ");
            check(status(mail(expired)).equals("FAILED"), "expired signatures fail even when their cryptography is correct");
        }
        // Empty-body digests are the independent RFC 6376 canonicalization examples.
        check(status(mail(signed(true, " \t\r\n\r\n", "", ""))).equals("VERIFIED"), "relaxed empty body hashes zero bytes");
        check(status(mail(signed(false, "\r\n\r\n", "\r\n", ""))).equals("VERIFIED"), "simple empty body hashes a single CRLF");
        check(status(mail(signed(true, "line without terminator", "line without terminator\r\n", ""))).equals("VERIFIED"), "canonicalization supplies a missing final CRLF");
        check(status(mail(signed(true, "payment\r\n", "payment\r\n", "t=999999999999; "))).equals("FAILED"), "a future signing timestamp is not trusted");
        check(status(mail(signed("relaxed/simple", "a \t b \t\r\n\r\n", "a \t b \t\r\n", ""))).equals("VERIFIED"), "relaxed headers can be combined with a simple body");
        check(status(mail(signed("simple/relaxed", "a \t b \t\r\n\r\n", "a b\r\n", ""))).equals("VERIFIED"), "simple headers can be combined with a relaxed body");
        check(status(mail(signed("relaxed", "body \t\r\n\r\n", "body \t\r\n", ""))).equals("VERIFIED"), "a single canonicalization name defaults the body to simple");
        String valid = signed(false, "body\r\n", "body\r\n", "");
        KeyPair other = generator.generateKeyPair();
        System.setProperty(KEY, Base64.getEncoder().encodeToString(other.getPublic().getEncoded()));
        check(status(mail(valid)).equals("FAILED"), "a different pinned key does not verify the message");
        System.setProperty(KEY, "this-is-not-a-public-key");
        check(status(mail(valid)).equals("UNVERIFIED"), "a malformed local key fails closed");
        System.setProperty(KEY, Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        String broken = valid.substring(0, valid.indexOf("\r\n"));
        broken = broken.substring(0, broken.indexOf("; b=") + 4) + Base64.getEncoder().encodeToString(new byte[512]);
        check(status(mail(broken + "\r\n" + valid)).equals("VERIFIED"), "one invalid signature cannot suppress another valid signature");
        check(status(mail(valid.replace("\r\n\r\nbody\r\n", "\r\n\r\nbody\nchanged\r\n"))).equals("FAILED"), "invalid body line endings cannot silently downgrade a trusted signature check");
    }

    private static void freshness() throws Exception {
        String valid = signed(false, "body\r\n", "body\r\n", "");
        Mail loaded = mail(valid);
        check(status(loaded).equals("VERIFIED"), "cached fixture starts verified");
        System.clearProperty(KEY);
        check(status(loaded).equals("UNVERIFIED"), "removing a trusted key invalidates an already-loaded result");
        System.setProperty(KEY, Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        check(status(loaded).equals("VERIFIED"), "restoring a trusted key rechecks the same loaded message");
        long now = I2PAppContext.getGlobalContext().clock().now() / 1000;
        Mail expiring = mail(signed(false, "body\r\n", "body\r\n", "t=" + (now - 1) + "; x=" + (now + 60) + "; "));
        check(status(expiring).equals("VERIFIED"), "unexpired signature initially verifies");
        long offset = I2PAppContext.getGlobalContext().clock().getOffset();
        try {
            I2PAppContext.getGlobalContext().clock().setOffset(offset + 120000, true);
            check(status(expiring).equals("FAILED"), "cached verification expires without requiring a reload");
        } finally { I2PAppContext.getGlobalContext().clock().setOffset(offset, true); }
        Path file = Files.createTempFile("signed-cache", ".eml"); Files.writeString(file, valid);
        Mail disk = new Mail("freshness"); disk.setBody(new i2p.susi.util.FileBuffer(file.toFile()));
        check(status(disk).equals("VERIFIED"), "disk-backed fixture verifies before replacement");
        Files.writeString(file, valid.replace("Subject: invoice", "Subject: changed")); file.toFile().setLastModified(file.toFile().lastModified() + 1000);
        check(status(disk).equals("FAILED") && Boolean.TRUE.equals(call(disk, "hasUnsafeContent")), "changed disk content invalidates verification and safe-action state");
    }

    private static void identities() throws Exception {
        for (String invalid : new String[] { "one@example.test two@evil.test", "a@example.test, b@evil.test",
                "Name <a@example.test> <b@evil.test>", "Name <a@example.test> garbage", "a\u0000@example.test",
                "a@exam\u202eple.test", "a@bad..test", "a@-bad.test", "a@bad-.test", ".a@example.test" })
            check(!Mail.validateAddress(invalid), "ambiguous or deceptive address is rejected");
        check("<a@example.test>".equals(Mail.getAddress("\"Doe, Jane\" <a@EXAMPLE.TEST>")), "ordinary quoted display names retain their canonical mailbox");
        Mail named = mail("From: \"Bank Security\" <criminal@evil.test>\r\nSubject: verify\r\n\r\ntext");
        check(named.shortSender.contains("criminal@evil.test") && !named.shortSender.contains("Bank Security"), "inbox sender column shows the actual address");
        Mail redirect = mail("From: sender@example.test\r\nReply-To: payment@evil.test\r\nSubject: invoice\r\n\r\ntext");
        check(Boolean.TRUE.equals(call(redirect, "isReplyRedirected")), "a different reply destination is detected");
        check(!Boolean.TRUE.equals(call(mail("From: sender@example.test\r\nReply-To: sender@example.test\r\n\r\ntext"), "isReplyRedirected")), "matching reply destination is not flagged");
        for (String fields : new String[] { "From: a@example.test\r\nFrom: b@example.test\r\n",
                "From: a@example.test\r\nReply-To: a@example.test\r\nReply-To: b@evil.test\r\n",
                "From: a@example.test\r\nSubject: one\r\nSubject: two\r\n",
                "Subject: no sender\r\n", "From: a@example.test\r\nReply-To: not-an-address\r\n" })
            check(Boolean.FALSE.equals(call(mail(fields + "\r\nbody"), "canReplySafely")), "ambiguous identity metadata blocks automatic replies");
        for (String encoded : new String[] {
                "=?UTF-8?Q?hello=0D=0AFrom:_trusted@example.test?=",
                "=?UTF-8?B?aGVsbG8NCkZyb206IHRydXN0ZWRAZXhhbXBsZS50ZXN0?=",
                "=?UTF-16BE?B?AA0ACgBGAHIAbwBtADoAIABhAEAAZQB2AGkAbAAuAHQAZQBzAHQ=?=" }) {
            Mail attack = mail("From: attacker@evil.test\r\nSubject: " + encoded + "\r\n\r\nbody");
            check(!attack.hasBody() && !attack.hasPart(), "encoded line breaks cannot create extra identity headers");
        }
        Mail encodedName = mail("=?UTF-8?Q?From?=: trusted@example.test\r\nSubject: x\r\n\r\nbody");
        check(!encodedName.sender.contains("trusted@example.test") && Boolean.FALSE.equals(call(encodedName, "canReplySafely")), "an encoded field name cannot become From");
        Mail unicode = mail("From: =?UTF-8?B?Sm9zw6k=?= <jose@example.test>\r\nSubject: =?UTF-8?Q?caf=C3=A9?=\r\n\r\nbody");
        check(unicode.sender.contains("José") && unicode.subject.equals("café") && unicode.hasPart(), "valid international display text still decodes");
        Mail invisible = mail("From: =?UTF-8?Q?Bank=E2=80=AE?= <a@example.test>\r\nSubject: safe\r\n\r\nbody");
        check(!invisible.shortSender.contains("\u202e"), "direction controls cannot disguise the inbox address");
        Mail changed = new Mail("changed-preview");
        changed.setHeader(buffer("From: bank@example.test\r\nSubject: invoice\r\n\r\n"));
        changed.setBody(buffer("From: criminal@evil.test\r\nSubject: invoice\r\n\r\nnew body"));
        check(changed.sender.contains("criminal@evil.test"), "full-message metadata replaces stale preview headers");
        check(Boolean.FALSE.equals(call(changed, "canReplySafely")), "changed preview identity blocks automatic replies");
        changed.setBody(buffer("From: incomplete"));
        check(!changed.hasBody() && !changed.hasPart(), "failed message replacement cannot expose the old body");
        Mail large = mail("From: a@example.test\r\nX-Large: " + "a".repeat(300000) + "\r\n\r\nbody");
        check(!large.hasPart(), "oversized headers are rejected without exposing a body");
        String nested = "From: a@example.test\r\n";
        for (int i = 0; i < 40; i++) nested += "Content-Type: message/rfc822\r\n\r\nFrom: a@example.test\r\n";
        check(!mail(nested + "\r\nbody").hasPart(), "excessive nested messages are rejected before stack exhaustion");
        String multipart = "From: sender@example.test\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=test-boundary\r\n\r\n"
            + "--test-boundary\r\nContent-Type: text/plain\r\n\r\nhello\r\n"
            + "--test-boundary\r\nContent-Type: application/octet-stream\r\nContent-Transfer-Encoding: base64\r\n\r\nYWJj\r\n--test-boundary--\r\n";
        Mail mixed = mail(multipart);
        check(mixed.hasPart() && mixed.getPart().parts.size() == 2, "ordinary multipart messages retain their MIME structure");
        if (mixed.hasPart() && mixed.getPart().parts.size() == 2) {
            MemoryBuffer decoded = new MemoryBuffer();
            mixed.getPart().parts.get(1).decode(0, decoded);
            decoded.writeComplete(true);
            check(new String(decoded.getContent(), StandardCharsets.UTF_8).equals("abc"), "multipart attachment decoding retains the original bytes");
        }
        StringBuilder manyParts = new StringBuilder("From: sender@example.test\r\nContent-Type: multipart/mixed; boundary=parts\r\n\r\n");
        for (int i = 0; i < 1025; i++) manyParts.append("--parts\r\nContent-Type: text/plain\r\n\r\nx\r\n");
        manyParts.append("--parts--\r\n");
        check(!mail(manyParts.toString()).hasPart(), "excessive MIME part counts are rejected without exposing partial data");
        System.clearProperty(KEY);
        check(status(mail("Authentication-Results: example.test; dkim=pass; spf=pass; dmarc=pass\r\n" + HEADERS + "\r\nbody")).equals("UNVERIFIED"), "authentication-result text is never proof of identity");
    }

    private static void actions() throws Exception {
        Class<?> sessionType = Class.forName("i2p.susi.webmail.WebMail$SessionObject");
        Constructor<?> constructor = sessionType.getDeclaredConstructor(Log.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(new Log(SusiMailIdentityRegression.class));
        set(session, "user", "user");
        MailCache cache = new MailCache(I2PAppContext.getGlobalContext(), null, WebMail.DIR_FOLDER, "unused.test", 110, "user", "password");
        @SuppressWarnings("unchecked") Map<String, Mail> mails = (Map<String, Mail>) field(cache, "mails");
        @SuppressWarnings("unchecked") Map<String, MailCache> caches = (Map<String, MailCache>) field(session, "caches");
        caches.put(WebMail.DIR_FOLDER, cache);
        MailCache drafts = new MailCache(I2PAppContext.getGlobalContext(), null, WebMail.DIR_DRAFTS, "unused.test", 110, "user", "password");
        caches.put(WebMail.DIR_DRAFTS, drafts);
        Mail redirect = mail("From: accounts@example.test\r\nReply-To: criminal@evil.test\r\nSubject: invoice\r\n\r\ntext");
        mails.put(redirect.uidl, redirect);
        Map<String, String> parameters = new HashMap<String, String>();
        parameters.put("reply", "1");
        parameters.put("msg", net.i2p.data.Base64.encode(redirect.uidl));
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
            new Class<?>[] { HttpServletRequest.class }, (object, call, args) -> call.getName().equals("getParameter") ? parameters.get(args[0]) : null);
        Class<?> state = Class.forName("i2p.susi.webmail.WebMail$State");
        Object show = java.util.Arrays.stream(state.getEnumConstants()).filter(value -> value.toString().equals("SHOW")).findFirst().orElseThrow();
        Method process = method(WebMail.class, "processStateChangeButtons", sessionType, RequestWrapper.class, boolean.class, state);
        Object next;
        synchronized (session) { next = process.invoke(null, session, new RequestWrapper(request), true, show); }
        check(next.toString().equals("SHOW") && field(session, "error").toString().contains("Confirm"), "server handler rejects a redirected reply without confirmation");
        StringWriter rendered = new StringWriter();
        try {
            method(WebMail.class, "showMessageSecurity", PrintWriter.class, Mail.class).invoke(null, new PrintWriter(rendered), redirect);
            check(rendered.toString().contains("confirmReplyTo") && rendered.toString().contains("criminal@evil.test"), "trusted message UI shows the redirect address and confirmation control");
        } catch (NoSuchMethodException missing) { check(false, "message UI exposes identity and integrity status"); }

        parameters.put("confirmReplyTo", "1");
        set(session, "error", "");
        synchronized (session) { next = process.invoke(null, session, new RequestWrapper(request), true, show); }
        check(next.toString().equals("NEW") && !field(session, "draftUIDL").toString().isEmpty(), "explicitly confirmed reply can create its draft");

        Mail ambiguous = mail("From: accounts@example.test\r\nFrom: criminal@evil.test\r\n\r\ntext");
        mails.put(ambiguous.uidl, ambiguous);
        set(session, "error", "");
        synchronized (session) { next = process.invoke(null, session, new RequestWrapper(request), true, show); }
        check(next.toString().equals("SHOW"), "confirmation cannot bypass ambiguous sender protection");

        String raw = signed(false, "body\r\n", "body\r\n", "");
        System.setProperty(KEY, Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        Mail tampered = mail(raw.replace("\r\n\r\nbody", "\r\n\r\naltered"));
        mails.put(tampered.uidl, tampered);
        parameters.put("att", "0");
        int[] errorCode = { 0 };
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),
            new Class<?>[] { HttpServletResponse.class }, (object, call, args) -> { if (call.getName().equals("sendError")) errorCode[0] = (Integer) args[0]; return null; });
        if (call(tampered, "getAuthentication") != null) {
            method(WebMail.class, "processDownloadLink", sessionType, String.class, RequestWrapper.class, HttpServletResponse.class)
                .invoke(null, session, tampered.uidl, new RequestWrapper(request), response);
            check(errorCode[0] == 403, "failed signature checks block attachment serving at the handler");
        } else { check(false, "failed signature checks block attachment serving at the handler"); }
        System.setProperty("susimail.pop3.leave.on.server", "false");
        try {
            check(!(Boolean) method(MailCache.class, "canDeleteFromServer", Mail.class).invoke(null, tampered), "unsafe message does not trigger automatic server deletion");
        } catch (NoSuchMethodException missing) { check(false, "automatic deletion is gated on message integrity"); }

        // Exercise fixed-sender enforcement through the real background sender and SMTP writer.
        Class<?> wireType = Class.forName("i2p.susi.webmail.SusiMailSecurityRegression$Wire");
        Constructor<?> wireConstructor = wireType.getDeclaredConstructor();
        wireConstructor.setAccessible(true);
        java.net.Socket wire = (java.net.Socket) wireConstructor.newInstance();
        method(wireType, "reply", String.class).invoke(wire, "220 ready\r\n250 ready\r\n334 username\r\n334 password\r\n235 accepted\r\n250 sender\r\n250 recipient\r\n354 data\r\n250 accepted\r\n");
        SockMgr manager = new SockMgr(I2PAppContext.getGlobalContext()) {
            @Override public synchronized boolean connect() { return true; }
            @Override public net.i2p.data.Destination getDestination(String host) { return new net.i2p.data.Destination(); }
            @Override public java.net.Socket connect(net.i2p.data.Destination destination, int port) { return wire; }
        };
        POP3MailBox mailbox = new POP3MailBox("unused.test", 110, "user", "password") {
            @Override public SockMgr getSockMgr() { return manager; }
        };
        set(session, "mailbox", mailbox);
        set(session, "smtpHost", "unused.test");
        set(session, "smtpPort", 25);
        set(session, "pass", "password");
        System.setProperty("susimail.sender.fixed", "true");
        System.setProperty("susimail.sender.domain", "mail.i2p");
        System.setProperty("susimail.sender.name", "");
        Draft forged = new Draft("forged-draft");
        forged.setBody(buffer("From: victim@example.test\r\nTo: recipient@mail.i2p\r\nSubject: spoof attempt\r\nMIME-Version: 1.0\r\nContent-Type: text/plain\r\n\r\nbody\r\n"));
        Path attachment = Files.createTempFile("send-size-fixture", ".bin");
        byte[] content = new byte[(int) (i2p.susi.webmail.smtp.SMTPClient.BINARY_MAX_SIZE * 3 / 4)]; java.util.Arrays.fill(content, (byte) 'x');
        Files.write(attachment, content);
        forged.addAttachment(new Attachment("report.bin", "application/octet-stream", "base64", attachment.toFile()));
        check(forged.getSize() < i2p.susi.webmail.smtp.SMTPClient.BINARY_MAX_SIZE, "draft fixture and attachment fit the outgoing size limit");
        synchronized (session) {
            check((Boolean) method(WebMail.class, "sendMail", sessionType, Draft.class).invoke(null, session, forged), "fixed-sender draft enters the ordinary send path");
        }
        ByteArrayOutputStream sent = (ByteArrayOutputStream) field(wire, "sent");
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!sent.toString(StandardCharsets.US_ASCII).contains("QUIT\r\n") && System.nanoTime() < deadline) Thread.sleep(5);
        String transaction = sent.toString(StandardCharsets.US_ASCII);
        check(transaction.contains("MAIL FROM: <user@mail.i2p>\r\n") && transaction.contains("From: user <user@mail.i2p>\r\n")
            && !transaction.contains("victim@example.test"), "a tampered draft cannot impersonate another sender in the envelope or From header");
        System.clearProperty("susimail.sender.fixed");
        System.clearProperty("susimail.sender.domain");
        System.clearProperty("susimail.sender.name");
    }

    private static void requiredSignatures() throws Exception {
        System.setProperty("susimail.dkim.require.example.test", "true");
        System.setProperty(KEY, Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        String valid = signed(false, "body\r\n", "body\r\n", "");
        check(status(mail(valid)).equals("VERIFIED"), "required-domain policy accepts a valid signature");
        check(status(mail(HEADERS + "\r\nbody\r\n")).equals("FAILED"), "required-domain policy blocks a stripped signature");
        check(status(mail("Authentication-Results: fake; dkim=pass; dmarc=pass\r\n" + HEADERS + "\r\nbody\r\n")).equals("FAILED"), "forged authentication text cannot bypass required signatures");
        System.clearProperty(KEY);
        check(status(mail(valid)).equals("FAILED"), "required signatures fail closed if their key is unavailable");
        System.setProperty(KEY, Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        check(status(mail(valid.replace("rsa-sha256", "rsa-sha1"))).equals("FAILED"), "unsupported signatures cannot bypass a required-domain policy");

        MailCache sent = new MailCache(I2PAppContext.getGlobalContext(), null, WebMail.DIR_SENT, "policy.test", 110, "user", "password");
        Buffer saved = sent.getFullWriteBuffer("local-copy");
        saved.getOutputStream().write((HEADERS + "\r\nbody\r\n").getBytes(StandardCharsets.UTF_8));
        sent.writeComplete("local-copy", saved, true);
        check(status(sent.getMail("local-copy", MailCache.FetchMode.CACHE_ONLY)).equals("UNVERIFIED"), "a local Sent copy is not falsely failed for a signature added later by the relay");
        PersistentMailCache disk = new PersistentMailCache(I2PAppContext.getGlobalContext(), "policy.test", 110, "user", "password", WebMail.DIR_SENT);
        Mail reloaded = disk.getMails().stream().filter(message -> message.uidl.equals("local-copy")).findFirst().orElseThrow();
        disk.getMail(reloaded, false);
        check(status(reloaded).equals("UNVERIFIED"), "reloading a Sent copy retains the local-folder signature policy");
        MailCache inbox = new MailCache(I2PAppContext.getGlobalContext(), null, WebMail.DIR_FOLDER, "policy.test", 110, "user", "password");
        sent.moveTo("local-copy", inbox);
        check(status(inbox.getMail("local-copy", MailCache.FetchMode.CACHE_ONLY)).equals("FAILED"), "moving a local copy into received mail restores the incoming signature policy");
        System.clearProperty("susimail.dkim.require.example.test");
    }

    public static void main(String[] args) throws Exception {
        Class.forName("i2p.susi.webmail.WebMail");
        I2PAppContext.getGlobalContext().logManager().setDefaultLimit("CRIT");
        signatures();
        freshness();
        identities();
        actions();
        requiredSignatures();
        System.out.println("SUSIMAIL_IDENTITY_CHECKS=" + checks + "; FAILURES=" + failures);
        I2PAppContext.getGlobalContext().logManager().shutdown();
        System.exit(failures == 0 ? 0 : 1);
    }
}
