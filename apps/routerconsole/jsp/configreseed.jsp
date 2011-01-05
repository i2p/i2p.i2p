<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config reseeding")%>
</head><body>

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigReseedHelper" id="reseedHelper" scope="request" />
<jsp:setProperty name="reseedHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<h1><%=intl._("I2P Reseeding Configuration")%></h1>
<div class="main" id="main">
<%@include file="confignav.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigReseedHandler" id="formhandler" scope="request" />
<% formhandler.storeMethod(request.getMethod()); %>
<jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<jsp:setProperty name="formhandler" property="action" value="<%=request.getParameter("action")%>" />
<jsp:setProperty name="formhandler" property="nonce" value="<%=request.getParameter("nonce")%>" />
<jsp:setProperty name="formhandler" property="settings" value="<%=request.getParameterMap()%>" />
<jsp:getProperty name="formhandler" property="allMessages" />
<div class="configure"><form action="" method="POST">
<%  String prev = System.getProperty("net.i2p.router.web.ConfigReseedHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigReseedHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigReseedHandler.nonce", new java.util.Random().nextLong()+""); %>
<input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigReseedHandler.nonce")%>" >
<h3><%=intl._("Reseeding Configuration")%></h3>
<p><%=intl._("Reseeding is the bootstrapping process used to find other routers when you first install I2P, or when your router has too few router references remaining.")%>
<%=intl._("If reseeding has failed, you should first check your network connection.")%>
<p><b><%=intl._("The default settings will work for most people.")%></b>
<%=intl._("Change these only if HTTP is blocked by a restrictive firewall, reseed has failed, and you have access to an HTTP proxy.")%>
<%=intl._("See {0} for instructions on reseeding manually.", "<a href=\"http://www.i2p2.de/faq.html#manual_reseed\">" + intl._("the FAQ") + "</a>")%>
</p>
<div class="wideload">
<table border="0" cellspacing="5">
<tr><td class="mediumtags" align="right"><b><%=intl._("Reseed URL Selection")%></b></td>
<td><input type="radio" class="optbox" name="mode" value="0" <%=reseedHelper.modeChecked(0) %> >
<%=intl._("Try SSL first then non-SSL")%>
<input type="radio" class="optbox" name="mode" value="1" <%=reseedHelper.modeChecked(1) %> >
<%=intl._("Use SSL only")%>
<input type="radio" class="optbox" name="mode" value="2" <%=reseedHelper.modeChecked(2) %> >
<%=intl._("Use non-SSL only")%></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._("Reseed URLs")%></b></td>
<td><textarea name="reseedURL" wrap="off" spellcheck="false"><jsp:getProperty name="reseedHelper" property="reseedURL" /></textarea></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._("Enable HTTP proxy (not used for SSL)")%></b></td>
<td><input type="checkbox" class="optbox" name="enable" value="true" <jsp:getProperty name="reseedHelper" property="enable" /> ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._("HTTP Proxy Host")%>:</b></td>
<td><input name="host" type="text" value="<jsp:getProperty name="reseedHelper" property="host" />" ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._("HTTP Proxy Port")%>:</b></td>
<td><input name="port" type="text" value="<jsp:getProperty name="reseedHelper" property="port" />" ></td></tr>
</table></div>
<hr><div class="formaction">
<input type="submit" name="foo" value="<%=intl._("Cancel")%>" />
<input type="submit" name="action" value="<%=intl._("Save changes and reseed now")%>" />
<input type="submit" name="action" value="<%=intl._("Save changes")%>" />
</div></form></div></div></body></html>
