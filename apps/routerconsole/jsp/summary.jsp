<%@page import="net.i2p.router.web.SummaryHelper" %>
<jsp:useBean class="net.i2p.router.web.SummaryHelper" id="helper" scope="request" />
<jsp:setProperty name="helper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<jsp:useBean class="net.i2p.router.web.ReseedHandler" id="reseed" scope="request" />
<jsp:setProperty name="reseed" property="*" />
<jsp:useBean class="net.i2p.router.web.UpdateHandler" id="update" scope="request" />
<jsp:setProperty name="update" property="*" />
<jsp:setProperty name="update" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="uhelper" scope="request" />
<jsp:setProperty name="uhelper" property="*" />
<jsp:setProperty name="uhelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<div class="routersummary">
 <center><b><a href="config.jsp">Configuration</a>&nbsp;&nbsp;<a href="help.jsp">Help</a></b></center>
 <hr />

 <u><b>General</b></u><br />
 <b>Ident:</b> (<a title="Your router identity is <jsp:getProperty name="helper" property="ident" />, never reveal it to anyone" href="netdb.jsp?r=.">view</a>)<br />
 <b>Version:</b> <jsp:getProperty name="helper" property="version" /><br />
 <b>Uptime:</b> <jsp:getProperty name="helper" property="uptime" /><br />
 <b>Now:</b> <jsp:getProperty name="helper" property="time" /><br />
 <b>Reachability:</b> <a href="config.jsp#help"><jsp:getProperty name="helper" property="reachability" /></a><%
    if (helper.updateAvailable()) {
        // display all the time so we display the final failure message
        out.print("<br />" + update.getStatus());
        if ("true".equals(System.getProperty("net.i2p.router.web.UpdateHandler.updateInProgress", "false"))) {
        } else {
            long nonce = new java.util.Random().nextLong();
            String prev = System.getProperty("net.i2p.router.web.UpdateHandler.nonce");
            if (prev != null) System.setProperty("net.i2p.router.web.UpdateHandler.noncePrev", prev);
            System.setProperty("net.i2p.router.web.UpdateHandler.nonce", nonce+"");
            String uri = request.getRequestURI();
            out.print("<p><form action=\"" + uri + "\" method=\"GET\">\n");
            out.print("<input type=\"hidden\" name=\"updateNonce\" value=\"" + nonce + "\" >\n");
            out.print("<input type=\"submit\" value=\"Download " + uhelper.getUpdateVersion() + " Update\" ></form></p>\n");
        }
    }
 %>
 <p>
 <%=net.i2p.router.web.ConfigRestartBean.renderStatus(request.getRequestURI(), request.getParameter("action"), request.getParameter("consoleNonce"))%>
 </p>
 <hr />
 
 <u><b><a href="peers.jsp">Peers</a></b></u><br />
 <b>Active:</b> <jsp:getProperty name="helper" property="activePeers" />/<jsp:getProperty name="helper" property="activeProfiles" /><br />
 <b>Fast:</b> <jsp:getProperty name="helper" property="fastPeers" /><br />
 <b>High capacity:</b> <jsp:getProperty name="helper" property="highCapacityPeers" /><br />
 <b>Well integrated:</b> <jsp:getProperty name="helper" property="wellIntegratedPeers" /><br />
 <b>Known:</b> <jsp:getProperty name="helper" property="allPeers" /><br /><%
    if (helper.getActivePeers() <= 0) {
        %><b><a href="config.jsp">check your NAT/firewall</a></b><br /><%
    }
    // If showing the reseed link is allowed
    if (helper.allowReseed()) {
        if ("true".equals(System.getProperty("net.i2p.router.web.ReseedHandler.reseedInProgress", "false"))) {
            // While reseed occurring, show status message instead
            out.print("<i>" + System.getProperty("net.i2p.router.web.ReseedHandler.statusMessage","") + "</i><br />");
        } else {
            // While no reseed occurring, show reseed link
            long nonce = new java.util.Random().nextLong();
            String prev = System.getProperty("net.i2p.router.web.ReseedHandler.nonce");
            if (prev != null) System.setProperty("net.i2p.router.web.ReseedHandler.noncePrev", prev);
            System.setProperty("net.i2p.router.web.ReseedHandler.nonce", nonce+"");
            String uri = request.getRequestURI();
            if (uri.indexOf('?') > 0)
                uri = uri + "&reseedNonce=" + nonce;
            else
                uri = uri + "?reseedNonce=" + nonce;
            out.print(" <a href=\"" + uri + "\">reseed</a><br />");
        }
    }
    // If a new reseed ain't running, and the last reseed had errors, show error message
    if ("false".equals(System.getProperty("net.i2p.router.web.ReseedHandler.reseedInProgress", "false"))) {
        String reseedErrorMessage = System.getProperty("net.i2p.router.web.ReseedHandler.errorMessage","");
        if (reseedErrorMessage.length() > 0) {
            out.print("<i>" + reseedErrorMessage + "</i><br />");
        }
    }
 %><hr />
 
 <u><b><a href="config.jsp" title="Configure the bandwidth limits">Bandwidth in/out</a></b></u><br />
 <b>1s:</b> <jsp:getProperty name="helper" property="inboundSecondKBps" />/<jsp:getProperty name="helper" property="outboundSecondKBps" />KBps<br />
 <b>5m:</b> <jsp:getProperty name="helper" property="inboundFiveMinuteKBps" />/<jsp:getProperty name="helper" property="outboundFiveMinuteKBps" />KBps<br />
 <b>Total:</b> <jsp:getProperty name="helper" property="inboundLifetimeKBps" />/<jsp:getProperty name="helper" property="outboundLifetimeKBps" />KBps<br />
 <b>Used:</b> <jsp:getProperty name="helper" property="inboundTransferred" />/<jsp:getProperty name="helper" property="outboundTransferred" /><br />
 <hr />
 
 <jsp:getProperty name="helper" property="destinations" />
 
 <u><b>Tunnels in/out</b></u><br />
 <b>Exploratory:</b> <jsp:getProperty name="helper" property="inboundTunnels" />/<jsp:getProperty name="helper" property="outboundTunnels" /><br />
 <b>Client:</b> <jsp:getProperty name="helper" property="inboundClientTunnels" />/<jsp:getProperty name="helper" property="outboundClientTunnels" /><br />
 <b>Participating:</b> <jsp:getProperty name="helper" property="participatingTunnels" /><br />
 <hr />
 
 <u><b>Congestion</b></u><br />
 <b>Job lag:</b> <jsp:getProperty name="helper" property="jobLag" /><br />
 <b>Message delay:</b> <jsp:getProperty name="helper" property="messageDelay" /><br />
 <b>Tunnel lag:</b> <jsp:getProperty name="helper" property="tunnelLag" /><br />
 <b>Handle backlog:</b> <jsp:getProperty name="helper" property="inboundBacklog" /><br />
 <b><jsp:getProperty name="helper" property="tunnelStatus" /></b><br />
 
</div>
