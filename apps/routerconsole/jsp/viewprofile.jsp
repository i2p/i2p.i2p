<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Peer Profile")%>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._("Peer Profile")%></h1>
<div class="main" id="main"><div class="wideload">
<%
    String peerB64 = request.getParameter("peer");
    if (peerB64 == null || peerB64.length() <= 0) {
        out.print("No peer specified");
    } else {
        peerB64 = net.i2p.data.DataHelper.stripHTML(peerB64);  // XSS
%>
<jsp:useBean id="stathelper" class="net.i2p.router.web.StatHelper" />
<jsp:setProperty name="stathelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<jsp:setProperty name="stathelper" property="peer" value="<%=peerB64%>" />
<% stathelper.storeWriter(out); %>
<h2><%=intl._("Profile for peer {0}", peerB64)%></h2>
<pre>
<jsp:getProperty name="stathelper" property="profile" />
</pre>
<%
    }
%>
</div></div></body></html>
