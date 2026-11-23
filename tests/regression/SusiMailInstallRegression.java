package i2p.susi.webmail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.ee8.webapp.WebAppContext;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;

import net.i2p.I2PAppContext;
import net.i2p.util.InternalServerSocket;

/** Deploy the normal Ant WAR, including web.xml and static assets; no network sockets or real accounts. */
public final class SusiMailInstallRegression {
    private static int checks, failures;
    private static final String PASSWORD = " WireSecret!<>&'\"$ 123 ";
    private static final String MESSAGE = "From: sender@example.test\r\nTo: installed@mail.i2p\r\nSubject: installed fixture\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"
        + "<p style='font-family:evil'>Installed message body</p><a href='https://example.test/path?a=1&amp;b=2'>Visit</a>\r\n";
    private static final List<String> problems = java.util.Collections.synchronizedList(new ArrayList<String>());
    private static LocalConnector connector;
    private static String cookie = "";
    private static final class RouterFixture extends I2PAppContext {
        RouterFixture(Properties props) { super(props); }
        @Override public boolean isRouterContext() { return true; }
    }
    private static void check(boolean ok, String why) { checks++; if (!ok) { failures++; System.out.println("FAIL: " + why); } }
    private static String header(String response, String name) {
        for (String line : response.substring(0, response.indexOf("\r\n\r\n")).split("\r\n"))
            if (line.regionMatches(true, 0, name + ':', 0, name.length() + 1)) return line.substring(name.length() + 1).trim();
        return "";
    }
    private static String request(String method, String path, String type, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String response = connector.getResponse(method + " " + path + " HTTP/1.1\r\nHost: mail.test\r\nConnection: close\r\n"
            + (cookie.isEmpty() ? "" : "Cookie: " + cookie + "\r\n")
            + (type.isEmpty() ? "" : "Content-Type: " + type + "\r\nContent-Length: " + bytes.length + "\r\n") + "\r\n" + body);
        String set = header(response, "Set-Cookie"); if (!set.isEmpty()) cookie = set.split(";", 2)[0];
        return response;
    }
    private static String get(String path) throws Exception { return request("GET", path, "", ""); }
    private static String form(String... fields) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < fields.length; i += 2) {
            if (i != 0) result.append('&');
            result.append(URLEncoder.encode(fields[i], StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(fields[i + 1], StandardCharsets.UTF_8));
        }
        return result.toString();
    }
    private static String post(String path, String fields) throws Exception { return request("POST", path, "application/x-www-form-urlencoded", fields); }
    private static String nonce(String response) {
        Matcher match = Pattern.compile("name=\"susiNonce\" value=\"([^\"]+)\"").matcher(response);
        if (!match.find()) throw new AssertionError("Missing CSRF token: " + response.substring(0, Math.min(800, response.length())));
        return match.group(1);
    }
    private static String waitForInbox() throws Exception {
        for (int i = 0; i < 100; i++) {
            String page = get("/susimail/");
            if (page.contains("installed fixture")) return page;
            if (page.contains("name=\"pass\"")) throw new AssertionError("Login failed: " + page);
            Thread.sleep(50);
        }
        throw new AssertionError("Installed mailbox did not finish loading");
    }
    private static String totp(byte[] key) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(System.currentTimeMillis() / 30000).array());
        int offset = hash[hash.length - 1] & 15;
        int value = java.nio.ByteBuffer.wrap(hash, offset, 4).getInt() & 0x7fffffff;
        return String.format(java.util.Locale.US, "%06d", value % 1000000);
    }
    private static byte[] decode32(String text) {
        ByteArrayOutputStreamHelper bytes = new ByteArrayOutputStreamHelper(); int word = 0, bits = 0;
        for (char letter : text.toCharArray()) {
            int digit = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(letter); if (digit < 0) throw new IllegalArgumentException();
            word = (word << 5) | digit; bits += 5;
            if (bits >= 8) { bits -= 8; bytes.write((word >>> bits) & 255); }
        }
        return bytes.toByteArray();
    }
    private static final class ByteArrayOutputStreamHelper extends java.io.ByteArrayOutputStream {}

    private static final class Provider implements AutoCloseable {
        final InternalServerSocket listener = new InternalServerSocket(17660);
        final AtomicInteger passwords = new AtomicInteger(), deliveries = new AtomicInteger();
        volatile boolean running = true;
        final List<Socket> open = java.util.Collections.synchronizedList(new ArrayList<Socket>());
        Provider() throws IOException {
            Thread thread = new Thread(() -> {
                while (running) try {
                    Socket socket = listener.accept(); open.add(socket);
                    Thread client = new Thread(() -> serve(socket), "synthetic POP3 client"); client.setDaemon(true); client.start();
                } catch (IOException stopped) { if (running) problems.add(stopped.toString()); }
            }, "synthetic POP3 listener"); thread.setDaemon(true); thread.start();
        }
        private void serve(Socket socket) {
            try (Socket connection = socket) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.ISO_8859_1));
                OutputStream out = connection.getOutputStream(); reply(out, "+OK synthetic provider\r\n");
                String line; boolean authenticated = false;
                while ((line = in.readLine()) != null) {
                    if (line.equals("CAPA")) reply(out, "+OK capabilities\r\nUIDL\r\nTOP\r\n.\r\n");
                    else if (line.startsWith("USER ")) reply(out, "+OK user\r\n");
                    else if (line.startsWith("PASS ")) { authenticated = line.substring(5).equals(PASSWORD); if (authenticated) passwords.incrementAndGet(); reply(out, authenticated ? "+OK authenticated\r\n" : "-ERR wrong password\r\n"); }
                    else if (line.equals("QUIT")) { reply(out, "+OK goodbye\r\n"); break; }
                    else if (!authenticated) reply(out, "-ERR login required\r\n");
                    else if (line.equals("STAT")) reply(out, "+OK 1 " + MESSAGE.length() + "\r\n");
                    else if (line.equals("UIDL")) reply(out, "+OK listing\r\n1 installed-one\r\n.\r\n");
                    else if (line.equals("LIST")) reply(out, "+OK sizes\r\n1 " + MESSAGE.length() + "\r\n.\r\n");
                    else if (line.startsWith("RETR ") || line.startsWith("TOP ")) { deliveries.incrementAndGet(); reply(out, "+OK message\r\n" + MESSAGE + ".\r\n"); }
                    else if (line.startsWith("DELE ") || line.equals("RSET") || line.equals("NOOP")) reply(out, "+OK\r\n");
                    else { problems.add("Unexpected command type"); reply(out, "-ERR unsupported\r\n"); }
                }
            } catch (IOException closed) { if (running && !closed.getMessage().contains("closed")) problems.add(closed.toString()); }
        }
        private static void reply(OutputStream out, String response) throws IOException { out.write(response.getBytes(StandardCharsets.US_ASCII)); out.flush(); }
        @Override public void close() { running = false; listener.close(); synchronized(open) { for (Socket socket : open) try { socket.close(); } catch (IOException ignored) {} } }
    }

    private static void workflow(Path output, Path config, Provider provider) throws Exception {
        String login = get("/susimail/");
        check(login.startsWith("HTTP/1.1 200") && login.contains("Authenticator or recovery code"), "normal WAR serves login and optional MFA controls");
        check(header(login, "Cache-Control").contains("no-store"), "normal deployment keeps login responses out of caches");
        check(get("/susimail/themes/security.css").contains("--mail-bg") && get("/susimail/themes/mail-content.css").contains("font-family"), "normal WAR packages and serves the new local stylesheets");
        String duplicate = post("/susimail/", "pass=first&pass=second"); check(duplicate.startsWith("HTTP/1.1 400"), "installed request filter rejects ambiguous duplicate parameters");
        String oldCookie = cookie;
        String submitted = post("/susimail/", form("login","1","user","installed","pass",PASSWORD,"susiNonce",nonce(login)));
        check(submitted.startsWith("HTTP/1.1 303") && header(submitted, "Location").startsWith("/susimail/"), "fresh account login starts through a relative redirect");
        check(!cookie.equals(oldCookie), "normal deployment rotates the login session identifier");
        String inbox = waitForInbox();
        check(provider.passwords.get() > 0 && provider.deliveries.get() > 0, "normal installed login preserves password punctuation and spaces while authenticating and fetching");
        check(inbox.contains("Saved mail encrypted") && inbox.contains("Mail security"), "installed inbox exposes encryption status and security settings");
        String draftId = net.i2p.data.Base64.encode("installed-upload");
        String compose = get("/susimail/?newmsg=" + draftId);
        String boundary = "SyntheticMailBoundary";
        StringBuilder upload = new StringBuilder();
        String[] fields = {"susiNonce",nonce(compose),"newmsg",draftId,"new_subject","uploaded fixture","new_to","recipient@mail.i2p","new_text","draft text","new_upload","1"};
        for (int i = 0; i < fields.length; i += 2) upload.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(fields[i]).append("\"\r\n\r\n").append(fields[i+1]).append("\r\n");
        upload.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"new_filename\"; filename=\"report.txt\"\r\nContent-Type: application/octet-stream\r\n\r\n").append("PRIVATE-UPLOAD-FIXTURE".repeat(10000)).append("\r\n--").append(boundary).append("--\r\n");
        String uploaded = request("POST","/susimail/","multipart/form-data; boundary=" + boundary,upload.toString());
        check(uploaded.startsWith("HTTP/1.1 303"), "normal multipart upload completes through the installed filter and servlet limits");
        String reopenedDraft = get("/susimail/?newmsg=" + draftId);
        check(reopenedDraft.contains("report.txt") && reopenedDraft.contains("uploaded fixture"), "uploaded encrypted attachment survives normal draft reopening");
        boolean attachmentSealed = false;
        try (java.util.stream.Stream<Path> paths = Files.walk(config.resolve("susimail"))) {
            for (Path path : (Iterable<Path>) paths.filter(p -> Files.isRegularFile(p) && p.toString().contains("attachments"))::iterator) {
                byte[] stored = Files.readAllBytes(path);
                if (stored.length > 32 && stored[0]=='S' && stored[1]=='M' && stored[2]=='D' && !new String(stored,StandardCharsets.ISO_8859_1).contains("PRIVATE-UPLOAD-FIXTURE")) attachmentSealed = true;
            }
        }
        check(attachmentSealed, "normal upload writes authenticated ciphertext into the draft attachment cache");
        String settings = get("/susimail/?security=1");
        check(settings.contains("Two-factor protection available"), "TOTP stays disabled after ordinary first login");
        String forged = post("/susimail/?security=1", form("securityAction","blockSender","blockTarget","sender@example.test"));
        check(forged.contains("Invalid form submission") && forged.contains("0 blocked entries"), "security settings require the session CSRF token");
        String started = post("/susimail/?security=1", form("securityAction","startTOTP","securityPassword",PASSWORD,"susiNonce",nonce(settings)));
        Matcher setup = Pattern.compile("class=\"setup-key\">([A-Z2-7]+)</code>").matcher(started);
        check(setup.find(), "installed authenticator setup accepts the unmodified encryption password");
        if (!setup.find(0)) throw new AssertionError("No enrollment key");
        byte[] secret = decode32(setup.group(1));
        String enabled = post("/susimail/?security=1", form("securityAction","enableTOTP","securityPassword",PASSWORD,"securityCode",totp(secret),"susiNonce",nonce(started)));
        Matcher codes = Pattern.compile("<code>([a-f0-9]{8}(?:-[a-f0-9]{8}){3})</code>").matcher(enabled); List<String> recovery = new ArrayList<String>();
        while (codes.find()) recovery.add(codes.group(1));
        check(recovery.size() == 8 && enabled.contains("Two-factor protection on"), "installed TOTP enrollment confirms a code and displays recovery values once");
        check(!get("/susimail/?security=1").contains("Your new recovery codes"), "reloading settings does not reveal recovery codes again");
        Files.writeString(output.resolve("security-light.html"), settings.substring(settings.indexOf("<!DOCTYPE")));
        Files.writeString(output.resolve("security-mfa.html"), enabled.substring(enabled.indexOf("<!DOCTYPE")));
        String destination = get("/susimail/?openLink=" + URLEncoder.encode("https://example.test/path?a=1&b=2", StandardCharsets.UTF_8));
        check(destination.contains("Check link destination") && destination.contains("example.test/path?a=1&amp;b=2") && !destination.startsWith("HTTP/1.1 30"), "installed link check preserves query parameters and requires an explicit follow action");
        String message = get("/susimail/?show=" + net.i2p.data.Base64.encode("installed-one"));
        check(message.contains("Block this sender") && message.contains("sandbox=\"allow-same-origin\""), "installed message offers sender controls and isolated HTML");
        String raw = get("/susimail/?msg=" + net.i2p.data.Base64.encode("installed-one") + "&att=0");
        check(raw.contains("Installed message body") && !raw.contains("font-family:evil") && raw.contains("data-safe-link"), "normal attachment route removes author fonts and wraps links");
        check(header(raw, "Content-Security-Policy").contains("font-src 'none'"), "installed message CSP blocks custom fonts");
        String blocked = post("/susimail/", form("mailSafetyAction","block","msg",net.i2p.data.Base64.encode("installed-one"),"folder","cur","susiNonce",nonce(message)));
        String blockedSettings = get("/susimail/?security=1");
        check(blockedSettings.contains("1 blocked entries"), "message sender block action updates encrypted account policy");
        String blockedMessage = get("/susimail/?show=" + net.i2p.data.Base64.encode("installed-one"));
        check(blockedMessage.contains("Content from this blocked sender is restricted"), "blocked sender content is restricted in the actual message view");
        String loggedOut = post("/susimail/", form("logout","1","susiNonce",nonce(blockedMessage)));
        login = get("/susimail/");
        String missing = post("/susimail/", form("offline","1","user","installed","pass",PASSWORD,"susiNonce",nonce(login)));
        check(missing.contains("authenticator or recovery code was not accepted"), "offline login cannot bypass enabled TOTP");
        String currentLogin = get("/susimail/");
        String recovered = post("/susimail/", form("offline","1","user","installed","pass",PASSWORD,"authCode",recovery.get(0),"susiNonce",nonce(currentLogin)));
        check(recovered.startsWith("HTTP/1.1 303") && get("/susimail/?security=1").contains("7 recovery codes remaining"), "recovery code unlocks the installed offline mailbox and is consumed");
        String secure = get("/susimail/?security=1");
        post("/susimail/", form("logout","1","susiNonce",nonce(secure))); login = get("/susimail/");
        String replay = post("/susimail/", form("offline","1","user","installed","pass",PASSWORD,"authCode",recovery.get(0),"susiNonce",nonce(login)));
        check(replay.contains("authenticator or recovery code was not accepted"), "installed login rejects reuse of a recovery code");
        byte[] passwordBytes = PASSWORD.getBytes(StandardCharsets.UTF_8);
        boolean plaintext = false; int encrypted = 0;
        try (java.util.stream.Stream<Path> paths = Files.walk(config.resolve("susimail"))) {
            for (Path file : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                byte[] bytes = Files.readAllBytes(file); String text = new String(bytes, StandardCharsets.ISO_8859_1);
                if (text.contains("Installed message body") || text.contains(PASSWORD) || recovery.stream().anyMatch(text::contains)) plaintext = true;
                if (bytes.length >= 4 && bytes[0]=='S' && bytes[1]=='M' && bytes[2]=='D') encrypted++;
            }
        }
        check(!plaintext && encrypted >= 3, "installed cache and security profile contain encrypted data rather than passwords, messages or recovery values");
        Arrays.fill(secret, (byte) 0); Arrays.fill(passwordBytes, (byte) 0);
    }

    public static void main(String[] args) throws Exception {
        Path war = Path.of(args[0]).toAbsolutePath(), output = Path.of(args[1]).toAbsolutePath(); Files.createDirectories(output);
        Path config = Files.createTempDirectory("susimail-install-"); Properties props = new Properties(); props.setProperty("i2p.dir.config",config.toString()); props.setProperty("i2p.dir.base",config.toString());
        new RouterFixture(props);
        System.setProperty("susimail.host","127.0.0.1"); System.setProperty("susimail.ports.pop3","17660"); System.setProperty("susimail.ports.smtp","17659"); System.setProperty("susimail.pop3.leave.on.server","true");
        Server server = new Server(); connector = new LocalConnector(server); server.addConnector(connector);
        WebAppContext app = new WebAppContext(); app.setContextPath("/susimail"); app.setWar(war.toString()); app.setTempDirectory(config.resolve("webapp").toFile()); app.setExtractWAR(true); app.setParentLoaderPriority(true);
        app.setConfigurationClasses(new String[] {"org.eclipse.jetty.ee8.webapp.WebAppConfiguration","org.eclipse.jetty.ee8.webapp.WebInfConfiguration","org.eclipse.jetty.ee8.webapp.WebXmlConfiguration","org.eclipse.jetty.ee8.webapp.MetaInfConfiguration","org.eclipse.jetty.ee8.webapp.FragmentConfiguration","org.eclipse.jetty.ee8.webapp.JettyWebXmlConfiguration"});
        server.setHandler(app.getCoreContextHandler());
        try (Provider provider = new Provider()) {
            server.start(); check(app.isAvailable(), "normal WAR deploys with its shipped servlet/filter/multipart/session configuration");
            if (!app.isAvailable()) throw new AssertionError(app.getUnavailableException());
            workflow(output, config, provider);
        } finally { server.stop(); server.destroy(); }
        check(problems.isEmpty(), "synthetic provider completed without protocol errors: " + problems);
        System.out.println("SUSIMAIL_INSTALL_CHECKS=" + checks + "; FAILURES=" + failures);
        I2PAppContext.getGlobalContext().logManager().shutdown(); System.exit(failures == 0 ? 0 : 1);
    }
}
