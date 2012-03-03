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
<input type="hidden" name="nonce" value="<jsp:getProperty name="formhandler" property="newNonce" />" >
<h3><%=intl._("Reseeding Configuration")%></h3>
<p><%=intl._("Reseeding is the bootstrapping process used to find other routers when you first install I2P, or when your router has too few router references remaining.")%>
<%=intl._("If reseeding has failed, you should first check your network connection.")%>
<p><b><%=intl._("The default settings will work for most people.")%></b>
<%=intl._("Change these only if HTTP is blocked by a restrictive firewall, reseed has failed, and you have access to an HTTP proxy.")%>
<%=intl._("See {0} for instructions on reseeding manually.", "<a href=\"http://www.i2p2.de/faq.html#manual_reseed\">" + intl._("the FAQ") + "</a>")%>
</p>
<div class="wideload">
<table border="0" cellspacing="5">
<tr><td class="mediumtags" align="right"><b><%=intl._("Reseed URL Selection")%>:</b></td>
<td><input type="radio" class="optbox" name="mode" value="0" <%=reseedHelper.modeChecked(0) %> >
<b><%=intl._("Try SSL first then non-SSL")%></b><br>
<input type="radio" class="optbox" name="mode" value="1" <%=reseedHelper.modeChecked(1) %> >
<b><%=intl._("Use SSL only")%></b><br>
<input type="radio" class="optbox" name="mode" value="2" <%=reseedHelper.modeChecked(2) %> >
<b><%=intl._("Use non-SSL only")%></b></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._("Reseed URLs")%>:</b></td>
<td><textarea wrap="off" name="reseedURL" cols="60" rows="7" spellcheck="false"><jsp:getProperty name="reseedHelper" property="reseedURL" /></textarea></td></tr>

<tr><td class="mediumtags" align="right"><b><%=intl._("Enable HTTP Proxy?")%></b></td>
<td><input type="checkbox" class="optbox" name="enable" value="true" <jsp:getProperty name="reseedHelper" property="enable" /> ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._("HTTP Proxy Host")%>:</b></td>
<td><input name="host" type="text" value="<jsp:getProperty name="reseedHelper" property="host" />" ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._("HTTP Proxy Port")%>:</b></td>
<td><input name="port" type="text" size="5" maxlength="5" value="<jsp:getProperty name="reseedHelper" property="port" />" ></td></tr>

<tr><td class="mediumtags" align="right"><b><%=intl._("Use HTTP Proxy Authorization?")%></b></td>
<td><input type="checkbox" class="optbox" name="auth" value="true" <jsp:getProperty name="reseedHelper" property="auth" /> ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._("HTTP Proxy Username")%>:</b></td>
<td><input name="username" type="text" value="<jsp:getProperty name="reseedHelper" property="username" />" ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._("HTTP Proxy Password")%>:</b></td>
<td><input name="password" type="password" value="<jsp:getProperty name="reseedHelper" property="password" />" ></td></tr>

<!-- TODO Need SSLEepGet support
<tr><td class="mediumtags" align="right"><b><%=intl._("Enable HTTPS Proxy?")%></b></td>
<td><input type="checkbox" class="optbox" name="senable" value="true" <jsp:getProperty name="reseedHelper" property="senable" /> ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._("HTTPS Proxy Host")%>:</b></td>
<td><input name="shost" type="text" value="<jsp:getProperty name="reseedHelper" property="shost" />" ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._("HTTPS Proxy Port")%>:</b></td>
<td><input name="sport" type="text" size="5" maxlength="5" value="<jsp:getProperty name="reseedHelper" property="sport" />" ></td></tr>

<tr><td class="mediumtags" align="right"><b><%=intl._("Use HTTPS Proxy Authorization?")%></b></td>
<td><input type="checkbox" class="optbox" name="sauth" value="true" <jsp:getProperty name="reseedHelper" property="sauth" /> ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._("HTTPS Proxy Username")%>:</b></td>
<td><input name="susername" type="text" value="<jsp:getProperty name="reseedHelper" property="susername" />" ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._("HTTPS Proxy Password")%>:</b></td>
<td><input name="spassword" type="password" value="<jsp:getProperty name="reseedHelper" property="spassword" />" ></td></tr>
-->

</table></div>
<hr><div class="formaction">
<input type="submit" class="cancel" name="foo" value="<%=intl._("Cancel")%>" />
<input type="submit" name="action" class="download" value="<%=intl._("Save changes and reseed now")%>" />
<input type="submit" name="action" class="accept" value="<%=intl._("Save changes")%>" />
</div></form></div></div></body></html>
