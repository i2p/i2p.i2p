<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("peer connections")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Network Peers")%></h1>
<div class="main" id="peers">
 <jsp:useBean class="net.i2p.router.web.helpers.PeerHelper" id="peerHelper" scope="request" />
 <jsp:setProperty name="peerHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <% peerHelper.storeWriter(out); %>
 <jsp:setProperty name="peerHelper" property="urlBase" value="peers.jsp" />
 <jsp:setProperty name="peerHelper" property="sort" value="<%=request.getParameter(\"sort\") != null ? request.getParameter(\"sort\") : \"\"%>" />
 <jsp:getProperty name="peerHelper" property="peerSummary" />
</div></div></body></html>
