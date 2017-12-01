<jsp:useBean class="net.i2p.router.web.helpers.ReseedGenerator" id="gen" scope="request" /><jsp:setProperty name="gen" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" /><%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 * Do not tag this file for translation.
 */
try {
    java.io.InputStream in = null;
    java.io.File zip = null;
    try {
        zip = gen.createZip();
        response.setContentLength((int) zip.length());
        long lastmod = zip.lastModified();
        if (lastmod > 0)
            response.setDateHeader("Last-Modified", lastmod);
        response.setDateHeader("Expires", 0);
        response.addHeader("Cache-Control", "no-store, max-age=0, no-cache, must-revalidate");
        response.addHeader("Pragma", "no-cache");
        response.setContentType("application/zip; name=\"i2preseed.zip\"");
        response.addHeader("Content-Disposition", "attachment; filename=\"i2preseed.zip\"");
        in = new java.io.FileInputStream(zip);
        java.io.OutputStream cout = response.getOutputStream();
        net.i2p.data.DataHelper.copy(in, cout);
    } finally {
        if (in != null) 
            try { in.close(); } catch (java.io.IOException ioe) {}
        if (zip != null)
            zip.delete();
    }
} catch (java.io.IOException ioe) {
    // prevent 'Committed' IllegalStateException from Jetty
    if (!response.isCommitted()) {
        response.sendError(403, ioe.toString());
    }  else {
        // Jetty doesn't log this
        throw ioe;
    }
}
%>