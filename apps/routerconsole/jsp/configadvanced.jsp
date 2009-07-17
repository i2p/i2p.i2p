<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config advanced</title>
<%@include file="css.jsp" %>
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<jsp:useBean class="net.i2p.router.web.ConfigAdvancedHelper" id="advancedhelper" scope="request" />
<jsp:setProperty name="advancedhelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<h1>I2P Advanced Configuration</h1>
<div class="main" id="main">

 <%@include file="confignav.jsp" %>
 
 <jsp:useBean class="net.i2p.router.web.ConfigAdvancedHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
 <div class="configure">
 <form action="configadvanced.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigAdvancedHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigAdvancedHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigAdvancedHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigAdvancedHandler.nonce")%>" />
 <input type="hidden" name="action" value="blah" />
 <textarea rows="32" cols="100" name="config" wrap="off"><jsp:getProperty name="advancedhelper" property="settings" /></textarea><br /><hr>
      <p align="right"> 
        <input type="submit" name="shouldsave" value="Apply" />
        <input type="reset" value="Cancel" />
      </p>
      <p>
 NOTE: Some changes may require a restart to take effect.
</p>
 </form>
</div>
</div>
</body>
</html>
