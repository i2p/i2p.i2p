package org.klomp.snark;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppManagerImpl;
import net.i2p.app.ClientAppState;
import net.i2p.app.NotificationService;
import net.i2p.data.DataHelper;
import net.i2p.servlet.filters.XSSRequestWrapper;
import net.i2p.update.UpdateManager;
import net.i2p.update.UpdateType;
import net.i2p.util.SimpleTimer2;

import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.web.I2PSnarkServlet;

/** Independent integration regression; no network sockets or downloads. */
public final class SnarkNotificationRegression extends SnarkIntegrationSupport {
    public static void main(String[] args) throws Exception {
        TestContext context = new TestContext(Files.createTempDirectory("snark-integration-"));
        context.logManager().setDefaultLimit("CRIT");
        try {
            notifications(context);
            System.out.println("SNARKNOTIFICATIONREGRESSION_CHECKS=" + checks);
        } finally { context.logManager().shutdown(); }
    }
    private static void notifications(TestContext context) throws Exception {
        TestManager manager = new TestManager(context, "/my-snark", "notification-test");
        AtomicReference<String> notifiedPath = new AtomicReference<>();
        boolean[] fail = {false};
        ClientApp desktop = (ClientApp) Proxy.newProxyInstance(ClientApp.class.getClassLoader(),
                new Class<?>[] {ClientApp.class, NotificationService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "desktopgui";
                    if (method.getName().equals("notify")) {
                        if (fail[0]) throw new IllegalStateException("controlled notification failure");
                        notifiedPath.set((String) args[5]);
                        return 1;
                    }
                    return defaultValue(proxy, method, args);
                });
        context.apps.register(desktop);
        try {
            String name = "Episode [1] & #100%+é.txt";
            StubStorage storage = new StubStorage(manager.util(), context.work.resolve(name).toFile(), meta(false, name));
            StubSnark snark = new StubSnark(manager.util(), storage);
            storage.finished = true;
            String url = manager.getTorrentURL(snark);
            URI parsed = new URI(url);
            check(parsed.getPath().equals("/my-snark/" + name), "file notification path round trips special characters");
            check(parsed.getQuery() == null && parsed.getFragment() == null, "file name cannot become a query or fragment");
            check(!url.contains(" ") && !url.contains("&") && !url.contains("<"), "notification receives a URL, not HTML");
            manager.torrentComplete(snark);
            check(url.equals(notifiedPath.get()), "completion notification uses this instance's URL");
            check(manager.statusWrites == 1, "completion still saves status");
            Method linkify = SnarkManager.class.getDeclaredMethod("linkify", Snark.class);
            linkify.setAccessible(true);
            String html = (String) linkify.invoke(manager, snark);
            check(html.contains("href=\"" + DataHelper.escapeHTML(url) + "\""), "HTML completion link uses same encoded target");
            check(html.contains(DataHelper.escapeHTML(name)), "HTML completion label remains escaped");
            int bubbles = context.apps.bubbles;
            fail[0] = true;
            manager.torrentComplete(snark);
            check(context.apps.bubbles == bubbles + 1 && manager.statusWrites == 2,
                    "notification service failure does not lose console notice or saved status");
            storage.finished = false;
            check(manager.getTorrentURL(snark).endsWith("/"), "incomplete torrent links to its directory");
            StubStorage directory = new StubStorage(manager.util(), context.work.resolve("folder & files").toFile(), meta(true, "folder & files"));
            directory.finished = true;
            check(manager.getTorrentURL(new StubSnark(manager.util(), directory)).endsWith("/"),
                    "complete multi-file torrent links to its directory");
            Snark magnet = new Snark(manager.util(), "magnet", new byte[20], null, null, null, null, null);
            check(manager.getTorrentURL(magnet).equals("/my-snark/"), "magnet links to its own application");
        } finally {
            context.apps.unregister(desktop);
        }
    }

}
