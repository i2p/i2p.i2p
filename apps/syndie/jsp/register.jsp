<%@page import="net.i2p.data.Base64, net.i2p.syndie.web.*, net.i2p.syndie.sml.*, net.i2p.syndie.*" %>
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" />
<html>
<head>
<title>SyndieMedia</title>
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr><td colspan="5" valign="top" align="left"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr><td valign="top" align="left" colspan="3"><%

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
    out.print("<b>Registration successful.</b>  <a href=\"index.jsp\">Continue...</a>\n");
    showForm = false;
  } else {
    out.print("<b>" + regResult + "</b>");
  }
} 
if (showForm) {%><form action="register.jsp" method="POST">
<p>To create a new blog (and Syndie user account), please fill out the following form.  
You may need to enter a registration password given to you by this Syndie instance's
operator, or there may be no registration password in place (in which case you can
leave that field blank).</p>
<p>
<b>Syndie login:</b> <input type="text" size="8" name="login" /><br />
<b>New password:</b> <input type="password" size="8" name="password" /><br />
<b>Registration password:</b> <input type="password" size="8" name="registrationpassword" /><br />
<b>Blog name:</b> <input type="text" size="32" name="blogname" /><br />
<b>Brief description:</b> <input type="text" size="60" name="description" /><br />
<b>Contact URL:</b> <input type="text" size="20" name="contacturl" /> <i>(e.g. mailto://user@mail.i2p, http://foo.i2p/, etc)</i><br />
<input type="submit" name="Register" value="Register" />
</p>
</form><% } %>
</td></tr>
</table>
</body>