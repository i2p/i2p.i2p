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

<jsp:useBean class="net.i2p.router.web.helpers.ConfigReseedHelper" id="reseedHelper" scope="request" />
<jsp:setProperty name="reseedHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<h1><%=intl._t("I2P Reseeding Configuration")%></h1>
<div class="main" id="config_reseed">
<%@include file="confignav.jsi" %>

<jsp:useBean class="net.i2p.router.web.helpers.ConfigReseedHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>

<p class="infohelp">
<%=intl._t("Reseeding is the bootstrapping process used to find other routers when you first install I2P, or when your router has too few router references remaining.")%>
<ol><li>
<%=intl._t("If reseeding has failed, you should first check your network connection.")%>
</li><li>
<%=intl._t("If a firewall is blocking your connections to reseed hosts, you may have access to a proxy.")%>
<ul><li>
<%=intl._t("The proxy may be a remote public proxy, or may be running on your computer (localhost).")%>
</li><li>
<%=intl._t("To use a proxy, configure the type, hostname, and port below.")%>
</li><li>
<%=intl._t("If you are running Tor Browser, reseed through it by configuring SOCKS 5, localhost, port 9150.")%>
</li><li>
<%=intl._t("If you are running command-line Tor, reseed through it by configuring SOCKS 5, localhost, port 9050.")%>
</li><li>
<%=intl._t("If you have some peers but need more, you may try the I2P Outproxy option. Leave the host and port blank.")%>
<%=intl._t("This will not work for an initial reseed when you have no peers at all.")%>
</li><li>
<%=intl._t("Then, click \"{0}\".", intl._t("Save changes and reseed now"))%>
</li><li>
<%=intl._t("The default settings will work for most people.")%>
<%=intl._t("Change these only if HTTPS is blocked by a restrictive firewall and reseed has failed.")%>
</li></ul>
</li><li>
<%=intl._t("If you know and trust somebody that runs I2P, ask them to send you a reseed file generated using this page on their router console.")%>
<%=intl._t("Then, use this page to reseed with the file you received.")%>
<%=intl._t("First, select the file below.")%>
<%=intl._t("Then, click \"{0}\".", intl._t("Reseed from file"))%>
</li><li>
<%=intl._t("If you know and trust somebody that publishes reseed files, ask them for the URL.")%>
<%=intl._t("Then, use this page to reseed with the URL you received.")%>
<%=intl._t("First, enter the URL below.")%>
<%=intl._t("Then, click \"{0}\".", intl._t("Reseed from URL"))%>
</li><li>
<%=intl._t("See {0} for instructions on reseeding manually.", "<a href=\"https://geti2p.net/faq#manual_reseed\">" + intl._t("the FAQ") + "</a>")%>
</li></ol>
</p>
<h3 class="tabletitle"><%=intl._t("Manual Reseed")%></h3>
<table id="manualreseed" class="configtable">
 <tr>
  <td class="infohelp" colspan="2">
<%=intl._t("The su3 format is preferred, as it will be verified as signed by a trusted source.")%>&nbsp;
<%=intl._t("The zip format is unsigned; use a zip file only from a source that you trust.")%>
  </td>
 <tr>
  <th colspan="2"><%=intl._t("Reseed from URL")%></th>
 </tr>
 <tr>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
  <td>
<b><%=intl._t("Enter zip or su3 URL")%>:</b>
<input name="url" type="text" size="60" value="" />
  </td>
  <td class="optionsave">
<input type="submit" name="action" class="download" value="<%=intl._t("Reseed from URL")%>" />
  </td>
</form>
 </tr>
 <tr>
  <th colspan="2"><%=intl._t("Reseed from File")%></th>
 </tr>
 <tr>
<form action="" method="POST" enctype="multipart/form-data" accept-charset="UTF-8">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
  <td>
<b><%=intl._t("Select zip or su3 file")%>:</b>
<input name="file" type="file" accept=".zip,.su3" value="" />
  </td>
  <td class="optionsave">
<input type="submit" name="action" class="download" value="<%=intl._t("Reseed from file")%>" />
  </td>
</form>
 </tr>
 <tr>
  <th colspan="2">
<%=intl._t("Create Reseed File")%>
  </th>
 </tr>
 <tr>
  <td class="infohelp" colspan="2">
<%=intl._t("Create a new reseed zip file you may share for others to reseed manually.")%>&nbsp;
<%=intl._t("This file will never contain your own router's identity or IP.")%>
  </td>
 </tr>
 <tr>
  <td class="optionsave" colspan="2">
<form action="/createreseed" method="GET">
<input type="submit" name="action" class="go" value="<%=intl._t("Create reseed file")%>" />
</form>
  </td>
 </tr>
</table>

<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<h3 class="tabletitle"><%=intl._t("Reseeding Configuration")%></h3>
<table id="reseedconfig" class="configtable" border="0" cellspacing="5">
 <tr>
  <td class="infohelp" colspan="2">
<b><%=intl._t("The default settings will work for most people.")%></b>&nbsp;
<%=intl._t("Change these only if HTTPS is blocked by a restrictive firewall and reseed has failed.")%>
  </td>
 </tr>

