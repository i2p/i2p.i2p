<%@page import="net.i2p.syndie.*, net.i2p.syndie.sml.*" %>
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" />
<jsp:useBean scope="session" class="net.i2p.syndie.data.ArchiveIndex" id="archive" />
<%
if ("true".equals(request.getParameter("logout"))) {
  user.invalidate();
}
String login = request.getParameter("login");
String pass = request.getParameter("password");
String loginSubmit = request.getParameter("Login");
if ( (login != null) && (pass != null) && (loginSubmit != null) && (loginSubmit.equals("Login")) ) {
  String loginResult = BlogManager.instance().login(user, login, pass);
  out.write("<b>" + loginResult + "</b>");
}

if (user.getAuthenticated()) {%>
<b><u><jsp:getProperty property="username" name="user" />:</u></b><br />
<a href="<%=HTMLRenderer.getPageURL(user.getBlog(), null, -1, -1, -1, user.getShowExpanded(), user.getShowImages())%>">My blog</a><br />
<a href="<%=HTMLRenderer.getPageURL(user.getBlog(), null, user.getMostRecentEntry(), -1, -1, true, user.getShowImages())%>">Last post</a><br />
<a href="<%=HTMLRenderer.getPostURL(user.getBlog())%>">Post</a><br />
<a href="<%=HTMLRenderer.getMetadataURL(user.getBlog())%>">Metadata</a><br />
<a href="index.jsp?logout=true">Logout</a><br />
<!--
<hr />
<u>Remote Archives:</u><br />
<a href="viewarchive.jsp?url=eep://politics.i2p/archive/">politics.i2p</a><br />
<a href="viewarchive.jsp?url=freenet://SSK@.../TFE/archive/">TFE</a><br />
-->
<%} else {%>
<form action="<%=request.getRequestURI() + "?" + (request.getQueryString() != null ? request.getQueryString() : "")%>">
<b>Login:</b> <input type="text" name="login" size="8" /><br />
<b>Pass:</b> <input type="password" name="password" size="8" /><br /><%
java.util.Enumeration params = request.getParameterNames();
while (params.hasMoreElements()) {
 String p = (String)params.nextElement();
 String val = request.getParameter(p); 
 %><input type="hidden" name="<%=p%>" value="<%=val%>" /><%
}%>
<input type="submit" name="Login" value="Login" /><br /></form>
<a href="register.jsp">Register</a><br />
<% } %>
