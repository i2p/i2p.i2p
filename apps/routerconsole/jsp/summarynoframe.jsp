<%@page import="net.i2p.router.web.SummaryHelper" %>
<%
/*
 * Note:
 * This is included almost 30 times, so keep whitespace etc. to a minimum.
 */
%>
<jsp:useBean class="net.i2p.router.web.SummaryHelper" id="helper" scope="request" />
<jsp:setProperty name="helper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<jsp:useBean class="net.i2p.router.web.ReseedHandler" id="reseed" scope="request" />
<jsp:setProperty name="reseed" property="*" />
<jsp:useBean class="net.i2p.router.web.UpdateHandler" id="update" scope="request" />
<jsp:setProperty name="update" property="*" />
<jsp:setProperty name="update" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<a href="index.jsp" target="_top"><img src="/themes/console/images/i2plogo.png" alt="I2P Router Console" title="I2P Router Console"/></a><hr />
<% java.io.File lpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "docs/toolbar.html");
    // you better have target="_top" for the links in there...
    if (lpath.exists()) { %>
<jsp:useBean class="net.i2p.router.web.ContentHelper" id="linkhelper" scope="request" />
<jsp:setProperty name="linkhelper" property="page" value="<%=lpath.getAbsolutePath()%>" />
<jsp:setProperty name="linkhelper" property="maxLines" value="100" />
<jsp:getProperty name="linkhelper" property="content" />
<% } else { %>
<h3><a href="/configclients.jsp" target="_top" title="Configure startup of clients and webapps (services); manually start dormant services.">I2P Services</a></h3><hr>
<table>
<tr>
<td><a href="susidns/index.jsp" target="_blank" title="Manage your I2P hosts file here (I2P domain name resolution).">Addressbook</a> 
<a href="i2psnark/" target="_blank" title="Built-in anonymous BitTorrent Client">Torrents</a>
<a href="susimail/susimail" target="blank" title="Anonymous webmail client.">Webmail</a>  
<a href="http://127.0.0.1:7658/" target="_blank" title="Anonymous resident webserver.">Webserver</a></td>
</tr></table><hr> 
<h3><a href="config.jsp" target="_top" title="Configure I2P Router.">I2P Internals</a></h3><hr>
<table><tr>
<td>
<a href="tunnels.jsp" target="_top" title="View existing tunnels and tunnel build status.">Tunnels</a> 
<a href="peers.jsp" target="_top" title="Show all current peer connections.">Peers</a> 
<a href="profiles.jsp" target="_top" title="Show recent peer performance profiles.">Profiles</a> 
<a href="netdb.jsp" target="_top" title="Show list of all known I2P routers.">NetDB</a> 
<a href="logs.jsp" target="_top" title="Health Report.">Logs</a> 
<a href="jobs.jsp" target="_top" title="Show the router's workload, and how it's performing.">Jobs</a>
<a href="graphs.jsp" target="_top" title="Graph router performance.">Graphs</a> 
<a href="oldstats.jsp" target="_top" title="Textual router performance statistics.">Stats</a>
</td></tr></table>
<% } %>

<hr>
<h3><a href="help.jsp" target="_top" title="I2P Router Help.">General</a></h3><hr>
<h4>
<a title="Your unique I2P router identity is <jsp:getProperty name="helper" property="ident" />, never reveal it to anyone" href="netdb.jsp?r=." target="_top">Local Identity</a></h4>
<hr>
<table><tr>
<td align="left">
<b>Version:</b></td>
<td align="right"><jsp:getProperty name="helper" property="version" /></td></tr>
<tr title="How long we've been running for this session.">
<td align="left">
<b>Uptime:</b></td> 
<td align="right"><jsp:getProperty name="helper" property="uptime" /></td></tr></table>
<hr><h4><a href="config.jsp#help" target="_top" title="Help with configuring your firewall and router for optimal I2P performance."><jsp:getProperty name="helper" property="reachability" /></a></h4>
<hr>
<%
    if (helper.updateAvailable() || helper.unsignedUpdateAvailable()) {
        // display all the time so we display the final failure message
        out.print("<br />" + update.getStatus());
        if ("true".equals(System.getProperty("net.i2p.router.web.UpdateHandler.updateInProgress"))) {
        } else if((!update.isDone()) &&
                  request.getParameter("action") == null &&
                  request.getParameter("updateNonce") == null &&
                  net.i2p.router.web.ConfigRestartBean.getRestartTimeRemaining() > 12*60*1000) {
            long nonce = new java.util.Random().nextLong();
            String prev = System.getProperty("net.i2p.router.web.UpdateHandler.nonce");
            if (prev != null) System.setProperty("net.i2p.router.web.UpdateHandler.noncePrev", prev);
            System.setProperty("net.i2p.router.web.UpdateHandler.nonce", nonce+"");
            String uri = request.getRequestURI();
            out.print("<p><form action=\"" + uri + "\" method=\"GET\">\n");
            out.print("<input type=\"hidden\" name=\"updateNonce\" value=\"" + nonce + "\" />\n");
            if (helper.updateAvailable())
                out.print("<button type=\"submit\" name=\"updateAction\" value=\"signed\" >Download " + helper.getUpdateVersion() + " Update</button>\n");
            if (helper.unsignedUpdateAvailable())
                out.print("<button type=\"submit\" name=\"updateAction\" value=\"Unsigned\" >Download Unsigned<br />" + helper.getUnsignedUpdateVersion() + " Update</button>\n");
            out.print("</form></p>\n");
        }
    }
