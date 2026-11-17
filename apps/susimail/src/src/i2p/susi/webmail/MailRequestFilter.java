package i2p.susi.webmail;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

/** Bound form work without rewriting or logging passwords, addresses or message text. */
public final class MailRequestFilter implements Filter {
    private static final long MAX_REQUEST = 7629902;
    private static final Semaphore POSTS = new Semaphore(8);
    @Override public void init(FilterConfig config) {}
    @Override public void destroy() {}

    @Override public void doFilter(ServletRequest supplied, ServletResponse output, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) supplied;
        HttpServletResponse response = (HttpServletResponse) output;
        String method = request.getMethod();
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (!(method.equals("GET") || method.equals("HEAD") || method.equals("POST"))) {
            response.setHeader("Allow", "GET, HEAD, POST"); response.sendError(405); return;
        }
        if (request.getContentLengthLong() > MAX_REQUEST || (request.getQueryString() != null && request.getQueryString().length() > 16384)) {
            response.sendError(413, "Mail request exceeds the size limit"); return;
        }
        boolean post = method.equals("POST");
        if (post && !POSTS.tryAcquire()) { response.sendError(429, "Too many concurrent mail submissions"); return; }
        try {
            request.setCharacterEncoding("UTF-8");
            String type = request.getContentType();
            if (type != null && type.toLowerCase(Locale.US).startsWith("multipart/form-data")) {
                if (!post) { response.sendError(400, "Multipart mail forms require POST"); return; }
                // Servlet multipart limits apply before parts are exposed. Keep uploads in memory until encryption.
                Set<String> names = new HashSet<String>(); int count = 0; long total = 0;
                for (Part part : request.getParts()) {
                    total += part.getSize();
                    if (++count > 512 || total > MAX_REQUEST || !validName(part.getName()) || !names.add(part.getName())
                            || (part.getSubmittedFileName() == null && part.getSize() > 65536)) {
                        response.sendError(400, "Invalid or excessive mail form fields"); return;
                    }
                }
            } else {
                Map<String, String[]> parameters = request.getParameterMap();
                if (parameters.size() > 512) { response.sendError(400, "Too many mail form fields"); return; }
                for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
                    String[] values = entry.getValue();
                    if (!validName(entry.getKey()) || values == null || values.length != 1 || values[0] == null || values[0].length() > 65536) {
                        response.sendError(400, "Invalid or ambiguous mail form fields"); return;
                    }
                }
            }
            chain.doFilter(request, response);
        } catch (IllegalStateException excessive) {
            if (!response.isCommitted()) response.sendError(413, "Mail form exceeds the size limit");
        } finally { if (post) POSTS.release(); }
    }
    private static boolean validName(String name) { return name != null && name.length() <= 256 && name.matches("[A-Za-z0-9_.~=-]+"); }
}
