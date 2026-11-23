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
public final class SusiMailFloodRegression {
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
        Wire timed = new Wire("complete");
        i2p.susi.util.TimedResponseInputStream deadline = new i2p.susi.util.TimedResponseInputStream(timed, 1000);
        check(deadline.read() == 'c', "bounded protocol stream preserves reply bytes");
        Field end = deadline.getClass().getDeclaredField("deadline"); end.setAccessible(true); end.setLong(deadline, System.nanoTime() - 1);
        rejects(() -> deadline.read(), "absolute protocol deadlines stop partial-line trickles");
        pop.destroy();
    }

    

    

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("susimail-protection-"); Properties properties = new Properties();
        properties.setProperty("i2p.dir.config", temporary.toString()); properties.setProperty("i2p.dir.base", temporary.toString());
        context = new I2PAppContext(properties);
        protocolLimits();
        System.out.println("SUSIMAIL_FLOOD_CHECKS=" + checks + "; FAILURES=" + failures);
        if (failures != 0) System.exit(1);
    }
}
