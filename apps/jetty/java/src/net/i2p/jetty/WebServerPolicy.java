package net.i2p.jetty;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.server.handler.SizeLimitHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.Callback;

/** Shared protections for TCP and QUIC requests, outside application handlers. */
public final class WebServerPolicy {
    private WebServerPolicy() {}

    public static Handler protect(Handler application, double requestsPerSecond, int burst, long maxRequestBytes) {
        if (maxRequestBytes <= 0) throw new IllegalArgumentException("Positive request body limit required");
        SizeLimitHandler size = new SizeLimitHandler(maxRequestBytes, -1);
        size.setHandler(application);
        RequestRateLimitHandler rate = new RequestRateLimitHandler();
        rate.setRequestsPerSecond(requestsPerSecond);
        rate.setBurst(burst);
        rate.setHandler(size);
        Handler.Wrapper headers = new Handler.Wrapper(rate) {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                if (!response.getHeaders().contains("X-Content-Type-Options"))
                    response.getHeaders().put("X-Content-Type-Options", "nosniff");
                if (!response.getHeaders().contains("Referrer-Policy"))
                    response.getHeaders().put("Referrer-Policy", "no-referrer");
                return super.handle(request, response, callback);
            }
        };
        return new StatisticsHandler(new GracefulHandler(headers));
    }
}
