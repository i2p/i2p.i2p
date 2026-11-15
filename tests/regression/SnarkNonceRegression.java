package org.klomp.snark.web;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpSession;
import net.i2p.I2PAppContext;

/** Check actual generators, bounded session queues and one-use validation. */
public final class SnarkNonceRegression {
    private static int checks;
    public static void main(String[] args) throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        context.logManager().setDefaultLimit("CRIT");
        try {
            I2PSnarkServlet servlet = new I2PSnarkServlet();
            Method token = method("randomToken");
            Set<String> sampled = new HashSet<String>();
            for (int i = 0; i < 512; i++) {
                String value = (String) token.invoke(servlet);
                check(Base64.getUrlDecoder().decode(value).length == 16, "nonce contains 128 random bits");
                check(sampled.add(value), "nonce sample is fresh");
            }
            Method create = method("getNonce", HttpSession.class, boolean.class);
            Method validate = method("validateNonce", HttpSession.class, String.class);
            HttpSession session = session(), other = session();
            String first = (String) create.invoke(servlet, session, false);
            check(first.startsWith("SNA") && Base64.getUrlDecoder().decode(first.substring(3)).length == 16,
                    "form nonce uses a full token");
            check(!valid(validate, other, first), "nonce is bound to its session");
            check(valid(validate, session, first) && !valid(validate, session, first), "nonce is consumed once");
            String oldest = (String) create.invoke(servlet, session, false);
            String newest = null;
            for (int i = 0; i < 10; i++) newest = (String) create.invoke(servlet, session, false);
            check(!valid(validate, session, oldest) && valid(validate, session, newest), "form queue is bounded to ten values");
            String xhr = (String) create.invoke(servlet, session, true);
            check(xhr.startsWith("SNB") && valid(validate, session, xhr) && !valid(validate, session, xhr),
                    "XHR queue retains one-use semantics");
            check(!valid(validate, null, newest) && !valid(validate, session, null), "missing session/token fails closed");
            System.out.println("SNARK_NONCE_CHECKS=" + checks);
        } finally { context.logManager().shutdown(); }
    }
    private static Method method(String name, Class<?>... types) throws Exception {
        Method result = I2PSnarkServlet.class.getDeclaredMethod(name, types);
        result.setAccessible(true);
        return result;
    }
    private static boolean valid(Method method, HttpSession session, String nonce) throws Exception {
        return ((Boolean) method.invoke(null, session, nonce)).booleanValue();
    }
    private static HttpSession session() {
        Map<String, Object> attributes = new HashMap<String, Object>();
        return (HttpSession) Proxy.newProxyInstance(HttpSession.class.getClassLoader(), new Class<?>[] {HttpSession.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getAttribute")) return attributes.get(args[0]);
                    if (method.getName().equals("setAttribute")) { attributes.put((String) args[0], args[1]); return null; }
                    if (method.getName().equals("toString")) return "test-session";
                    return null;
                });
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
}
