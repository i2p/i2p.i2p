<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 * Do not tag this file for translation.
 */

    //
    //  Redirect to either /home or /console, depending on configuration,
    //  while preserving any query parameters
    //
    response.setStatus(307);
    response.setHeader("Cache-Control","no-cache");
    String req = request.getRequestURL().toString();
    StringBuilder buf = new StringBuilder(128);
    if (req.endsWith("index"))
        req = req.substring(0, req.length() - 5);
    else if (req.endsWith("index.jsp"))
        req = req.substring(0, req.length() - 9);
    buf.append(req);
    if (!req.endsWith("/"))
        buf.append('/');
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    boolean oldHome = ctx.getBooleanProperty("routerconsole.oldHomePage");
    boolean wizRun = ctx.getBooleanProperty(net.i2p.router.web.helpers.WizardHelper.PROP_COMPLETE);
    String firstVersion = ctx.getProperty("router.firstVersion");
    String tgt;
    final boolean ENABLE_WIZARD_ON_FIRST_RUN = true;
    if (oldHome) {
        tgt = "console";
    } else if (!ENABLE_WIZARD_ON_FIRST_RUN || wizRun || firstVersion == null) {
        // wizard already run
        tgt = "home";
    } else {
        String version = net.i2p.CoreVersion.VERSION;
        if (version.equals(firstVersion)) {
            // first install 38 or later, still on same version
            tgt = "welcome";
        } else {
            // they already upgraded
            tgt = "home";
        }
    }
    buf.append(tgt);
    String query = request.getQueryString();
    if (query != null)
        buf.append('?').append(query);
    response.setHeader("Location", buf.toString());
    // force commitment
    response.getOutputStream().close();
%>