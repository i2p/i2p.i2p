<%@page contentType="text/html"%><%@page pageEncoding="UTF-8"%><jsp:useBean class="net.i2p.router.web.helpers.WizardHelper" id="wizhelper" scope="session" /><%
        String i2pcontextId = request.getParameter("i2p.contextId");
        try {
            if (i2pcontextId != null) {
                session.setAttribute("i2p.contextId", i2pcontextId);
            } else {
                i2pcontextId = (String) session.getAttribute("i2p.contextId");
            }
        } catch (IllegalStateException ise) {}
        // Browser should not load this directly
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'none'; script-src 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'; media-src 'none'");
        response.setHeader("X-XSS-Protection", "1; mode=block");
        response.setHeader("X-Content-Type-Options", "nosniff");
        wizhelper.setContextId(i2pcontextId);
        // output 1 for complete, 0 + status string for in progress
        if (wizhelper.isNDTComplete()) {
%>1<%
        } else {
%>0<%=wizhelper.getTestStatus()%><%
        }
%>
