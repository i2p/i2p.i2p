<%
try {
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String family = ctx.getProperty("netdb.family.name");
    String keypw = ctx.getProperty("netdb.family.keyPassword");
    String kspw = ctx.getProperty("netdb.family.keystorePassword", "changeit");
    if (family == null || keypw == null) {
        response.sendError(404);
        return;
    }
    try {
        response.setDateHeader("Expires", 0);
        response.addHeader("Cache-Control", "no-store, max-age=0, no-cache, must-revalidate");
        response.addHeader("Pragma", "no-cache");
        String name = "family-" + family + "-secret.crt";
        response.setContentType("application/x-x509-ca-cert; name=\"" + name + '"');
        response.addHeader("Content-Disposition", "attachment; filename=\"" + name + '"');
        java.io.File ks = new java.io.File(ctx.getConfigDir(), "keystore");
        ks = new java.io.File(ks, "family-" + family + ".ks");
        java.io.OutputStream cout = response.getOutputStream();
        net.i2p.crypto.KeyStoreUtil.exportPrivateKey(ks, kspw, family, keypw, cout);
    } catch (java.security.GeneralSecurityException gse) {
        throw new java.io.IOException("key error", gse);
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
// don't worry about a newline after this
%>
