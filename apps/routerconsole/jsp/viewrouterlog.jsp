<%@page pageEncoding="UTF-8"%><%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 * Do not tag this file for translation.
 */
net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
net.i2p.util.LogManager mgr = ctx.logManager();
mgr.flush();
java.io.File f = new java.io.File(mgr.currentFile());
long length = f.length();
if (length <= 0 || !f.isFile()) {
    response.sendError(404, "Not Found");
} else {
    response.setContentType("text/plain");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Accept-Ranges", "none");
    response.setHeader("Content-Length", Long.toString(length));
    response.setDateHeader("Expires", 0);
    response.addHeader("Cache-Control", "no-store, max-age=0, no-cache, must-revalidate");
    response.addHeader("Pragma", "no-cache");
    java.io.InputStream in = null;
    try {
        in = new java.io.FileInputStream(f);
        java.io.OutputStream bout = response.getOutputStream();
        net.i2p.data.DataHelper.copy(in, bout);
    } catch (java.io.IOException ioe) {
        // prevent 'Committed' IllegalStateException from Jetty
        if (!response.isCommitted()) {
            response.sendError(403, ioe.toString());
        }  else {
            // not an error, happens when the browser closes the stream
            // Jetty doesn't log this
            throw ioe;
        }
    } finally {
        if (in != null) 
            try { in.close(); } catch (java.io.IOException ioe) {}
    }
}
%>