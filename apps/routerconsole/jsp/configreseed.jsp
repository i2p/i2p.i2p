<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config reseeding")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigReseedHelper" id="reseedHelper" scope="request" />
<jsp:setProperty name="reseedHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<h1><%=intl._t("I2P Reseeding Configuration")%></h1>
<div class="main" id="main">
<%@include file="confignav.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigReseedHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>

<p><%=intl._t("Reseeding is the bootstrapping process used to find other routers when you first install I2P, or when your router has too few router references remaining.")%>
<%=intl._t("If reseeding has failed, you should first check your network connection.")%>
<%=intl._t("See {0} for instructions on reseeding manually.", "<a href=\"https://geti2p.net/faq#manual_reseed\">" + intl._t("the FAQ") + "</a>")%>
</p>

<div class="configure"><form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<h3><%=intl._t("Manual Reseed from URL")%></h3>
<p><%=intl._t("Enter zip or su3 URL")%> :
<input name="url" type="text" size="60" value="" />
<br><%=intl._t("The su3 format is preferred, as it will be verified as signed by a trusted source.")%>
<%=intl._t("The zip format is unsigned; use a zip file only from a source that you trust.")%>
</p>
<div class="formaction">
<input type="submit" name="action" class="download" value="<%=intl._t("Reseed from URL")%>" />
</div></form></div>

<div class="configure">
<form action="" method="POST" enctype="multipart/form-data" accept-charset="UTF-8">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<h3><%=intl._t("Manual Reseed from File")%></h3>
<p><%=intl._t("Select zip or su3 file")%> :
<input name="file" type="file" value="" />
<br><%=intl._t("The su3 format is preferred, as it will be verified as signed by a trusted source.")%>
<%=intl._t("The zip format is unsigned; use a zip file only from a source that you trust.")%>
</p>
<div class="formaction">
<input type="submit" name="action" class="download" value="<%=intl._t("Reseed from file")%>" />
</div></form></div>

<div class="configure">
<form action="/createreseed" method="GET">
<h3><%=intl._t("Create Reseed File")%></h3>
<p><%=intl._t("Create a new reseed zip file you may share for others to reseed manually.")%>
<%=intl._t("This file will never contain your own router's identity or IP.")%>
</p>
<div class="formaction">
<input type="submit" name="action" class="go" value="<%=intl._t("Create reseed file")%>" />
</div></form></div>

<div class="configure">
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<h3><%=intl._t("Reseeding Configuration")%></h3>
<p><b><%=intl._t("The default settings will work for most people.")%></b>
<%=intl._t("Change these only if HTTPS is blocked by a restrictive firewall and reseed has failed.")%>
</p>
<div class="wideload">
<table border="0" cellspacing="5">
<tr><td class="mediumtags" align="right"><b><%=intl._t("Reseed URL Selection")%>:</b></td>
<td><input type="radio" class="optbox" name="mode" value="0" <%=reseedHelper.modeChecked(0) %> >
<b><%=intl._t("Try SSL first then non-SSL")%></b><br>
<input type="radio" class="optbox" name="mode" value="1" <%=reseedHelper.modeChecked(1) %> >
<b><%=intl._t("Use SSL only")%></b><br>
<input type="radio" class="optbox" name="mode" value="2" <%=reseedHelper.modeChecked(2) %> >
<b><%=intl._t("Use non-SSL only")%></b></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._t("Reseed URLs")%>:</b></td>
<td><textarea wrap="off" name="reseedURL" cols="60" rows="7" spellcheck="false"><jsp:getProperty name="reseedHelper" property="reseedURL" /></textarea>
<div class="formaction"><input type="submit" name="action" value="<%=intl._t("Reset URL list")%>" /></div>
</td></tr>

<tr><td class="mediumtags" align="right"><b><%=intl._t("Enable HTTP Proxy?")%></b></td>
<td><input type="checkbox" class="optbox" name="enable" value="true" <jsp:getProperty name="reseedHelper" property="enable" /> ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._t("HTTP Proxy Host")%>:</b></td>
<td><input name="host" type="text" value="<jsp:getProperty name="reseedHelper" property="host" />" ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._t("HTTP Proxy Port")%>:</b></td>
<td><input name="port" type="text" size="5" maxlength="5" value="<jsp:getProperty name="reseedHelper" property="port" />" ></td></tr>

<tr><td class="mediumtags" align="right"><b><%=intl._t("Use HTTP Proxy Authorization?")%></b></td>
<td><input type="checkbox" class="optbox" name="auth" value="true" <jsp:getProperty name="reseedHelper" property="auth" /> ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._t("HTTP Proxy Username")%>:</b></td>
<td><input name="username" type="text" value="<jsp:getProperty name="reseedHelper" property="username" />" ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._t("HTTP Proxy Password")%>:</b></td>
<td><input name="nofilter_password" type="password" value="<jsp:getProperty name="reseedHelper" property="nofilter_password" />" ></td></tr>

<!-- TODO Need SSLEepGet support
<tr><td class="mediumtags" align="right"><b><%=intl._t("Enable HTTPS Proxy?")%></b></td>
<td><input type="checkbox" class="optbox" name="senable" value="true" <jsp:getProperty name="reseedHelper" property="senable" /> ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._t("HTTPS Proxy Host")%>:</b></td>
<td><input name="shost" type="text" value="<jsp:getProperty name="reseedHelper" property="shost" />" ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._t("HTTPS Proxy Port")%>:</b></td>
<td><input name="sport" type="text" size="5" maxlength="5" value="<jsp:getProperty name="reseedHelper" property="sport" />" ></td></tr>

<tr><td class="mediumtags" align="right"><b><%=intl._t("Use HTTPS Proxy Authorization?")%></b></td>
<td><input type="checkbox" class="optbox" name="sauth" value="true" <jsp:getProperty name="reseedHelper" property="sauth" /> ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._t("HTTPS Proxy Username")%>:</b></td>
<td><input name="susername" type="text" value="<jsp:getProperty name="reseedHelper" property="susername" />" ></td></tr>
<tr><td class="mediumtags" align="right"><b><%=intl._t("HTTPS Proxy Password")%>:</b></td>
<td><input name="nofilter_spassword" type="password" value="<jsp:getProperty name="reseedHelper" property="nofilter_spassword" />" ></td></tr>
-->

</table></div>
<div class="formaction">
<input type="submit" class="cancel" name="foo" value="<%=intl._t("Cancel")%>" />
<input type="submit" name="action" class="download" value="<%=intl._t("Save changes and reseed now")%>" />
<input type="submit" name="action" class="accept" value="<%=intl._t("Save changes")%>" />
</div></form></div></div></body></html>
