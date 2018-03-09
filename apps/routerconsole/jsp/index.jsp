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
    String req = request.getRequestURL().toString();
    StringBuilder buf = new StringBuilder(128);
    if (req.endsWith("index"))
        req = req.substring(0, req.length() - 5);
    else if (req.endsWith("index.jsp"))
        req = req.substring(0, req.length() - 9);
    buf.append(req);
    if (!req.endsWith("/"))
        buf.append('/');
    boolean oldHome = net.i2p.I2PAppContext.getGlobalContext().getBooleanProperty("routerconsole.oldHomePage");
    if (oldHome)
        buf.append("console");
    else
        buf.append("home");
    String query = request.getQueryString();
    if (query != null)
        buf.append('?').append(query);
    response.setHeader("Location", buf.toString());
    // force commitment
    response.getOutputStream().close();
%>