%>
<p>
<%=net.i2p.router.web.ConfigRestartBean.renderStatus(request.getRequestURI(), request.getParameter("action"), request.getParameter("consoleNonce"))%>
</p>
<hr />
<h3><a href="peers.jsp" target="_top" title="Show all current peer connections.">Peers</a></h3><hr><table>
<tr><td align="left"><b>Active:</b></td><td align="right"><jsp:getProperty name="helper" property="activePeers" />/<jsp:getProperty name="helper" property="activeProfiles" /></td></tr>
<tr><td align="left"><b>Fast:</b></td><td align="right"><jsp:getProperty name="helper" property="fastPeers" /></td></tr>
<tr><td align="left"><b>High capacity:</b></td><td align="right"><jsp:getProperty name="helper" property="highCapacityPeers" /></td></tr>
<tr><td align="left"><b>Integrated:</b></td><td align="right"><jsp:getProperty name="helper" property="wellIntegratedPeers" /></td></tr>
<tr><td align="left"><b>Known:</b></td><td align="right"><jsp:getProperty name="helper" property="allPeers" /></td></tr></table><hr><%
    if (helper.getActivePeers() <= 0) {
        %><h4><a href="config.jsp" target="_top" title="Help with firewall configuration.">Check NAT/firewall</a></h4><%
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
            out.print("<p><form action=\"" + uri + "\" method=\"GET\">\n");
            out.print("<input type=\"hidden\" name=\"reseedNonce\" value=\"" + nonce + "\" />\n");
            out.print("<button type=\"submit\" >Reseed</button></form></p>\n");
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
<h3><a href="config.jsp" title="Configure router bandwidth allocation." target="_top">Bandwidth in/out</a></h3><hr>
<table>
<tr><td align="left"><b>1s:</b></td><td align="right"><jsp:getProperty name="helper" property="inboundSecondKBps" />/<jsp:getProperty name="helper" property="outboundSecondKBps" />K/s</td></tr>
<tr><td align="left"><b>5m:</b></td><td align="right"><jsp:getProperty name="helper" property="inboundFiveMinuteKBps" />/<jsp:getProperty name="helper" property="outboundFiveMinuteKBps" />K/s</td></tr>
<tr><td align="left"><b>Total:</b></td><td align="right"><jsp:getProperty name="helper" property="inboundLifetimeKBps" />/<jsp:getProperty name="helper" property="outboundLifetimeKBps" />K/s</td></tr>
<tr><td align="left"><b>Used:</b></td><td align="right"><jsp:getProperty name="helper" property="inboundTransferred" />/<jsp:getProperty name="helper" property="outboundTransferred" /></td></tr></table>
<hr>
<h3><a href="tunnels.jsp" target="_blank" title="View existing tunnels and tunnel build status.">Tunnels in/out</a></h3><hr>
<table><tr>
<td align="left"><b>Exploratory:</b></td><td align="right"><jsp:getProperty name="helper" property="inboundTunnels" />/<jsp:getProperty name="helper" property="outboundTunnels" /></td></tr>
<tr><td align="left"><b>Client:</b></td><td align="right"><jsp:getProperty name="helper" property="inboundClientTunnels" />/<jsp:getProperty name="helper" property="outboundClientTunnels" /></td></tr>
<tr><td align="left"><b>Participating:</b></td><td align="right"><jsp:getProperty name="helper" property="participatingTunnels" /></td></tr></table>
<hr>
<h3><a href="/oldstats.jsp#JobQueue" target="_top" title="What's in the router's job queue?">Congestion</a></h3><hr>
<table><tr>
<td align="left"><b>Job lag:</b></td><td align="right"><jsp:getProperty name="helper" property="jobLag" /></td></tr>
<tr><td align="left"><b>Message delay:</b></td><td align="right"><jsp:getProperty name="helper" property="messageDelay" /></td></tr>
<tr><td align="left"><b>Tunnel lag:</b></td><td align="right"><jsp:getProperty name="helper" property="tunnelLag" /></td></tr>
<tr><td align="left"><b>Backlog:</b></td><td align="right"><jsp:getProperty name="helper" property="inboundBacklog" /></td></tr><table  align="center" title="Router tunnel build status.">
<hr><h4><jsp:getProperty name="helper" property="tunnelStatus" /></h4>
<hr>
<jsp:getProperty name="helper" property="destinations" />
