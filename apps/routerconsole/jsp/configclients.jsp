<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config clients")%>
<style type='text/css'>
button span.hide{
    display:none;
}
</style></head><body>

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigClientsHelper" id="clientshelper" scope="request" />
<jsp:setProperty name="clientshelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<jsp:setProperty name="clientshelper" property="edit" value="<%=request.getParameter("edit")%>" />
<h1><%=intl._("I2P Client Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigClientsHandler" id="formhandler" scope="request" />
 <% formhandler.storeMethod(request.getMethod()); %>
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:setProperty name="formhandler" property="action" value="<%=request.getParameter("action")%>" />
 <jsp:setProperty name="formhandler" property="nonce" value="<%=request.getParameter("nonce")%>" />
 <jsp:setProperty name="formhandler" property="settings" value="<%=request.getParameterMap()%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
 <div class="configure">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigClientsHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigClientsHandler.noncePrev", prev);
    String pageNonce = Long.toString(new java.util.Random().nextLong());
    System.setProperty("net.i2p.router.web.ConfigClientsHandler.nonce", pageNonce); %>
 <h3><%=intl._("Client Configuration")%></h3><p>
 <%=intl._("The Java clients listed below are started by the router and run in the same JVM.")%>
 </p><div class="wideload">
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<jsp:getProperty name="clientshelper" property="form1" />
<p><i><%=intl._("To change other client options, edit the file")%>
 <%=net.i2p.router.startup.ClientAppConfig.configFile(net.i2p.I2PAppContext.getGlobalContext()).getAbsolutePath()%>.
 <%=intl._("All changes require restart to take effect.")%></i>
 </p><hr><div class="formaction">
 <input type="submit" name="foo" value="<%=intl._("Cancel")%>" />
<% if (request.getParameter("edit") == null) { %>
 <input type="submit" name="edit" value="<%=intl._("Add Client")%>" />
<% } %>
 <input type="submit" name="action" value="<%=intl._("Save Client Configuration")%>" />
</div></form></div>

<h3><a name="i2cp"></a><%=intl._("Advanced Client Interface Configuration")%></h3>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<p>
<b><%=intl._("External I2CP (I2P Client Protocol) Interface Configuration")%></b><br>
<input type="radio" class="optbox" name="mode" value="1" <%=clientshelper.i2cpModeChecked(1) %> >
<%=intl._("Enabled without SSL")%><br>
<input type="radio" class="optbox" name="mode" value="2" <%=clientshelper.i2cpModeChecked(2) %> >
<%=intl._("Enabled with SSL required")%><br>
<input type="radio" class="optbox" name="mode" value="0" <%=clientshelper.i2cpModeChecked(0) %> >
<%=intl._("Disabled - Clients outside this Java process may not connect")%><br>
<%=intl._("I2CP Port")%>:
<input name="port" type="text" size="5" maxlength="5" value="<jsp:getProperty name="clientshelper" property="port" />" ><br>
<%=intl._("I2CP Interface")%>:
<select name="interface">
<%
       String[] ips = clientshelper.intfcAddresses();
       for (int i = 0; i < ips.length; i++) {
           out.print("<option value=\"");
           out.print(ips[i]);
           out.print('\"');
           if (clientshelper.isIFSelected(ips[i]))
               out.print(" selected=\"selected\"");
           out.print('>');
           out.print(ips[i]);
           out.print("</option>\n");
       }
%>
</select><br>
<b><%=intl._("Authorization")%></b><br>
<input type="checkbox" class="optbox" name="auth" value="true" <jsp:getProperty name="clientshelper" property="auth" /> >
<%=intl._("Require username and password")%><br>
<%=intl._("Username")%>:
<input name="user" type="text" value="<jsp:getProperty name="clientshelper" property="user" />" ><br>
<%=intl._("Password")%>:
<input name="pw" type="password" value="<jsp:getProperty name="clientshelper" property="pw" />" ><br>
</p><p><b><%=intl._("The default settings will work for most people.")%></b>
<%=intl._("Any changes made here must also be configured in the external client.")%>
<%=intl._("Many clients do not support SSL or authorization.")%>
<i><%=intl._("All changes require restart to take effect.")%></i>
</p><hr><div class="formaction">
<input type="submit" name="foo" value="<%=intl._("Cancel")%>" />
<input type="submit" name="action" value="<%=intl._("Save Interface Configuration")%>" />
</div></form>

<h3><a name="webapp"></a><%=intl._("WebApp Configuration")%></h3><p>
 <%=intl._("The Java web applications listed below are started by the webConsole client and run in the same JVM as the router. They are usually web applications accessible through the router console. They may be complete applications (e.g. i2psnark),front-ends to another client or application which must be separately enabled (e.g. susidns, i2ptunnel), or have no web interface at all (e.g. addressbook).")%>
 </p><p>
 <%=intl._("A web app may also be disabled by removing the .war file from the webapps directory; however the .war file and web app will reappear when you update your router to a newer version, so disabling the web app here is the preferred method.")%>
 </p><div class="wideload">
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <jsp:getProperty name="clientshelper" property="form2" />
 <p><i><%=intl._("All changes require restart to take effect.")%></i>
 </p><hr><div class="formaction">
 <input type="submit" name="action" value="<%=intl._("Save WebApp Configuration")%>" />
</div></form></div>

<% if (clientshelper.showPlugins()) { %>
<h3><a name="pconfig"></a><%=intl._("Plugin Configuration")%></h3><p>
 <%=intl._("The plugins listed below are started by the webConsole client.")%>
 </p><div class="wideload">
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <jsp:getProperty name="clientshelper" property="form3" />
<hr><div class="formaction">
 <input type="submit" name="action" value="<%=intl._("Save Plugin Configuration")%>" />
</div></form></div>

<h3><a name="plugin"></a><%=intl._("Plugin Installation")%></h3><p>
 <%=intl._("To install a plugin, enter the download URL:")%>
 </p><div class="wideload">
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<p>
 <input type="text" size="60" name="pluginURL" >
 </p><hr><div class="formaction">
 <input type="submit" name="action" value="<%=intl._("Install Plugin")%>" />
 </div></form></div>
<% } %>
</div></div></body></html>
