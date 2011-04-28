<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 * Do not tag this file for translation.
 */
response.setContentType("text/plain");
String base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath();
try {
    net.i2p.util.FileUtil.readFile("history.txt", base, response.getOutputStream());
} catch (java.io.IOException ioe) {
    // prevent 'Committed' IllegalStateException from Jetty
    if (!response.isCommitted()) {
        response.sendError(403, ioe.toString());
    }  else {
        net.i2p.I2PAppContext.getGlobalContext().logManager().getLog(getClass()).error("Error serving history.txt", ioe);
        // Jetty doesn't log this
        throw ioe;
    }
}
%>