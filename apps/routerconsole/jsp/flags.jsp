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
 *
 *  As of 0.9.36:
 *  All new and changed flags must go in the flags16x11/ dir,
 *  which will be checked first by flags.jsp.
 *  The flags/ dir is the original set from famfamfam,
 *  which may be symlinked in package installs.
 *
 */
String c = request.getParameter("c");
if (c != null &&
    (c.length() == 2 || c.length() == 7) &&
    c.replaceAll("[a-z0-9_]", "").length() == 0) {
    String flagSet = "flags16x11";
    String s = request.getParameter("s");

    String base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath() +
                  java.io.File.separatorChar +
                  "docs" + java.io.File.separatorChar + "icons";
    String file = flagSet + java.io.File.separatorChar + c + ".png";
    java.io.File ffile = new java.io.File(base, file);
    if (!ffile.exists()) {
        // fallback to flags dir, which will be symlinked to /usr/share/flags/countries/16x11 for package builds
        file = "flags" + java.io.File.separatorChar + c + ".png";
        ffile = new java.io.File(base, file);
    }
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
    java.io.FileInputStream fin = null;
    java.io.OutputStream cout = response.getOutputStream();
    try {
        // flags dir may be a symlink, which readFile will reject
        // We carefully vetted the "c" value above.
        //net.i2p.util.FileUtil.readFile(file, base, cout);
        fin = new java.io.FileInputStream(ffile);
        net.i2p.data.DataHelper.copy(fin, cout);
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
    } finally {
        if (fin != null)
            try { fin.close(); } catch (java.io.IOException ioe) {}
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