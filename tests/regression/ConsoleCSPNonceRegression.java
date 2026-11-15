package net.i2p.servlet;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.FilterHolder;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;

import net.i2p.router.web.CSSHelper;

/** Sends real HTTP requests through Jetty without opening network sockets. */
public class ConsoleCSPNonceRegression {
    private static int checks, failures;
    private static void check(boolean passed, String message) {
        checks++;
        if (!passed) { failures++; System.out.println("FAIL: " + message); }
    }

    private static void nonces() throws Exception {
        Method generator;
        try { generator = CSSHelper.class.getMethod("getCSPNonce"); }
        catch (NoSuchMethodException expected) {
            check(false, "console exposes the 128-bit CSP nonce generator");
            return;
        }
        Set<String> samples = new HashSet<String>();
        boolean valid = true;
        for (int i = 0; i < 512; i++) {
            String nonce = (String) generator.invoke(null);
            valid &= Base64.getDecoder().decode(nonce).length == 16;
            samples.add(nonce);
        }
        check(valid, "every sampled CSP nonce contains 128 encoded bits");
        check(samples.size() == 512, "responses receive distinct nonce samples");
    }

    public static void main(String[] args) throws Exception {
        nonces();
        System.out.println("DEFAULT_WEB_SECURITY_CHECKS=" + checks + "; FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
