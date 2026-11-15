package net.i2p.router.tunnel.pool;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import net.i2p.crypto.EncType;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.BuildRequestRecord;
import net.i2p.data.i2np.BuildResponseRecord;
import net.i2p.data.i2np.EncryptedBuildRecord;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.data.i2np.TunnelBuildReplyMessage;
import net.i2p.data.i2np.VariableTunnelBuildReplyMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.*;
import net.i2p.router.dummy.DummyNetworkDatabaseFacade;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelDispatcher;

/** Executes actual BuildHandler admission and decrypted-reply processing without transports. */
public final class TunnelBandwidthRegression {
    private static int checks, failures;
    private static final Hash SELF = hash(1), NEXT = hash(2), FROM = hash(3);

    public static void main(String[] args) throws Exception {
        System.setProperty("I2P_DISABLE_OUTPUT_OVERRIDE", "true");
        Context ctx = new Context();
        ctx.logManager().setDefaultLimit("CRIT");
        Executor exec = new Executor(ctx);
        BuildHandler handler = new BuildHandler(ctx, null, exec);
        ctx.dispatcher = new Dispatcher(ctx);
        try {
            Class<?> stateType = Class.forName("net.i2p.router.tunnel.pool.BuildHandler$BuildMessageState");
            Constructor<?> stateConstructor = stateType.getDeclaredConstructors()[0];
            stateConstructor.setAccessible(true);
            Method request = BuildHandler.class.getDeclaredMethod("handleReq", RouterInfo.class, stateType, BuildRequestRecord.class, Hash.class);
            request.setAccessible(true);
            String[] invalid = { "-1", "-2147483648", "-4294967", "2147484", "4294968", "2147483647", "999999999999", "bad", "" };
            for (String key : new String[] { "m", "r", "l" }) {
                for (String value : invalid) checkRequest(ctx, handler, request, stateConstructor, key, value, false);
                for (String value : new String[] { "0", "1", "+1", "0001" })
                    checkRequest(ctx, handler, request, stateConstructor, key, value, true);
                if (!key.equals("m")) checkRequest(ctx, handler, request, stateConstructor, key, "2147483", true);
            }
            Method reply = BuildHandler.class.getDeclaredMethod("handleReply", TunnelBuildReplyMessage.class, PooledTunnelCreatorConfig.class, long.class);
            reply.setAccessible(true);
            TunnelPool pool = new TunnelPool(ctx, null, new TunnelPoolSettings(NEXT, false), null);
            for (String value : new String[] { "0", "1", "2147483", "+1", "0001", "-1", "-4294967", "4294968", "2147484", "2147483647", "bad" }) {
                PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(ctx, 2, false, NEXT, pool);
                cfg.setPeer(0, SELF); cfg.setPeer(1, NEXT);
                cfg.setReplyOrder(Arrays.asList(0, 1));
                cfg.setExpiration(ctx.clock().now() + 600000);
                SessionKey key = new SessionKey(bytes(32, 17));
                byte[] ad = bytes(32, 19);
                cfg.setChaChaReplyKeys(1, key, ad);
                Properties options = new Properties(); options.setProperty("b", value);
                TunnelBuildReplyMessage message = new VariableTunnelBuildReplyMessage(ctx, 2);
                message.setRecord(0, new EncryptedBuildRecord(new byte[528]));
                message.setRecord(1, BuildResponseRecord.create(ctx, 0, key, ad, options));
                exec.result = null;
                reply.invoke(handler, message, cfg, 0L);
                int expected = value.equals("2147483") ? 2147483000
                        : (value.equals("1") || value.equals("+1") || value.equals("0001") ? 1000 : 0);
                check(exec.result == BuildExecutor.Result.SUCCESS, "malformed optional reply bandwidth does not break accepted tunnel: " + value);
                check(cfg.getAllocatedBW() == expected, "reply bandwidth is preserved or ignored safely: " + value + " (got " + cfg.getAllocatedBW() + ")");
            }
        } finally {
            ctx.dispatcher.shutdown(); ctx.bandwidthLimiter().shutdown();
            ctx.simpleTimer2().stop(); ctx.logManager().shutdown();
        }
        System.out.println("TUNNEL_BANDWIDTH_CHECKS=" + checks + "; FAILURES=" + failures);
        if (failures != 0) throw new AssertionError("bandwidth conversion/admission checks failed");
    }