<% if (reseedHelper.shouldShowSelect()) { %>
<tr><td align="right"><b><%=intl._t("Reseed URL Selection")%>:</b></td>
<td><label><input type="radio" class="optbox" name="mode" value="0" <%=reseedHelper.modeChecked(0) %> >
<%=intl._t("Try SSL first then non-SSL")%></label><br>
<label><input type="radio" class="optbox" name="mode" value="1" <%=reseedHelper.modeChecked(1) %> >
<%=intl._t("Use SSL only")%></label><br>
<label><input type="radio" class="optbox" name="mode" value="2" <%=reseedHelper.modeChecked(2) %> >
<%=intl._t("Use non-SSL only")%></label></td></tr>
<% } // shouldShowSelect %>

<tr><td align="right"><b><%=intl._t("Reseed URLs")%>:</b></td>
<td><textarea wrap="off" name="reseedURL" cols="60" rows="7" spellcheck="false"><jsp:getProperty name="reseedHelper" property="reseedURL" /></textarea>
<div class="formaction" id="resetreseed"><input type="submit" name="action" class="reload" value="<%=intl._t("Reset URL list")%>" /></div>
</td></tr>

<% if (reseedHelper.shouldShowHTTPSProxy()) { %>
<tr><td align="right"><b><%=intl._t("Proxy type for HTTPS reseed URLs")%>:</b></td>
<td><label><input type="radio" class="optbox" name="pmode" value="" <%=reseedHelper.pmodeChecked(0) %> >
<%=intl._t("None")%></label><br>
<label><input type="radio" class="optbox" name="pmode" value="HTTP" <%=reseedHelper.pmodeChecked(1) %> >
<%=intl._t("HTTPS")%></label><br>
<label><input type="radio" class="optbox" name="pmode" value="SOCKS4" <%=reseedHelper.pmodeChecked(2) %> >
<%=intl._t("SOCKS 4/4a")%></label><br>
<label><input type="radio" class="optbox" name="pmode" value="SOCKS5" <%=reseedHelper.pmodeChecked(3) %> >
<%=intl._t("SOCKS 5")%></label><br>
<label><input type="radio" class="optbox" name="pmode" value="INTERNAL" <%=reseedHelper.pmodeChecked(4) %> >
<%=intl._t("I2P Outproxy")%></label>
(<%=intl._t("Not for initial reseed. Leave host and port blank.")%>)
</td></tr>
<tr><td align="right"><b><%=intl._t("HTTPS Proxy Host")%>:</b></td>
<td><input name="shost" type="text" value="<jsp:getProperty name="reseedHelper" property="shost" />" ></td></tr>
<tr><td align="right"><b><%=intl._t("HTTPS Proxy Port")%>:</b></td>
<td><input name="sport" type="text" size="5" maxlength="5" value="<jsp:getProperty name="reseedHelper" property="sport" />" ></td></tr>

<!-- not fully implemented, not necessary?
<tr><td align="right"><b><%=intl._t("Use HTTPS Proxy Authorization?")%></b></td>
<td><input type="checkbox" class="optbox" name="sauth" value="true" <jsp:getProperty name="reseedHelper" property="sauth" /> ></td></tr>
<tr><td align="right"><b><%=intl._t("HTTPS Proxy Username")%>:</b></td>
<td><input name="susername" type="text" value="<jsp:getProperty name="reseedHelper" property="susername" />" ></td></tr>
<tr><td align="right"><b><%=intl._t("HTTPS Proxy Password")%>:</b></td>
<td><input name="nofilter_spassword" type="password" value="<jsp:getProperty name="reseedHelper" property="nofilter_spassword" />" ></td></tr>
-->
<% } // shouldShowHTTPSProxy %>

<% if (reseedHelper.shouldShowHTTPProxy()) { %>
<tr><td align="right"><label for="enableproxy"><b><%=intl._t("Enable proxy for HTTP reseed URLs?")%></b></label></td>
<td><input type="checkbox" class="optbox" name="enable" id="enableproxy" value="true" <jsp:getProperty name="reseedHelper" property="enable" /> ></td></tr>
<tr><td align="right"><b><%=intl._t("HTTP Proxy Host")%>:</b></td>
<td><input name="host" type="text" value="<jsp:getProperty name="reseedHelper" property="host" />" ></td></tr>
<tr><td align="right"><b><%=intl._t("HTTP Proxy Port")%>:</b></td>
<td><input name="port" type="text" size="5" maxlength="5" value="<jsp:getProperty name="reseedHelper" property="port" />" ></td></tr>

<tr><td align="right"><label for="useproxyauth"><b><%=intl._t("Use HTTP Proxy Authorization?")%></b></label></td>
<td><input type="checkbox" class="optbox" name="auth" id="useproxyauth" value="true" <jsp:getProperty name="reseedHelper" property="auth" /> ></td></tr>
<tr><td align="right"><b><%=intl._t("HTTP Proxy Username")%>:</b></td>
<td><input name="username" type="text" value="<jsp:getProperty name="reseedHelper" property="username" />" ></td></tr>
<tr><td align="right"><b><%=intl._t("HTTP Proxy Password")%>:</b></td>
<td><input name="nofilter_password" type="password" value="<jsp:getProperty name="reseedHelper" property="nofilter_password" />" ></td></tr>
<% } // shouldShowHTTPProxy %>

 <tr>
  <td class="optionsave" colspan="2">
<input type="submit" class="cancel" name="foo" value="<%=intl._t("Cancel")%>" />
<input type="submit" name="action" class="download" value="<%=intl._t("Save changes and reseed now")%>" />
<input type="submit" name="action" class="accept" value="<%=intl._t("Save changes")%>" />
  </td>
 </tr>
</table>
</form></div></body></html>
