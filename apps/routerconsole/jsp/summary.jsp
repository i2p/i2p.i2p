<%@page import="net.i2p.router.web.SummaryHelper" %>
<jsp:useBean class="net.i2p.router.web.SummaryHelper" id="helper" scope="request" />
<jsp:setProperty name="helper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<jsp:useBean class="net.i2p.router.web.ReseedHandler" id="reseed" scope="request" />
<jsp:setProperty name="reseed" property="*" />
<jsp:useBean class="net.i2p.router.web.UpdateHandler" id="update" scope="request" />
<jsp:setProperty name="update" property="*" />
<jsp:setProperty name="update" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<div class="routersummary">
 <u><b>General</b></u><br />
 <b>Ident:</b> <jsp:getProperty name="helper" property="ident" /><br />
 <b>Version:</b> <jsp:getProperty name="helper" property="version" /><br />
 <b>Uptime:</b> <jsp:getProperty name="helper" property="uptime" /><br />
 <b>Now:</b> <jsp:getProperty name="helper" property="time" /><br />
 <b>Status:</b> <a href="config.jsp"><jsp:getProperty name="helper" property="reachability" /></a><%
    if (helper.updateAvailable()) {
        if ("true".equals(System.getProperty("net.i2p.router.web.UpdateHandler.updateInProgress", "false"))) {
            out.print("<br />" + update.getStatus());
        } else {
            long nonce = new java.util.Random().nextLong();
            String prev = System.getProperty("net.i2p.router.web.UpdateHandler.nonce");
            if (prev != null) System.setProperty("net.i2p.router.web.UpdateHandler.noncePrev", prev);
            System.setProperty("net.i2p.router.web.UpdateHandler.nonce", nonce+"");
            String uri = request.getRequestURI();
            if (uri.indexOf('?') > 0)
                uri = uri + "&updateNonce=" + nonce;
            else
                uri = uri + "?updateNonce=" + nonce;
            out.print("<br /><a href=\"" + uri + "\">Update available</a>");
        }
    }
 %>
 <br /><%=net.i2p.router.web.ConfigRestartBean.renderStatus(request.getRequestURI(), request.getParameter("action"), request.getParameter("consoleNonce"))%>
 <hr />
 
 <u><b><a href="peers.jsp">Peers</a></b></u><br />
 <b>Active:</b> <jsp:getProperty name="helper" property="activePeers" />/<jsp:getProperty name="helper" property="activeProfiles" /><br />
 <b>Fast:</b> <jsp:getProperty name="helper" property="fastPeers" /><br />
 <b>High capacity:</b> <jsp:getProperty name="helper" property="highCapacityPeers" /><br />
 <b>Well integrated:</b> <jsp:getProperty name="helper" property="wellIntegratedPeers" /><br />
 <b>Failing:</b> <jsp:getProperty name="helper" property="failingPeers" /><br />
 <!-- <b>Shitlisted:</b> <jsp:getProperty name="helper" property="shitlistedPeers" /><br /> -->
 <b>Known:</b> <jsp:getProperty name="helper" property="allPeers" /><br /><%
     if (helper.getActivePeers() <= 0) {
        %><b><a href="config.jsp">check your NAT/firewall</a></b><br /><%
     }
    if (helper.allowReseed()) {
        if ("true".equals(System.getProperty("net.i2p.router.web.ReseedHandler.reseedInProgress", "false"))) {
            out.print(" <i>reseeding</i>");
        } else {
            long nonce = new java.util.Random().nextLong();
            String prev = System.getProperty("net.i2p.router.web.ReseedHandler.nonce");
            if (prev != null) System.setProperty("net.i2p.router.web.ReseedHandler.noncePrev", prev);
            System.setProperty("net.i2p.router.web.ReseedHandler.nonce", nonce+"");
            String uri = request.getRequestURI();
            if (uri.indexOf('?') > 0)
                uri = uri + "&reseedNonce=" + nonce;
            else
                uri = uri + "?reseedNonce=" + nonce;
            out.print(" <a href=\"" + uri + "\">reseed</a>");
        }
    }
 %><hr />
 
 <u><b><a href="config.jsp" title="Configure the bandwidth limits">Bandwidth in/out</a></b></u><br />
 <b>1s:</b> <jsp:getProperty name="helper" property="inboundMinuteKBps" />/<jsp:getProperty name="helper" property="outboundMinuteKBps" />KBps<br />
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
 <hr />
 
</div>
