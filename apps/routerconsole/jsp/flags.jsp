<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 * Do not tag this file for translation.
 */

/**
 *  flags.jsp?c=de => icons/flags/de.png
 *  with headers set so the browser caches.
 */
String c = request.getParameter("c");
if (c != null && c.length() > 0) {
    java.io.OutputStream cout = response.getOutputStream();
    String base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath();
    String file = "docs" + java.io.File.separatorChar + "icons" + java.io.File.separatorChar +
                  "flags" + java.io.File.separatorChar + c + ".png";
    java.io.File ffile = new java.io.File(base, file);
    long lastmod = ffile.lastModified();
    if (lastmod > 0) {
        long iflast = request.getDateHeader("If-Modified-Since");
        // iflast is -1 if not present; round down file time
        if (iflast >= ((lastmod / 1000) * 1000)) {
            response.sendError(304, "Not Modified");
            return;
        }
        response.setDateHeader("Last-Modified", lastmod);
        // cache for a day
        response.setDateHeader("Expires", net.i2p.I2PAppContext.getGlobalContext().clock().now() + 86400000l);
        response.setHeader("Cache-Control", "public, max-age=86400");
    }
    long length = ffile.length();
    if (length > 0)
        response.setHeader("Content-Length", Long.toString(length));
    response.setContentType("image/png");
    try {
        net.i2p.util.FileUtil.readFile(file, base, cout);
    } catch (java.io.IOException ioe) {
        // prevent 'Committed' IllegalStateException from Jetty
        if (!response.isCommitted()) {
            response.sendError(403, ioe.toString());
        }  else {
            // not an error, happens when the browser closes the stream
            net.i2p.I2PAppContext.getGlobalContext().logManager().getLog(getClass()).warn("Error serving flags/" + c + ".png", ioe);
            // Jetty doesn't log this
            throw ioe;
        }
    }
} else {
    /*
     *  Send a 403 instead of a 404, because the server sends error.jsp
     *  for 404 errors, complete with the summary bar, which would be
     *  a huge load for a page full of flags if the user didn't have the
     *  flags directory for some reason.
     */
    response.sendError(403, "No flag specified");
}
%>