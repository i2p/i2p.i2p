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
import net.i2p.router.web.helpers.TorrentHelper;
import net.i2p.servlet.filters.XSSRequestWrapper;
import net.i2p.update.UpdateManager;
import net.i2p.update.UpdateType;
import net.i2p.util.SimpleTimer2;

import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.web.I2PSnarkServlet;

/** Independent integration regression; no network sockets or downloads. */
public final class SnarkHandoffRegression extends SnarkIntegrationSupport {
    public static void main(String[] args) throws Exception {
        TestContext context = new TestContext(Files.createTempDirectory("snark-integration-"));
        context.logManager().setDefaultLimit("CRIT");
        try {
            prefilledLinks(context);
            System.out.println("SNARKHANDOFFREGRESSION_CHECKS=" + checks);
        } finally { context.logManager().shutdown(); }
    }
    private static void prefilledLinks(TestContext context) throws Exception {
        TestManager manager = new TestManager(context, "/i2psnark", "form-test");
        I2PSnarkServlet servlet = new I2PSnarkServlet();
        set(servlet, "_manager", manager);
        set(servlet, "_imgPath", "/i2psnark/.resources/icons/");
        Method render = I2PSnarkServlet.class.getDeclaredMethod("writeAddForm", PrintWriter.class,
                HttpServletRequest.class, String.class);
        render.setAccessible(true);
        Method incoming = I2PSnarkServlet.class.getDeclaredMethod("getAddURL", HttpServletRequest.class);
        incoming.setAccessible(true);
        for (String url : List.of("magnet:?xt=urn:btih:" + "a".repeat(40) +
                "&dn=é \"<episode>\"&tr=http://tracker.i2p/a?x=1&y=2",
                "http://site.i2p/é%20file.torrent?x=1&y=2", "\" onfocus=\"alert(1)\" <img src=x>")) {
            String bridge = TorrentHelper.getSnarkURL(url);
            URI parsed = URI.create(bridge);
            String decoded = URLDecoder.decode(parsed.getRawQuery().substring("nofilter_newURL=".length()), "UTF-8");
            check(decoded.equals(url), "console handoff preserves complete URL and tracker parameters");
            check(parsed.getPath().equals("/i2psnark/") && parsed.getFragment().equals("add"),
                    "handoff always targets the local add form");
            check(!parsed.getRawQuery().contains("&") && !bridge.contains("\""), "handoff cannot inject query parameters or HTML");
            for (String alias : List.of("newURL", "nofilter_newURL")) {
                HttpServletRequest request = new XSSRequestWrapper(request("GET", Map.of(alias, url)));
                check(TorrentHelper.getSnarkURLFromRequest(request).equals(bridge), "filtered console request preserves incoming URL");
                check(incoming.invoke(null, request).equals(url), "both incoming parameter names work");
                StringWriter output = new StringWriter();
                render.invoke(servlet, new PrintWriter(output), request, "fresh-review-nonce");
                String html = output.toString();
                check(html.contains("value=\"" + DataHelper.escapeHTML(url) + "\" spellcheck"),
                        "prefill renders intact inside an escaped input attribute");
                check(html.contains("name=\"nonce\" value=\"fresh-review-nonce\"") && html.contains("method=\"POST\""),
                        "adding a prefilled torrent still requires the normal POST form");
            }
        }
        check(TorrentHelper.getSnarkURL(null).equals("/i2psnark/") &&
                TorrentHelper.getSnarkURL("  ").equals("/i2psnark/"), "empty handoff retains normal navigation");
        check(incoming.invoke(null, request("POST", Map.of("newURL", "magnet:test"))).equals(""),
                "POST does not carry old prefilled values into the form");
        check(incoming.invoke(null, request("GET", Map.of("newURL", "fallback", "nofilter_newURL", "preferred")))
                .equals("preferred"), "canonical parameter takes precedence");
        check(manager.getTorrents().isEmpty(), "following or rendering a link does not add a torrent");
    }

}
