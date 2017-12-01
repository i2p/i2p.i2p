<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("peer profiles")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Network Peer Profiles")%></h1>
<div class="main" id="profiles"><div class="wideload">
 <jsp:useBean class="net.i2p.router.web.helpers.ProfilesHelper" id="profilesHelper" scope="request" />
 <jsp:setProperty name="profilesHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<%
    profilesHelper.storeWriter(out);
    if (allowIFrame)
        profilesHelper.allowGraphical();
%>
 <jsp:setProperty name="profilesHelper" property="full" value="<%=request.getParameter(\"f\")%>" />
 <jsp:getProperty name="profilesHelper" property="summary" />
</div></div></body></html>
