package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.i2p.I2PAppContext;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Convert foo.jsp to foo_xx.jsp for language xx.
 * This is appropriate for jsps with large amounts of text.
 *
 * Also, as of 0.8.2, rewrite "/" and "/index.html" to "/index.jsp",
 * and "/foo" to "/foo.jsp".
 *
 * @author zzz
 */
public class LocaleWebAppHandler extends HandlerWrapper
{
    private final I2PAppContext _context;
    private final WebAppContext _wac;

    public LocaleWebAppHandler(I2PAppContext ctx, String path, String warPath,
                               File tmpdir, ServletHandler servletHandler) {
        super();
        _context = ctx;
        _wac = new WebAppContext(warPath, path);
        setInitParams(WebAppStarter.INIT_PARAMS);
        _wac.setTempDirectory(tmpdir);
        _wac.setExtractWAR(false);
        _wac.setSessionHandler(new SessionHandler());
        _wac.setServletHandler(servletHandler);
        setHandler(_wac);
    }
    
    /**
     *  Handle foo.jsp by converting to foo_xx.jsp
     *  for language xx, where xx is the language for the default locale,
     *  or as specified in the routerconsole.lang property.
     *  Unless language == "en".
     */
    public void handle(String pathInContext,
                       Request baseRequest,
                       HttpServletRequest httpRequest,
                       HttpServletResponse httpResponse)
         throws IOException, ServletException
    {

        // transparent rewriting
        if (pathInContext.equals("/") || pathInContext.equals("/index.html")) {
            // home page
            pathInContext = "/index.jsp";
        } else if (pathInContext.indexOf('/', 1) < 0 &&
                   (!pathInContext.endsWith(".jsp")) &&
                   (!pathInContext.endsWith(".log")) &&
                   (!pathInContext.endsWith(".txt"))) {
            // add .jsp to pages at top level
            pathInContext += ".jsp";
        }

        //System.err.println("Path: " + pathInContext);
        String newPath = pathInContext;
        //if (pathInContext.endsWith(".jsp")) {
        // We only ended up doing this for help.jsp, so save some effort
        // unless we translate more pages like this
        if (pathInContext.equals("/help.jsp")) {
            int len = pathInContext.length();
            // ...but leave foo_xx.jsp alone
            if (len < 8 || pathInContext.charAt(len - 7) != '_') {
                String lang = _context.getProperty(Messages.PROP_LANG);
                if (lang == null || lang.length() <= 0)
                    lang = Locale.getDefault().getLanguage();
                if (lang != null && lang.length() > 0 && !lang.equals("en")) {
                    String testPath = pathInContext.substring(0, len - 4) + '_' + lang + ".jsp";
                    // Do we have a servlet for the new path that isn't the catchall *.jsp?
                    @SuppressWarnings("rawtypes")
                    Map.Entry servlet = _wac.getServletHandler().getHolderEntry(testPath);
                    if (servlet != null) {
                        String servletPath = (String) servlet.getKey();
                        if (servletPath != null && !servletPath.startsWith("*")) {
                            // success!!
                            //System.err.println("Servlet is: " + servletPath);
                            newPath = testPath;
                        }
                    }
                }
            }
        } else if (pathInContext.startsWith("/js/")) {
            // war internal
            httpResponse.setCharacterEncoding("ISO-8859-1");
        }
        //System.err.println("New path: " + newPath);
        super.handle(newPath, baseRequest, httpRequest, httpResponse);
        //System.err.println("Was handled? " + httpRequest.isHandled());
    }

    /**
     *  Overrides method in ServletHandler
     *  @since 0.8
     */
/****  not in Jetty 6
    @Override
    public void handleTrace(HttpServletRequest request,
                            HttpServletResponse response)
        throws IOException
    {
        response.sendError(405);
    }
****/

    /**
     *  Not an override
     *  @since 0.8
     */
/****  not in Jetty 7
    public void handleOptions(HttpServletRequest request,
                              HttpServletResponse response)
        throws IOException
    {
        response.sendError(405);
    }
****/

    /**
     *  Mysteriously removed from Jetty 7
     */
    private void setInitParams(Map<?,?> params) {
        setInitParams(_wac, params);
    }

    /**
     *  @since Jetty 7
     */
    public static void setInitParams(WebAppContext context, Map<?,?> params) {
        for (Map.Entry<?,?> e : params.entrySet()) {
            context.setInitParameter((String)e.getKey(), (String)e.getValue());
        }
    }
}
