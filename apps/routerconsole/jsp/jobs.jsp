<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("job queue")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %><h1><%=intl._t("I2P Router Job Queue")%></h1>
<div class="main" id="jobs">
 <jsp:useBean class="net.i2p.router.web.helpers.JobQueueHelper" id="jobQueueHelper" scope="request" />
 <jsp:setProperty name="jobQueueHelper" property="contextId" value="<%=i2pcontextId%>" />
 <% jobQueueHelper.storeWriter(out); %>
<%-- This page is hidden behind advanced config, don't bother translating --%>
<h2>Congestion</h2><div class="joblog">
<p>Some basic indications of router overload:</p>
<ul><li>
<b>Job Lag:</b> How long jobs are waiting before execution. 
Unfortunately, there are several other job queues in the router that may be congested, and their status is not available in the router console. 
The job lag should generally be zero. 
If it is consistently higher than 500ms, your computer is very slow, your network is experiencing connectivity issues, or the router has serious problems. 
</li><li>
<b>Message Delay:</b> How long an outbound message waits in the queue. 
This should generally be a few hundred milliseconds or less. 
If it is consistently higher than 1000ms, your computer is very slow, or you should adjust your bandwidth limits, or your (Bittorrent?) clients may be sending too much data and should have their transmit bandwidth limit reduced. 
</li><li>
<b>Accepting/Rejecting:</b> Your router's status on accepting or rejecting requests from other routers to build a participating tunnel through your router. 
Your router may accept all requests, accept or reject a percentage of requests, or reject all requests for a number of reasons, to control the bandwidth and CPU demands and maintain capacity for local clients. 
<b>Note:</b> It will take several minutes after startup to begin accepting participating tunnels. This ensures your router is stable and successfully bootstrapped to the network.
</li></ul></div>
 <jsp:getProperty name="jobQueueHelper" property="jobQueueSummary" />
</div></body></html>