    private static void checkRequest(Context ctx, BuildHandler handler, Method method, Constructor<?> constructor,
                                     String key, String value, boolean expected) throws Exception {
        Properties options = new Properties(); options.setProperty(key, value);
        SessionKey material = new SessionKey(bytes(32, 23));
        BuildRequestRecord record = new BuildRequestRecord(ctx, 11, 12, NEXT, 13, material, material, material,
                                                           new byte[16], true, false, options);
        Object state = constructor.newInstance(ctx, new TunnelBuildMessage(ctx), null, FROM);
        Boolean accepted = null;
        try { method.invoke(handler, new RouterInfo(), state, record, NEXT); }
        catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof Decision)) throw e;
            accepted = ((Decision) e.getCause()).accepted;
        }
        check(accepted != null && accepted == expected, "request " + key + "=" + value + " expected accepted=" + expected + ", got " + accepted);
    }
    private static void check(boolean ok, String description) { checks++; if (!ok) { failures++; System.out.println("FAIL: " + description); } }
    private static byte[] bytes(int size, int value) { byte[] b = new byte[size]; Arrays.fill(b, (byte) value); return b; }
    private static Hash hash(int value) { return new Hash(bytes(32, value)); }
    private static final class Decision extends RuntimeException {
        final boolean accepted;
        Decision(boolean accepted) { this.accepted = accepted; }
    }
    private static final class Executor extends BuildExecutor {
        Result result;
        Executor(RouterContext ctx) { super(ctx, null); }
        @Override public void buildComplete(PooledTunnelCreatorConfig cfg, Result result) { this.result = result; }
    }
    private static final class Dispatcher extends TunnelDispatcher {
        Dispatcher(RouterContext ctx) { super(ctx); super.shutdown(); }
        @Override public boolean joinInboundGateway(HopConfig cfg) { throw new Decision(true); }
        @Override public boolean joinOutbound(PooledTunnelCreatorConfig cfg) { return true; }
    }
    private static Properties properties() {
        Properties p = new Properties();
        p.setProperty("time.disabled", "true"); p.setProperty("i2np.allowLocal", "true");
        p.setProperty("i2p.dir.base", new File(".").getAbsolutePath());
        p.setProperty("i2p.dir.config", new File(".").getAbsolutePath());
        p.setProperty("i2np.bandwidth.outboundKBytesPerSecond", "1024");
        p.setProperty("i2np.bandwidth.inboundKBytesPerSecond", "1024");
        return p;
    }
    @SuppressWarnings("unchecked")
    private static <T> T noOp(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            Class<?> result = method.getReturnType();
            if (result == boolean.class) return true;
            if (result == int.class) return 0;
            if (result == long.class) return 0L;
            return null;
        });
    }
    private static final class TestRouter extends Router {
        final RouterInfo info = new RouterInfo();
        TestRouter() { super("bandwidth-test.config", properties()); Properties p = new Properties(); p.setProperty("caps", "X"); info.setOptions(p); }
        @Override public boolean isHidden() { return false; }
        @Override public RouterInfo getRouterInfo() { return info; }
    }
    private static final class Context extends RouterContext {
        private FIFOBandwidthLimiter limiter;
        private InNetMessagePool inbound;
        private JobQueue jobs;
        private KeyManager keys;
        Dispatcher dispatcher;
        final ProfileManager profiles = noOp(ProfileManager.class);
        final RouterThrottle throttle = noOp(RouterThrottle.class);
        final Comm comm = new Comm();
        final DummyNetworkDatabaseFacade db = new DummyNetworkDatabaseFacade(this) {
            @Override public RouterInfo lookupRouterInfoLocally(Hash hash) { return ((TestRouter) router()).info; }
        };
        Context() { super(new TestRouter(), properties()); }
        @Override protected void initializeClock() { _clock = new RouterClock(this); _clockInitialized = true; }
        @Override public boolean isRouterContext() { return false; }
        @Override public Hash routerHash() { return SELF; }
        @Override public synchronized FIFOBandwidthLimiter bandwidthLimiter() { if (limiter == null) limiter = new FIFOBandwidthLimiter(this); return limiter; }
        @Override public synchronized InNetMessagePool inNetMessagePool() { if (inbound == null) inbound = new InNetMessagePool(this); return inbound; }
        @Override public synchronized JobQueue jobQueue() { if (jobs == null) jobs = new JobQueue(this); return jobs; }
        @Override public synchronized KeyManager keyManager() {
            if (keys == null) keys = new KeyManager(this) {
                @Override public synchronized PrivateKey getPrivateKey() { return new PrivateKey(EncType.ECIES_X25519, bytes(32, 37)); }
            };
            return keys;
        }
        @Override public RouterThrottle throttle() { return throttle; }
        @Override public CommSystemFacade commSystem() { return comm; }
        @Override public ProfileManager profileManager() { return profiles; }
        @Override public NetworkDatabaseFacade netDb() { return db; }
        @Override public TunnelDispatcher tunnelDispatcher() { return dispatcher; }
        @Override public MessageHistory messageHistory() { throw new Decision(false); }
    }
    private static final class Comm extends CommSystemFacade {
        public void processMessage(OutNetMessage message) { throw new AssertionError("network access"); }
        public int countActivePeers() { return 1; }
        public int countActiveSendPeers() { return 1; }
        public boolean isEstablished(Hash hash) { return true; }
        public List<Hash> getEstablished() { return Collections.emptyList(); }
        public void startup() { throw new AssertionError("network startup"); }
        public void restart() { throw new AssertionError("network restart"); }
        public void shutdown() { }
    }
}
