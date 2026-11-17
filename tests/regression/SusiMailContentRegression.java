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
public final class SusiMailContentRegression {
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
    

    

    

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("susimail-protection-"); Properties properties = new Properties();
        properties.setProperty("i2p.dir.config", temporary.toString()); properties.setProperty("i2p.dir.base", temporary.toString());
        context = new I2PAppContext(properties);
        contentAndPolicy();
        System.out.println("SUSIMAIL_CONTENT_CHECKS=" + checks + "; FAILURES=" + failures);
        if (failures != 0) System.exit(1);
    }
private static void contentAndPolicy() throws Exception {
        String attack = "<style>@font-face{font-family:lie;src:url(https://bad.test/font.woff);unicode-range:U+0-FFFF}</style>"
            + "<div style='font-size:0' class='hidden' id='spoof'>Visible after cleanup</div><font face='lie'>pay</font>"
            + "<script>steal()</script><form action='https://bad.test'><input type='password'></form>"
            + "<svg onload='steal()'></svg><img src='https://bad.test/pixel'><img src='cid:local-image'>"
            + "<a href='https://example.test/path?q=1&amp;x=2' target='_top'>safe destination</a><a href='javascript:steal()'>unsafe</a>";
        String safe = SafeMailHTML.sanitize(attack, "fixture", "cur");
        check(safe.contains("Visible after cleanup") && safe.contains("pay"), "font-smuggling cleanup preserves the readable semantic text");
        for (String forbidden : new String[] {"@font-face", "unicode-range", "font-size", "<script", "<form", "<input", "<svg", "javascript:", "https://bad.test", "target=", "class=\"hidden\""})
            check(!safe.contains(forbidden), "untrusted HTML removes " + forbidden);
        check(safe.contains("cid=local-image") && safe.contains("data-safe-link=\"1\"") && safe.contains("?openLink="), "CID raster images and explicit destination-check links survive rebuilding");
        for (String destination : new String[] {"javascript:alert(1)","data:text/html,hello","//example.test","https://user:pass@example.test/","http://127.0.0.1/","http://[::1]/","http://device.local/","https://example.test:0/","https://example.test:65536/","https://exam\u202eple.test/"})
            check(SafeMailHTML.safeURL(destination) == null, "unsafe link is refused: " + destination);
        check(SafeMailHTML.safeURL("https://example.test/path").equals("https://example.test/path"), "ordinary HTTPS link remains usable");
        check(!SafeMailHTML.safeInlineType("image/svg+xml") && !SafeMailHTML.safeInlineType("font/woff2") && SafeMailHTML.safeInlineType("image/png"), "inline content excludes SVG and custom fonts");
        rejects(() -> SafeMailHTML.sanitize("x".repeat(SafeMailHTML.MAX_HTML + 1), "a", "cur"), "HTML display is bounded");
        String manyImages = SafeMailHTML.sanitize("<img src='cid:a'>".repeat(100), "a", "cur");
        check(manyImages.split("<img ", -1).length - 1 == 20, "inline image count is bounded");
    }
}
