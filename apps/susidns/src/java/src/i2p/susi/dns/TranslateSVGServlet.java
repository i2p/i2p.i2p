package i2p.susi.dns;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.ServletException;

import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.util.TranslateReader;
 
/**
 * Translate a svg file on-the-fly.
 * svg file must contain strings tagged with _("...")
 * See TranslateReader for details.
 *
 * We don't bother parsing the XML and replacing entities,
 * since we had TranslateReader already.
 * If we want to use svg files unmodified, we'll have to parse the XML
 * and replace the title and tspan elements.
 *
 * Servlet path contains .svg files in the /svg/ directory.
 *
 * @since 0.9.34
 */
public class TranslateSVGServlet extends HttpServlet {
 
    private static final long serialVersionUID = 18638760L;
    private final I2PAppContext _context;
    private final String DIR = "/svg";

    public TranslateSVGServlet() {
        super();
        _context = I2PAppContext.getGlobalContext();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // this is the part after /images
        String path = req.getPathInfo();
        if (path == null || !path.endsWith(".svg")) {
            resp.sendError(404);
            return;
        }
        path = DIR + path;
        InputStream in = getServletContext().getResourceAsStream(path);
        if (in == null) {
            resp.sendError(404);
            return;
        }
        String etag = '"' + System.getProperty("router.version", CoreVersion.VERSION) + '"';
        // This isn't working, the header is coming back null
        String requestETag = req.getHeader("If-None-Match");
        if (etag.equals(requestETag)) {
            resp.setStatus(304);
            resp.getOutputStream().close();
            return;
        }

        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Accept-Ranges", "none");
        resp.setDateHeader("Expires", _context.clock().now() + 86400000L);
        resp.setHeader("Cache-Control", "public, max-age=86400");
        resp.setHeader("Pragma", "no-cache");
        resp.setContentType("image/svg+xml");
        resp.setHeader("ETag", etag);

        PrintWriter out = resp.getWriter();
        TranslateReader tr = null;
        try {
            tr = new TranslateReader(_context, Messages.BUNDLE_NAME, in);
            char[] buf = new char[256];
            int read;
            while ((read = tr.read(buf)) != -1) {
                out.write(buf, 0, read);
            }   
        } finally {
            if (tr != null) try { tr.close(); } catch (IOException ioe) {}
            try { in.close(); } catch (IOException ioe) {}
            out.close();
        }
    }
}
