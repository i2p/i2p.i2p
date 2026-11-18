package i2p.susi.webmail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;

import i2p.susi.util.ReadBuffer;
import i2p.susi.webmail.pop3.POP3MailBox;
import i2p.susi.webmail.pop3.SockMgr;
import i2p.susi.webmail.smtp.SMTPClient;
import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.servlet.RequestWrapper;
import net.i2p.util.Log;

/** Exercises production mail writers, MIME decoding and servlet responses offline. */
public final class SusiMailSecurityRegression {
    private static int checks, failures;
    private static String selectedSuite = "all";
    private static final String PRIVATE = "PRIVATE_MAIL_MARKER";
    private static final Class<?> SESSION;
    static {
        try { SESSION = Class.forName("i2p.susi.webmail.WebMail$SessionObject"); }
        catch (ClassNotFoundException e) { throw new ExceptionInInitializerError(e); }
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL: " + message); }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method result = type.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static final class Capture extends Log {
        final StringBuilder messages = new StringBuilder();
        Capture() { super(SusiMailSecurityRegression.class); }
        @Override public boolean shouldLog(int priority) { return true; }
        @Override public boolean shouldDebug() { return true; }
        @Override public void debug(String message) { messages.append(message).append('\n'); }
    }

    private static final class Wire extends Socket {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        InputStream received = InputStream.nullInputStream();
        @Override public synchronized int getSoTimeout() { return 120000; }
        @Override public OutputStream getOutputStream() { return sent; }
        @Override public InputStream getInputStream() { return received; }
        @Override public void setSoTimeout(int timeout) {}
        void reply(String value) { received = new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII)); }
    }

    private static Object session(Capture log) throws Exception {
        Constructor<?> constructor = SESSION.getDeclaredConstructor(Log.class);
        constructor.setAccessible(true);
        return constructor.newInstance(log);
    }

    private static void commands() throws Exception {
        Object[] clients = { new SMTPClient(), new POP3MailBox("unused.invalid", 110, PRIVATE, PRIVATE) };
        String[] writers = { "sendCmdNoWait", "sendCmd1aNoWait" };
        for (int i = 0; i < clients.length; i++) {
            Object client = clients[i];
            Capture log = new Capture();
            Wire wire = new Wire();
            set(client, "socket", wire);
            set(client, "_log", log);
            Method writer = method(client.getClass(), writers[i], String.class);
            String encoded = Base64.getEncoder().encodeToString(PRIVATE.getBytes(StandardCharsets.UTF_8));
            String[] valid = i == 0
                ? new String[] { "AUTH LOGIN", encoded, "MAIL FROM: <" + PRIVATE + "@mail.i2p>", "RCPT TO: <hidden@mail.i2p>" }
                : new String[] { "USER " + PRIVATE, "PASS " + PRIVATE, "RETR 1" };
            for (String command : valid) {
                wire.sent.reset();
                writer.invoke(client, command);
                check(wire.sent.toString(StandardCharsets.US_ASCII).equals(command + "\r\n"),
                      client.getClass().getSimpleName() + " preserves valid command bytes");
            }
            for (String bad : new String[] { "USER x\r\nDELE 1", "USER x\nQUIT", "PASS x\rQUIT", "PASS x\0hidden",
                    "USER x\u010d\u010aDELE 1", "USER x\u010aQUIT", "PASS x\u0100hidden", "PASS x\u017fhidden",
                    "PASS x\u0007hidden", "PASS x\u007fhidden" }) {
                wire.sent.reset();
                boolean rejected = false;
                try { writer.invoke(client, bad); }
                catch (InvocationTargetException e) { rejected = e.getCause() instanceof IOException; }
                check(rejected && wire.sent.size() == 0, client.getClass().getSimpleName() + " rejects injected commands before writing");
            }
            if (i == 0) {
                wire.reply("535 " + PRIVATE + " " + encoded + "\r\n");
                check(((Integer) method(SMTPClient.class, "getResult").invoke(client)) == 535,
                      "SMTP status remains available without logging response contents");
            } else {
                for (String reply : new String[] { "+OK " + PRIVATE + "\r\n", "-ERR " + PRIVATE + "\r\n", "" }) {
                    wire.reply(reply);
                    try { method(POP3MailBox.class, "sendCmd1a", String.class).invoke(client, "PASS " + PRIVATE); }
                    catch (InvocationTargetException e) {
                        if (!(e.getCause() instanceof IOException)) throw e;
                    }
                }
                Class<?> mode = Class.forName("i2p.susi.webmail.pop3.POP3MailBox$Mode");
                Class<?> sr = Class.forName("i2p.susi.webmail.pop3.POP3MailBox$SendRecv");
                Constructor<?> constructor = sr.getDeclaredConstructor(String.class, mode);
                constructor.setAccessible(true);
                Object command = constructor.newInstance("USER " + PRIVATE, mode.getEnumConstants()[0]);
                for (boolean pipeline : new boolean[] { false, true }) {
                    set(client, "supportsPipelining", pipeline);
                    wire.reply("+OK " + PRIVATE + "\r\n");
                    check((Boolean) method(POP3MailBox.class, "sendCmds", List.class).invoke(client, List.of(command)),
                          "POP3 still handles pipelined and sequential responses");
                }
            }
            check(!log.messages.toString().contains(PRIVATE) && !log.messages.toString().contains(encoded)
                  && !log.messages.toString().contains("hidden@mail.i2p"),
                  client.getClass().getSimpleName() + " omits credentials, addresses and echoed server contents from diagnostics");
        }
    }

    private static void smtpConversation() throws Exception {
        for (boolean pipeline : new boolean[] { false, true }) {
            Wire wire = new Wire();
            wire.reply("220 ready\r\n250-mail.test\r\n250 " + (pipeline ? "PIPELINING" : "HELP")
                + "\r\n334 username\r\n334 password\r\n235 authenticated\r\n250 sender\r\n250 recipient\r\n354 data\r\n250 accepted\r\n");
            SockMgr manager = new SockMgr(I2PAppContext.getGlobalContext()) {
                @Override public synchronized boolean connect() { return true; }
                @Override public Destination getDestination(String host) { return new Destination(); }
                @Override public Socket connect(Destination destination, int port) { return wire; }
            };
            SMTPClient client = new SMTPClient();
            Capture log = new Capture();
            set(client, "_log", log);
            String user = PRIVATE + "é";
            String password = PRIVATE + "x".repeat(100);
            String body = "From: sender@mail.i2p\r\nSubject: test\r\n\r\nmessage body\r\n";
            check(client.sendMail("unused.invalid", 25, manager, user, password, "<sender@mail.i2p>",
                List.of("<recipient@mail.i2p>"), new StringBuilder(body), null, null),
                "SMTP completes a synthetic " + (pipeline ? "pipelined" : "sequential") + " transaction");
            String expected = "EHLO localhost\r\nAUTH LOGIN\r\n"
                + Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8)) + "\r\n"
                + Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)) + "\r\n"
                + "MAIL FROM: <sender@mail.i2p>\r\nRCPT TO: <recipient@mail.i2p>\r\nDATA\r\n"
                + body + "\r\n.\r\nQUIT\r\n";
            check(wire.sent.toString(StandardCharsets.US_ASCII).equals(expected),
                  "long and Unicode AUTH credentials use one complete Base64 line each");
            check(!log.messages.toString().contains(PRIVATE) && !log.messages.toString().contains("@mail.i2p"),
                  "complete SMTP transaction retains no credentials or addresses in diagnostics");
        }
    }

    private static final class Response {
        final Map<String, String> headers = new HashMap<String, String>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
            HttpServletResponse.class.getClassLoader(), new Class<?>[] { HttpServletResponse.class }, (object, call, args) -> {
                String name = call.getName();
                if (name.equals("setHeader") || name.equals("addHeader") || name.equals("setDateHeader"))
                    headers.put(args[0].toString().toLowerCase(Locale.US), args[1].toString());
                else if (name.equals("setContentType")) headers.put("content-type", args[0].toString());
                else if (name.equals("getOutputStream")) return new ServletOutputStream() {
                    @Override public void write(int b) { bytes.write(b); }
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(WriteListener listener) {}
                };
                else if (name.equals("isCommitted")) return false;
                return null;
            });
    }

    private static Mail mail(String name, String type, String body) throws Exception {
        String data = "From: sender@mail.i2p\r\nTo: recipient@mail.i2p\r\nSubject: test\r\nMIME-Version: 1.0\r\n"
            + "Content-Type: " + type + "; charset=UTF-8\r\nContent-Disposition: attachment; filename*=UTF-8''"
            + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")
            + "\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" + body;
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        Mail mail = new Mail("synthetic-mail");
        mail.setBody(new ReadBuffer(bytes, 0, bytes.length));
        if (mail.getPart() == null) throw new AssertionError("test MIME message did not parse");
        return mail;
    }

    private static void noStore(Response response, String path) {
        String policy = response.headers.getOrDefault("cache-control", "");
        check(policy.contains("private") && policy.contains("no-store") && !policy.contains("public"),
              path + " prevents browser and shared cache storage");
    }

    private static final String HTML = "<!DOCTYPE html><html><head><base href=\"https://tracker.invalid/\">"
        + "<script>window.emailScriptRan=true</script><script src=\"/js/iframeResizer.contentWindow.js\"></script>"
        + "</head><body><p id=\"readable\">Readable mail</p><img src=\"https://tracker.invalid/pixel\">"
        + "<img id=\"inline\" src=\"cid:test-image\"><a id=\"escape\" target=\"_top\" href=\"https://escape.invalid/\">link</a>"
        + "<form action=\"https://tracker.invalid/submit\"><input type=\"submit\"></form>"
        + "<iframe src=\"https://tracker.invalid/frame\"></iframe></body></html>";

    private static void downloads(Path output, Map<String, String> fixtures) throws Exception {
        Object session = session(new Capture());
        Method send = method(WebMail.class, "sendAttachment", SESSION, MailPart.class, HttpServletResponse.class, boolean.class);
        for (String name : new String[] { "../../escape.txt", "..\\..\\escape.txt", "/absolute.txt", "C:\\escape.txt", ".", "..", "", "report.txt" }) {
            Mail mail = mail(name, "application/octet-stream", PRIVATE);
            Response response = new Response();
            check((Boolean) send.invoke(null, session, mail.getPart(), response.response, false), "attachment ZIP is produced");
            noStore(response, "ZIP attachment");
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.bytes.toByteArray()))) {
                ZipEntry entry = zip.getNextEntry();
                String entryName = entry.getName();
                check(!entryName.contains("/") && !entryName.contains("\\") && !entryName.contains(":")
                      && !entryName.equals(".") && !entryName.equals("..") && !entryName.isEmpty(),
                      "ZIP entry cannot name a path outside the extraction directory");
                check(new String(zip.readAllBytes(), StandardCharsets.UTF_8).equals(PRIVATE), "ZIP attachment retains its original contents");
                if (name.equals("report.txt")) check(entryName.equals(name), "ordinary attachment names remain intact");
            }
        }
        Mail html = mail("message.html", "text/html", HTML);
        Response raw = new Response();
        send.invoke(null, session, html.getPart(), raw.response, true);
        noStore(raw, "inline HTML attachment");
        fixtures.put("html", raw.bytes.toString(StandardCharsets.UTF_8));
        check(!fixtures.get("html").contains("injected by susimail"), "HTML mail does not receive an executable resizing script");
        check(fixtures.get("html").contains("/susimail/?msg="), "inline CID references are still rewritten for authenticated loading");

        Response saved = new Response();
        method(WebMail.class, "sendMailSaveAs", SESSION, Mail.class, HttpServletResponse.class).invoke(null, session, html, saved.response);
        noStore(saved, "saved message");
        check(java.util.Arrays.equals(saved.bytes.toByteArray(), html.getBody().getInputStream().readAllBytes()), "saved message bytes are unchanged");

        Path file = output.resolve("draft-attachment.bin");
        Files.writeString(file, PRIVATE);
        Attachment attachment = new Attachment("draft.txt", "text/plain", "base64", file.toFile());
        set(session, "attachments", new ArrayList<Attachment>(List.of(attachment)));
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
            new Class<?>[] { HttpServletRequest.class }, (object, call, args) -> call.getName().equals("getParameter") ? Integer.toString(attachment.hashCode()) : null);
        Response draft = new Response();
        method(WebMail.class, "processDraftAttachmentLink", SESSION, RequestWrapper.class, HttpServletResponse.class)
            .invoke(null, session, new RequestWrapper(request), draft.response);
        noStore(draft, "draft attachment");
        check(draft.bytes.toString(StandardCharsets.UTF_8).equals(PRIVATE), "draft thumbnail bytes are unchanged");

        Class<?> mode = Class.forName("i2p.susi.webmail.WebMail$HtmlMode");
        StringWriter markup = new StringWriter();
        Method show = method(WebMail.class, "showPart", PrintWriter.class, MailPart.class, int.class, boolean.class, mode);
        show.invoke(null, new PrintWriter(markup), html.getPart(), 0, true, mode.getEnumConstants()[2]);
        fixtures.put("markup", markup.toString());
        check(fixtures.get("markup").contains("sandbox=\"allow-same-origin\""), "HTML mail frame disables scripts, forms, popups and top navigation");
    }

    private static String header(String response, String name) {
        for (String line : response.substring(0, response.indexOf("\r\n\r\n")).split("\r\n")) {
            if (line.toLowerCase(Locale.US).startsWith(name.toLowerCase(Locale.US) + ":")) return line.substring(line.indexOf(':') + 1).trim();
        }
        return "";
    }

    private static void http(Map<String, String> fixtures) throws Exception {
        Capture log = new Capture();
        WebMail webmail = new WebMail();
        set(webmail, "_log", log);
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/susimail");
        context.addServlet(new ServletHolder(webmail), "/*");
        server.setHandler(context.getCoreContextHandler());
        try {
            server.start();
            for (String query : new String[] { "", "?att=0", "?cid=image", "?datt=123", "?download=0", "?saveas=1" }) {
                String response = connector.getResponse("GET /susimail/" + query + " HTTP/1.1\r\nHost: mail.test\r\nConnection: close\r\n\r\n");
                check(response.startsWith("HTTP/1.1 200"), "servlet handles " + query);
                String cache = header(response, "Cache-Control");
                check(cache.contains("private") && cache.contains("no-store"), "HTTP " + query + " cannot be stored by caches");
                String cookie = header(response, "Set-Cookie").split(";", 2)[0];
                if (cookie.contains("=")) check(!log.messages.toString().contains(cookie.substring(cookie.indexOf('=') + 1)), "session identifiers are omitted from logs");
                String csp = header(response, "Content-Security-Policy");
                if (!selectedSuite.equals("privacy")) check(csp.contains("base-uri 'none'"), "HTTP " + query + " blocks base URL rewriting");
                if (query.equals("?att=0") && !selectedSuite.equals("privacy")) {
                    fixtures.put("csp", csp);
                    check(csp.contains("sandbox allow-same-origin") && csp.contains("script-src 'none'"), "attachment response isolates active content even when opened directly");
                    check(csp.contains("img-src http://mail.test/susimail/"), "inline image policy works on an origin using its default port");
                }
            }
            // Offline login unlocks an existing cache; a fresh account must authenticate with its provider first.
            new MailCache(I2PAppContext.getGlobalContext(), null, WebMail.DIR_FOLDER,
                i2p.susi.util.Config.getProperty("host.pop3", "pop.postman.i2p"),
                i2p.susi.util.Config.getProperty("ports.pop3", 110), "mail-security-test", PRIVATE);
            String login = connector.getResponse("GET /susimail/ HTTP/1.1\r\nHost: mail.test\r\nConnection: close\r\n\r\n");
            String oldCookie = header(login, "Set-Cookie").split(";", 2)[0];
            java.util.regex.Matcher nonce = java.util.regex.Pattern.compile("name=\"susiNonce\" value=\"([^\"]+)\"").matcher(login);
            if (!nonce.find()) throw new AssertionError("login did not contain a CSRF nonce");
            String form = "offline=1&user=mail-security-test&pass=" + PRIVATE;
            String invalid = post(connector, oldCookie, form);
            check(invalid.contains("Invalid form submission"), "login rejects a POST without its session's CSRF nonce");
            String authenticated = post(connector, oldCookie, form + "&susiNonce=" + nonce.group(1));
            String newCookie = header(authenticated, "Set-Cookie").split(";", 2)[0];
            check(authenticated.startsWith("HTTP/1.1 303"), "offline mailbox login still succeeds");
            check(newCookie.contains("=") && !newCookie.equals(oldCookie), "entering a mailbox rotates the pre-login session ID");
            String stale = connector.getResponse("GET /susimail/ HTTP/1.1\r\nHost: mail.test\r\nCookie: " + oldCookie + "\r\nConnection: close\r\n\r\n");
            check(stale.contains("name=\"pass\""), "the previous session identifier cannot access the mailbox");
            String current = connector.getResponse("GET /susimail/ HTTP/1.1\r\nHost: mail.test\r\nCookie: " + newCookie + "\r\nConnection: close\r\n\r\n");
            check(current.contains("name=\"logout\"") && !current.contains("name=\"pass\""), "the rotated session keeps the mailbox available");
        } finally { server.stop(); server.destroy(); }

        Object session = session(log);
        Map<String, Object> attributes = new HashMap<String, Object>();
        HttpSession httpSession = (HttpSession) Proxy.newProxyInstance(HttpSession.class.getClassLoader(), new Class<?>[] { HttpSession.class }, (object, call, args) -> {
            switch (call.getName()) {
                case "getId": return PRIVATE;
                case "getCreationTime": return 1L;
                case "getAttribute": return attributes.get(args[0]);
                case "setAttribute": attributes.put(args[0].toString(), args[1]); return null;
                default: return null;
            }
        });
        Method get = method(WebMail.class, "getSessionObject", HttpSession.class);
        get.invoke(webmail, httpSession);
        get.invoke(webmail, httpSession);
        ((HttpSessionBindingListener) session).valueUnbound(new HttpSessionBindingEvent(httpSession, "sessionObject"));
        check(!log.messages.toString().contains(PRIVATE), "new, existing and removed sessions do not disclose identifiers");
    }

    private static String post(LocalConnector connector, String cookie, String form) throws Exception {
        return connector.getResponse("POST /susimail/ HTTP/1.1\r\nHost: mail.test\r\nCookie: " + cookie
            + "\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: " + form.length()
            + "\r\nConnection: close\r\n\r\n" + form);
    }

    public static void main(String[] args) throws Exception {
        I2PAppContext.getGlobalContext().logManager().setDefaultLimit("CRIT");
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Map<String, String> fixtures = new HashMap<String, String>();
        String suite = args.length > 1 ? args[1] : "all"; selectedSuite = suite;
        if (suite.equals("all") || suite.equals("protocol")) { commands(); smtpConversation(); }
        if (suite.equals("all") || suite.equals("downloads")) downloads(output, fixtures);
        if (suite.equals("all") || suite.equals("http") || suite.equals("privacy")) http(fixtures);
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> entry : fixtures.entrySet())
            encoded.append(entry.getKey()).append('=').append(Base64.getEncoder().encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8))).append('\n');
        Files.writeString(output.resolve("browser-fixtures.txt"), encoded);
        System.out.println("SUSIMAIL_SECURITY_CHECKS=" + checks + "; FAILURES=" + failures);
        I2PAppContext.getGlobalContext().logManager().shutdown();
        System.exit(failures == 0 ? 0 : 1);
    }
}
