<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 */

/**
 *  flags.jsp?c=de => icons/flags/de.png
 *  with headers set so the browser caches.
 */
boolean rendered = false;
String c = request.getParameter("c");
if (c != null && c.length() > 0) {
    java.io.OutputStream cout = response.getOutputStream();
    response.setContentType("image/png");
    response.setHeader("Cache-Control", "max-age=86400");  // cache for a day
    String base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath();
    String file = "docs" + java.io.File.separatorChar + "icons" + java.io.File.separatorChar +
                  "flags" + java.io.File.separatorChar + c + ".png";
    try {
        net.i2p.util.FileUtil.readFile(file, base, cout);
        rendered = true;
    } catch (java.io.IOException ioe) {}
    if (rendered)
        cout.close();
}
/*
 *  Send a 403 instead of a 404, because the server sends error.jsp
 *  for 404 errors, complete with the summary bar, which would be
 *  a huge load for a page full of flags if the user didn't have the
 *  flags directory for some reason.
 */
if (!rendered)
    response.sendError(403, "Flag not found");
%>