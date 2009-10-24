<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsp" %>
<%=intl.title("peer profiles")%>
</head><body><%@include file="summary.jsp" %>
<h1><%=intl._("I2P Network Peer Profiles")%></h1>
<div class="main" id="main"><div class="wideload">
 <jsp:useBean class="net.i2p.router.web.ProfilesHelper" id="profilesHelper" scope="request" />
 <jsp:setProperty name="profilesHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:setProperty name="profilesHelper" property="writer" value="<%=out%>" />
 <jsp:getProperty name="profilesHelper" property="profileSummary" />
 <a name="shitlist"> </a>
 <jsp:getProperty name="profilesHelper" property="shitlistSummary" />
<hr></div></div></body></html>
