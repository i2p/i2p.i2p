<%@page contentType="text/plain"%>
<%@page pageEncoding="UTF-8"%>
<%
    //
    //  Redirect to either /home or /console, depending on configuration,
    //  while preserving any query parameters
    //
    response.setStatus(302, "Moved");
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
%>
