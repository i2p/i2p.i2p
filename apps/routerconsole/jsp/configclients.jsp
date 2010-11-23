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
 <div class="configure"><form action="" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigClientsHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigClientsHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigClientsHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigClientsHandler.nonce")%>" >
 <% /* set hidden default */ %>
 <button type="submit" name="action" value="" style="display:none" >Cancel</button>
 <h3><%=intl._("Client Configuration")%></h3><p>
 <%=intl._("The Java clients listed below are started by the router and run in the same JVM.")%>
 </p><div class="wideload">
 <p><jsp:getProperty name="clientshelper" property="form1" />
 </p><p><i><%=intl._("To change other client options, edit the file")%>
 <%=net.i2p.router.startup.ClientAppConfig.configFile(net.i2p.I2PAppContext.getGlobalContext()).getAbsolutePath()%>.
 <%=intl._("All changes require restart to take effect.")%></i>
 </p><hr><div class="formaction">
 <input type="submit" name="foo" value="<%=intl._("Cancel")%>" />
<% if (request.getParameter("edit") == null) { %>
 <input type="submit" name="edit" value="<%=intl._("Add Client")%>" />
<% } %>
 <input type="submit" name="action" value="<%=intl._("Save Client Configuration")%>" />
</div></div><h3><a name="webapp"></a><%=intl._("WebApp Configuration")%></h3><p>
 <%=intl._("The Java web applications listed below are started by the webConsole client and run in the same JVM as the router. They are usually web applications accessible through the router console. They may be complete applications (e.g. i2psnark),front-ends to another client or application which must be separately enabled (e.g. susidns, i2ptunnel), or have no web interface at all (e.g. addressbook).")%>
 </p><p>
 <%=intl._("A web app may also be disabled by removing the .war file from the webapps directory; however the .war file and web app will reappear when you update your router to a newer version, so disabling the web app here is the preferred method.")%>
 </p><div class="wideload"><p>
 <jsp:getProperty name="clientshelper" property="form2" />
 </p><p>
 <i><%=intl._("All changes require restart to take effect.")%></i>
 </p><hr><div class="formaction">
 <input type="submit" name="action" value="<%=intl._("Save WebApp Configuration")%>" />
</div></div>
<% if (clientshelper.showPlugins()) { %>
<h3><a name="pconfig"></a><%=intl._("Plugin Configuration")%></h3><p>
 <%=intl._("The plugins listed below are started by the webConsole client.")%>
 </p><div class="wideload"><p>
 <jsp:getProperty name="clientshelper" property="form3" />
 </p><hr><div class="formaction">
 <input type="submit" name="action" value="<%=intl._("Save Plugin Configuration")%>" />
</div></div><h3><a name="plugin"></a><%=intl._("Plugin Installation")%></h3><p>
 <%=intl._("To install a plugin, enter the download URL:")%>
 </p><div class="wideload"><p>
 <input type="text" size="60" name="pluginURL" >
 </p><hr><div class="formaction">
 <input type="submit" name="action" value="<%=intl._("Install Plugin")%>" />
 </div></div>
<% } %>
</form></div></div></body></html>
