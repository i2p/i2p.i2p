<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config update")%>
</head><body>

<%@include file="summary.jsi" %>
<h1><%=intl._("I2P Update Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHandler" id="formhandler" scope="request" />
 <% formhandler.storeMethod(request.getMethod()); %>
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<div class="messages">
<i><jsp:getProperty name="updatehelper" property="newsStatus" /></i></div>
<div class="configure">
 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<jsp:getProperty name="formhandler" property="newNonce" />" >
 <% /* set hidden default */ %>
 <input type="submit" name="action" value="" style="display:none" >
    <% if (updatehelper.canInstall()) { %>
      <h3><%=intl._("Check for I2P and news updates")%></h3>
      <div class="wideload"><table border="0" cellspacing="5">
        <tr><td colspan="2"></tr>
        <tr><td class= "mediumtags" align="right"><b><%=intl._("News &amp; I2P Updates")%>:</b></td>
     <% } else { %>
      <h3><%=intl._("Check for news updates")%></h3>
      <div class="wideload"><table border="0" cellspacing="5">
        <tr><td colspan="2"></tr>
        <tr><td class= "mediumtags" align="right"><b><%=intl._("News Updates")%>:</b></td>
     <% }   // if canInstall %>
          <td> <% if ("true".equals(System.getProperty("net.i2p.router.web.UpdateHandler.updateInProgress", "false"))) { %> <i><%=intl._("Update In Progress")%></i><br> <% } else { %> <input type="submit" name="action" value="<%=intl._("Check for updates")%>" />
            <% } %></td></tr>
        <tr><td colspan="2"><br></td></tr>
        <tr><td class= "mediumtags" align="right"><b><%=intl._("News URL")%>:</b></td>
          <td><input type="text" size="60" name="newsURL" value="<jsp:getProperty name="updatehelper" property="newsURL" />"></td>
        </tr><tr><td class= "mediumtags" align="right"><b><%=intl._("Refresh frequency")%>:</b>
          <td><jsp:getProperty name="updatehelper" property="refreshFrequencySelectBox" /></td></tr>
    <% if (updatehelper.canInstall()) { %>
        <tr><td class= "mediumtags" align="right"><b><%=formhandler._("Update policy")%>:</b></td>
          <td><jsp:getProperty name="updatehelper" property="updatePolicySelectBox" /></td></tr>
    <% }   // if canInstall %>
        <tr><td class= "mediumtags" align="right"><b><%=intl._("Update through the eepProxy?")%></b></td>
          <td><jsp:getProperty name="updatehelper" property="updateThroughProxy" /></td>
        </tr><tr><td class= "mediumtags" align="right"><b><%=intl._("eepProxy host")%>:</b></td>
          <td><input type="text" size="10" name="proxyHost" value="<jsp:getProperty name="updatehelper" property="proxyHost" />" /></td>
        </tr><tr><td class= "mediumtags" align="right"><b><%=intl._("eepProxy port")%>:</b></td>
          <td><input type="text" size="4" name="proxyPort" value="<jsp:getProperty name="updatehelper" property="proxyPort" />" /></td></tr>
    <% if (updatehelper.canInstall()) { %>
        <tr><td class= "mediumtags" align="right"><b><%=intl._("Update URLs")%>:</b></td>
          <td><textarea name="updateURL" wrap="off" spellcheck="false"><jsp:getProperty name="updatehelper" property="updateURL" /></textarea></td>
        </tr><tr><td class= "mediumtags" align="right"><b><%=intl._("Trusted keys")%>:</b></td>
          <td><textarea name="trustedKeys" wrap="off" spellcheck="false"><jsp:getProperty name="updatehelper" property="trustedKeys" /></textarea></td>
        </tr><tr><td class= "mediumtags" align="right"><b><%=intl._("Update with unsigned development builds?")%></b></td>
          <td><jsp:getProperty name="updatehelper" property="updateUnsigned" /></td>
        </tr><tr><td class= "mediumtags" align="right"><b><%=intl._("Unsigned Build URL")%>:</b></td>
          <td><input type="text" size="60" name="zipURL" value="<jsp:getProperty name="updatehelper" property="zipURL" />"></td></tr>
    <% } else { %>
        <tr><td class= "mediumtags" align="center" colspan="2"><b><%=intl._("Updates will be dispatched via your package manager.")%></b></td></tr>
    <% }   // if canInstall %>
        <tr class="tablefooter"><td colspan="2">
        <div class="formaction">
            <input type="reset" value="<%=intl._("Cancel")%>" >
            <input type="submit" name="action" value="<%=intl._("Save")%>" >
        </div></td></tr></table></div></form></div></div></body></html>
