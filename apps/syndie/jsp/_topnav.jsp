<%@page import="net.i2p.syndie.*, net.i2p.syndie.sml.*, net.i2p.syndie.web.*" %>
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" />
<td valign="top" align="left" class="syndieTopNavBlogsCell" height="10"><a href="index.jsp">Home</a></td>
<td valign="top" align="left" class="syndieTopNavRemoteCell" height="10">
<a href="remote.jsp">Remote archives</a>
<a href="import.jsp">Import</a>
</td>
<form action="<%=request.getRequestURI() + "?" + (request.getQueryString() != null ? request.getQueryString() : "")%>">
<td nowrap="true" valign="top" align="right" class="syndieTopNavManageCell" height="10"><%
if ("true".equals(request.getParameter("logout"))) {
  user.invalidate();
}
String login = request.getParameter("login");
String pass = request.getParameter("password");
String loginSubmit = request.getParameter("Login");
if ( (login != null) && (pass != null) && (loginSubmit != null) && (loginSubmit.equals("Login")) ) {
  String loginResult = BlogManager.instance().login(user, login, pass);
  if (!user.getAuthenticated())
    out.write("<b>" + loginResult + "</b>");
}
%>
<% if (user.getAuthenticated()) { %>
Logged in as: <b><jsp:getProperty property="username" name="user" />:</b>
<a href="<%=HTMLRenderer.getPageURL(user.getBlog(), null, -1, -1, -1, user.getShowExpanded(), user.getShowImages())%>"><%=HTMLRenderer.sanitizeString(ArchiveViewerBean.getBlogName(user.getBlogStr()))%></a>
<a href="<%=HTMLRenderer.getPostURL(user.getBlog())%>">Post</a>
<a href="<%=HTMLRenderer.getMetadataURL(user.getBlog())%>">Metadata</a>
<a href="index.jsp?logout=true">Logout</a><br />
<%} else {%>
Login: <input type="text" name="login" size="8" />
Pass: <input type="password" name="password" size="8" /><%
java.util.Enumeration params = request.getParameterNames();
while (params.hasMoreElements()) {
 String p = (String)params.nextElement();
 String val = request.getParameter(p); 
 %><input type="hidden" name="<%=p%>" value="<%=val%>" /><%
}%>
<input type="submit" name="Login" value="Login" />
<a href="register.jsp">Register</a>
<% } %>

</td>
</form> 