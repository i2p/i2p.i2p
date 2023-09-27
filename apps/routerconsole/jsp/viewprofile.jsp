<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Peer Profile")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._t("Peer Profile")%></h1>
<div class="main" id="view_profile"><div class="wideload">
<%
    String peerB64 = request.getParameter("peer");
    if (peerB64 == null || peerB64.length() <= 0 ||
        peerB64.replaceAll("[a-zA-Z0-9~=-]", "").length() != 0) {
        out.print("No peer specified");
    } else {

%>
<jsp:useBean id="stathelper" class="net.i2p.router.web.helpers.StatHelper" />
<jsp:setProperty name="stathelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:setProperty name="stathelper" property="peer" value="<%=peerB64%>" />
<% stathelper.storeWriter(out); %>
<h2><%=intl._t("Profile for peer {0}", peerB64)%></h2>
<table id="viewprofile"><tbody><tr><td><pre>
<jsp:getProperty name="stathelper" property="profile" />
</pre></td></tr></tbody></table>
<%
    }
%>
</div></div></body></html>
