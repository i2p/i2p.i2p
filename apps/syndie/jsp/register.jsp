<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.data.Base64, net.i2p.syndie.web.*, net.i2p.syndie.sml.*, net.i2p.syndie.*" %><% 
request.setCharacterEncoding("UTF-8"); 
%><jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" 
/><!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 TRANSITIONAL//EN" "http://www.w3c.org/TR/1999/REC-html401-19991224/loose.dtd">
<html>
<head>
<title>SyndieMedia</title>
<link href="style.jsp" rel="stylesheet" type="text/css" >
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr class="b_toplogo"><td colspan="5" valign="top" align="left" class="b_toplogo"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2" class="b_leftnav"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2" class="b_rightnav"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr class="b_content"><td valign="top" align="left" colspan="3" class="b_content"><%
String regLogin = request.getParameter("login");
boolean showForm = true;
if ( (regLogin != null) && ("Register".equals(request.getParameter("Register"))) ) {
  String regUserPass = request.getParameter("password");
  String regPass = request.getParameter("registrationpassword");
  String blogName = request.getParameter("blogname");
  String desc = request.getParameter("description");
  String url = request.getParameter("contacturl");
  String regResult = BlogManager.instance().register(user, regLogin, regUserPass, regPass, blogName, desc, url);
  if (User.LOGIN_OK.equals(regResult)) {
    %><span class="b_regMsgOk">Registration successful.</span> <a class="b_reg" href="index.jsp">Continue...</a>
<%  showForm = false;
  } else {
    %><span class="b_regMsgErr"><%=regResult%></span><%
  }
} 
if (showForm) {%><form action="register.jsp" method="POST">
<p class="b_reg">To create a new blog (and Syndie user account), please fill out the following form.  
You may need to enter a registration password given to you by this Syndie instance's
operator, or there may be no registration password in place (in which case you can
leave that field blank).</p>
<p class="b_reg">
<em class="b_regField">Syndie login:</em> <input class="b_regField" type="text" size="8" name="login" /><br />
<em class="b_regField">New password:</em> <input class="b_regField" type="password" size="8" name="password" /><br />
<em class="b_regField">Registration password:</em> <input class="b_regField" type="password" size="8" name="registrationpassword" /><br />
<em class="b_regField">Blog name:</em> <input class="b_regField" type="text" size="32" name="blogname" /><br />
<em class="b_regField">Brief description:</em> <input class="b_regField" type="text" size="60" name="description" /><br />
<em class="b_regField">Contact URL:</em> <input class="b_regField" type="text" size="20" name="contacturl" /> <span class="b_reg">(e.g. mailto://user@mail.i2p, http://foo.i2p/, etc)</span><br />
<input class="b_regSubmit" type="submit" name="Register" value="Register" />
</p>
</form><% } %>
</td></tr>
</table>
</body>