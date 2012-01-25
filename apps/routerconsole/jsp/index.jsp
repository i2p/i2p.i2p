<%@page contentType="text/plain"%>
<%@page pageEncoding="UTF-8"%>
<%
    response.setStatus(302, "Moved");
    String req = request.getRequestURI();
    if (req.endsWith("index"))
        req = req.substring(0, req.length() - 5);
    else if (req.endsWith("index.jsp"))
        req = req.substring(0, req.length() - 9);
    if (!req.endsWith("/"))
        req += '/';
    boolean oldHome = net.i2p.I2PAppContext.getGlobalContext().getBooleanProperty("routerconsole.oldHomePage");
    if (oldHome)
        req += "console";
    else
        req += "home";
    response.setHeader("Location", req);
%>
