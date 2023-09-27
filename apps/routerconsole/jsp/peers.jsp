<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("peer connections")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Network Peers")%></h1>
<div class="main" id="peers">
 <jsp:useBean class="net.i2p.router.web.helpers.PeerHelper" id="peerHelper" scope="request" />
 <jsp:setProperty name="peerHelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    peerHelper.storeWriter(out);
    if (allowIFrame)
        peerHelper.allowGraphical();
%>
 <jsp:setProperty name="peerHelper" property="urlBase" value="peers.jsp" />
 <jsp:setProperty name="peerHelper" property="sort" value="<%=request.getParameter(\"sort\") != null ? request.getParameter(\"sort\") : \"\"%>" />
 <jsp:setProperty name="peerHelper" property="transport" value="<%=request.getParameter(\"tx\")%>" />
 <jsp:getProperty name="peerHelper" property="peerSummary" />
</div></body></html>
