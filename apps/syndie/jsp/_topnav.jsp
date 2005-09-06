<%@page import="net.i2p.syndie.*, net.i2p.syndie.sml.*, net.i2p.syndie.web.*" %>
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" />
<form action="<%=request.getRequestURI() + "?" + (request.getQueryString() != null ? request.getQueryString() : "")%>">
<td nowrap="true" colspan="2" height="10" class="b_topnav">
<span class="b_topnavHome"><a href="index.jsp" class="b_topnavHome">Home</a></span>
<a href="admin.jsp" class="b_topnavAdmin">Syndie admin</a>
<a href="remote.jsp" class="b_topnavRemote">Remote archives</a>
<a href="import.jsp" class="b_topnavImport">Import</a>
</td><td nowrap="true" height="10" class="b_topnavUser"><%
if ("true".equals(request.getParameter("logout"))) {
  user.invalidate();
  RemoteArchiveBean rem = (RemoteArchiveBean)session.getAttribute("remote");
  if (rem != null) rem.reinitialize();
  PostBean post = (PostBean)session.getAttribute("post");
  if (post != null) post.reinitialize();
}
String login = request.getParameter("login");
String pass = request.getParameter("password");
String loginSubmit = request.getParameter("Login");
if ( (login != null) && (pass != null) && (loginSubmit != null) && (loginSubmit.equals("Login")) ) {
  String loginResult = BlogManager.instance().login(user, login, pass);
  if (!user.getAuthenticated())
    out.write("<b class=\"b_topnavLoginResult\">" + loginResult + "</b>");
}
%>
<% if (user.getAuthenticated()) { %>
<span class="b_topnavUsername">Logged in as:</span> <em class="b_topnavUsername"><jsp:getProperty property="username" name="user" />:</em>
<a class="b_topnavBlog" href="<%=HTMLRenderer.getPageURL(user.getBlog(), null, -1, -1, -1, user.getShowExpanded(), user.getShowImages())%>"><%=HTMLRenderer.sanitizeString(ArchiveViewerBean.getBlogName(user.getBlogStr()))%></a>
<a class="b_topnavPost" href="<%=HTMLRenderer.getPostURL(user.getBlog())%>">Post</a>
<a class="b_topnavMeta" href="<%=HTMLRenderer.getMetadataURL(user.getBlog())%>">Metadata</a>
<a class="b_topnavAddr" href="addresses.jsp">Addressbook</a>
<a class="b_topnavLogout" href="index.jsp?logout=true">Logout</a>
<%} else {%>
<span class="b_topnavLogin">Login:</span> <input class="b_topnavLogin" type="text" name="login" size="8" />
<span class="b_topnavPass">Pass:</span> <input class="b_topnavPass" type="password" name="password" size="8" /><%
java.util.Enumeration params = request.getParameterNames();
while (params.hasMoreElements()) {
 String p = (String)params.nextElement();
 String val = request.getParameter(p); 
 %><input type="hidden" name="<%=p%>" value="<%=val%>" /><%
}%>
<input class="b_topnavLoginSubmit" type="submit" name="Login" value="Login" />
<a class="b_topnavRegister" href="register.jsp">Register</a>
<% } %>
</td>
</form> 