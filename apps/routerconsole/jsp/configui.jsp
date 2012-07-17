<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config UI")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigUIHelper" id="uihelper" scope="request" />
<jsp:setProperty name="uihelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />

<h1><%=uihelper._("I2P UI Configuration")%></h1>
<div class="main" id="main">

 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigUIHandler" id="formhandler" scope="request" />
 <% formhandler.storeMethod(request.getMethod()); %>
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
<div class="configure"><div class="topshimten"><h3><%=uihelper._("Router Console Theme")%></h3></div>
 <form action="" method="POST">
<%
    /** lang setting is done in css.jsi, not in ConfigUIHandler */
    String consoleNonce = System.getProperty("router.consoleNonce");
    if (consoleNonce == null) {
        consoleNonce = Long.toString(new java.util.Random().nextLong());
        System.setProperty("router.consoleNonce", consoleNonce);
    }
%>
 <input type="hidden" name="consoleNonce" value="<%=consoleNonce%>" >
 <input type="hidden" name="nonce" value="<jsp:getProperty name="formhandler" property="newNonce" />" >
 <input type="hidden" name="action" value="blah" >
<%
 String userAgent = request.getHeader("User-Agent");
 if (userAgent == null || !userAgent.contains("MSIE")) {
%>
 <jsp:getProperty name="uihelper" property="settings" />
<% } else { %>
<%=uihelper._("Theme selection disabled for Internet Explorer, sorry.")%>
<hr>
<%=uihelper._("If you're not using IE, it's likely that your browser is pretending to be IE; please configure your browser (or proxy) to use a different User Agent string if you'd like to access the console themes.")%>
<% } %>
<h3><%=uihelper._("Router Console Language")%></h3>
<jsp:getProperty name="uihelper" property="langSettings" />
<p><%=uihelper._("Please contribute to the router console translation project! Contact the developers in #i2p-dev on IRC to help.")%>
</p><hr><div class="formaction">
<input type="reset" class="cancel" value="<%=intl._("Cancel")%>" >
<input type="submit" name="shouldsave" class="accept" value="<%=intl._("Apply")%>" >
</div></form></div>
</div></body></html>
