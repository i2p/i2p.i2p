<%@page import="net.i2p.router.web.SummaryHelper" %>
<jsp:useBean class="net.i2p.router.web.SummaryHelper" id="helper" scope="request" />
<jsp:setProperty name="helper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<div class="routersummary">
 <u><b>General</b></u><br />
 <b>Ident:</b> <jsp:getProperty name="helper" property="ident" /><br />
 <b>Version:</b> <jsp:getProperty name="helper" property="version" /><br />
 <b>Uptime:</b> <jsp:getProperty name="helper" property="uptime" /><br />
 <hr />
 
 <u><b>Peers</b></u><br />
 <b>Active:</b> <jsp:getProperty name="helper" property="activePeers" /><br />
 <b>Fast:</b> <jsp:getProperty name="helper" property="fastPeers" /><br />
 <b>High capacity:</b> <jsp:getProperty name="helper" property="highCapacityPeers" /><br />
 <b>Well integrated:</b> <jsp:getProperty name="helper" property="wellIntegratedPeers" /><br />
 <b>Failing:</b> <jsp:getProperty name="helper" property="failingPeers" /><br />
 <b>Shitlisted:</b> <jsp:getProperty name="helper" property="shitlistedPeers" /><br />
 <hr />
 
 <u><b>Bandwidth in/out</b></u><br />
 <b>1m:</b> <jsp:getProperty name="helper" property="inboundMinuteKBps" />/<jsp:getProperty name="helper" property="outboundMinuteKBps" />KBps<br />
 <b>5m:</b> <jsp:getProperty name="helper" property="inboundFiveMinuteKBps" />/<jsp:getProperty name="helper" property="outboundFiveMinuteKBps" />KBps<br />
 <b>Total:</b> <jsp:getProperty name="helper" property="inboundLifetimeKBps" />/<jsp:getProperty name="helper" property="outboundLifetimeKBps" />KBps<br />
 <b>Used:</b> <jsp:getProperty name="helper" property="inboundTransferred" />/<jsp:getProperty name="helper" property="outboundTransferred" /><br />
 <hr />
 
 <jsp:getProperty name="helper" property="destinations" />
 
 <u><b>Tunnels</b></u><br />
 <b>Inbound:</b> <jsp:getProperty name="helper" property="inboundTunnels" /><br />
 <b>Outbound:</b> <jsp:getProperty name="helper" property="outboundTunnels" /><br />
 <b>Participating:</b> <jsp:getProperty name="helper" property="participatingTunnels" /><br />
 <hr />
 
 <u><b>Congestion</b></u><br />
 <b>Job lag:</b> <jsp:getProperty name="helper" property="jobLag" /><br />
 <b>Message delay:</b> <jsp:getProperty name="helper" property="messageDelay" /><br />
 <b>Tunnel lag:</b> <jsp:getProperty name="helper" property="tunnelLag" /><br />
 <hr />
 
</div>
