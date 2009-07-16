<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config UI</title>
<%@include file="css.jsp" %>
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<jsp:useBean class="net.i2p.router.web.ConfigUIHelper" id="uihelper" scope="request" />
<jsp:setProperty name="uihelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<h1>I2P UI Configuration</h1>
<div class="main" id="main">

 <%@include file="confignav.jsp" %>
 
 <jsp:useBean class="net.i2p.router.web.ConfigUIHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
 
<h3>Router Console Theme</h3>
<div class="configure">
 <form action="configui.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigUIHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigUIHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigUIHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigUIHandler.nonce")%>" />
 <input type="hidden" name="action" value="blah" />
 <jsp:getProperty name="uihelper" property="settings" />
<hr> 	
<input type="submit" name="shouldsave" value="Apply" /> <input type="reset" value="Cancel" />
</form>
</div>
</div>
</body>
</html>
