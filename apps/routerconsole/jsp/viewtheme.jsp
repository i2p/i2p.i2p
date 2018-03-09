<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 * Do not tag this file for translation.
 */

String uri = request.getRequestURI();
if (uri.endsWith(".css")) {
  response.setContentType("text/css");
} else if (uri.endsWith(".png")) {
  response.setContentType("image/png");
} else if (uri.endsWith(".gif")) {
  response.setContentType("image/gif");
} else if (uri.endsWith(".jpg")) {
  response.setContentType("image/jpeg");
} else if (uri.endsWith(".ico")) {
  response.setContentType("image/x-icon");
} else if (uri.endsWith(".svg")) {
  response.setContentType("image/svg+xml");
}
response.setHeader("Accept-Ranges", "none");
response.setHeader("X-Content-Type-Options", "nosniff");
/*
 * User or plugin themes
 * If the request is for /themes/console/foo/bar/baz,
 * and the property routerconsole.theme.foo=/path/to/foo,
 * get the file from /path/to/foo/bar/baz
 */
String themePath = null;
final String PFX = "/themes/console/";
if (uri.startsWith(PFX) && uri.length() > PFX.length() + 1) {
    String theme = uri.substring(PFX.length());
    int slash = theme.indexOf('/');
    if (slash > 0) {
        theme = theme.substring(0, slash);
        themePath = net.i2p.I2PAppContext.getGlobalContext().getProperty("routerconsole.theme." + theme);
        if (themePath != null)
            uri = uri.substring(PFX.length() + theme.length()); // /bar/baz
    }
}
String base;
if (themePath != null)
    base = themePath;
else
    base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath() +
              java.io.File.separatorChar + "docs";
java.io.File file = new java.io.File(base, uri);
long lastmod = file.lastModified();
if (lastmod > 0) {
    long iflast = request.getDateHeader("If-Modified-Since");
    // iflast is -1 if not present; round down file time
    if (iflast >= ((lastmod / 1000) * 1000)) {
        response.setStatus(304);
        return;
    }
    response.setDateHeader("Last-Modified", lastmod);
    // cache for a day
    response.setDateHeader("Expires", net.i2p.I2PAppContext.getGlobalContext().clock().now() + 86400000l);
    response.setHeader("Cache-Control", "public, max-age=86400");
}
long length = file.length();
if (length > 0)
    response.setHeader("Content-Length", Long.toString(length));
try {
    net.i2p.util.FileUtil.readFile(uri, base, response.getOutputStream());
} catch (java.io.IOException ioe) {
    // prevent 'Committed' IllegalStateException from Jetty
    if (!response.isCommitted()) {
        response.sendError(403, ioe.toString());
    }  else {
        // not an error, happens when the browser closes the stream
        net.i2p.I2PAppContext.getGlobalContext().logManager().getLog(getClass()).warn("Error serving " + uri, ioe);
        // Jetty doesn't log this
        throw ioe;
    }
}
%>