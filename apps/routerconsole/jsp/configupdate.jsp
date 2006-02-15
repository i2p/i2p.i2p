<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config update</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<div class="main" id="main">
 <%@include file="confignav.jsp" %>
  
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <font color="red"><jsp:getProperty name="formhandler" property="errors" /></font>
 <i><jsp:getProperty name="formhandler" property="notices" /></i>
 
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 
 <form action="configupdate.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigUpdateHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigUpdateHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigUpdateHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigUpdateHandler.nonce")%>" />
 <input type="submit" name="action" value="Check for update now" /><br /><br />
 News URL:
 <input type="text" size="60" name="newsURL" value="<jsp:getProperty name="updatehelper" property="newsURL" />"><br />
 Refresh frequency:
 <jsp:getProperty name="updatehelper" property="refreshFrequencySelectBox" /><br />
 Update URL:
 <input type="text" size="60" name="updateURL" value="<jsp:getProperty name="updatehelper" property="updateURL" />"><br />
 Update policy:
 <jsp:getProperty name="updatehelper" property="updatePolicySelectBox" /><br />
Update through the eepProxy?
 <jsp:getProperty name="updatehelper" property="updateThroughProxy" /><br />
 eepProxy host: <input type="text" size="10" name="proxyHost" value="<jsp:getProperty name="updatehelper" property="proxyHost" />" /><br />
 eepProxy port: <input type="text" size="4" name="proxyPort" value="<jsp:getProperty name="updatehelper" property="proxyPort" />" /><br />
 <!-- prompt for the eepproxy -->
 Trusted keys:
 <textarea name="trustedKeys" disabled="true" cols="60" rows="2"><jsp:getProperty name="updatehelper" property="trustedKeys" /></textarea>
 <input type="submit" name="action" value="Save" />
 </form>
</div>

</body>
</html>
