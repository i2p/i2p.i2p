package net.i2p.router.web;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import net.i2p.I2PAppContext;

import org.mortbay.http.HttpRequest;
import org.mortbay.http.HttpResponse;
import org.mortbay.jetty.servlet.WebApplicationHandler;

/**
 * Convert foo.jsp to foo_xx.jsp for language xx.
 * This is appropriate for jsps with large amounts of text.
 *
 * Also, as of 0.8.2, rewrite "/" and "/index.html" to "/index.jsp",x
 * and "/foo" to "/foo.jsp".
 *
 * @author zzz
 */
public class LocaleWebAppHandler extends WebApplicationHandler
{
    private I2PAppContext _context;

    public LocaleWebAppHandler(I2PAppContext ctx) {
        super();
        _context = ctx;
    }
    
    /**
     *  Handle foo.jsp by converting to foo_xx.jsp
     *  for language xx, where xx is the language for the default locale,
     *  or as specified in the routerconsole.lang property.
     *  Unless language==="en".
     */
    @Override
    public void handle(String pathInContext,
                       String pathParams,
                       HttpRequest httpRequest,
                       HttpResponse httpResponse)
         throws IOException
    {
        // Handle OPTIONS (nothing to override)
        if (HttpRequest.__OPTIONS.equals(httpRequest.getMethod()))
        {
            handleOptions(httpRequest, httpResponse);
            return;
        }

        // transparent rewriting
        if (pathInContext.equals("/") || pathInContext.equals("/index.html")) {
            // home page
            pathInContext = "/index.jsp";
        } else if (pathInContext.indexOf("/", 1) < 0 &&
                   (!pathInContext.endsWith(".jsp")) &&
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
                    Map.Entry servlet = getHolderEntry(testPath);
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
        }
        //System.err.println("New path: " + newPath);
        super.handle(newPath, pathParams, httpRequest, httpResponse);
        //System.err.println("Was handled? " + httpRequest.isHandled());
    }

    /**
     *  Overrides method in ServletHandler
     *  @since 0.8
     */
    @Override
    public void handleTrace(HttpRequest request,
                            HttpResponse response)
        throws IOException
    {
        response.sendError(HttpResponse.__405_Method_Not_Allowed);
    }

    /**
     *  Not an override
     *  @since 0.8
     */
    public void handleOptions(HttpRequest request,
                              HttpResponse response)
        throws IOException
    {
        response.sendError(HttpResponse.__405_Method_Not_Allowed);
    }
}
