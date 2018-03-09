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
 *  flags.jsp?c=de&s=48 => icons/flags48x48/de.png
 *  with headers set so the browser caches.
 */
String c = request.getParameter("c");
if (c != null &&
    (c.length() == 2 || c.length() == 7) &&
    c.replaceAll("[a-z0-9_]", "").length() == 0) {
    String flagSet = "flags";
    String s = request.getParameter("s");
    if ("48".equals(s)) {
        flagSet = "flags48x48";
    }
    java.io.OutputStream cout = response.getOutputStream();
    String base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath();
    String file = "docs" + java.io.File.separatorChar + "icons" + java.io.File.separatorChar +
                  flagSet + java.io.File.separatorChar + c + ".png";
    java.io.File ffile = new java.io.File(base, file);
    long lastmod = ffile.lastModified();
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
        response.setHeader("Cache-Control", "public, max-age=604800");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }
    long length = ffile.length();
    if (length > 0)
        response.setHeader("Content-Length", Long.toString(length));
    response.setContentType("image/png");
    response.setHeader("Accept-Ranges", "none");
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