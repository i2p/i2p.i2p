<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.syndie.web.*, net.i2p.syndie.*" %>
<% request.setCharacterEncoding("UTF-8"); %>
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" />
<html>
<head>
<title>SyndieMedia</title>
<link href="style.jsp" rel="stylesheet" type="text/css" />
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr><td colspan="5" valign="top" align="left"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr><td valign="top" align="left" colspan="3"><%
ArchiveViewerBean.renderMetadata(request.getParameterMap(), out); 
if (user.getAuthenticated()) {
  if ("Authorize".equals(request.getParameter("action"))) {
    %><b><%=BlogManager.instance().authorizeRemoteAccess(user, request.getParameter("password"))%></b><%
  }
  if (!user.getAllowAccessRemote()) { 
    if (user.getBlog().toBase64().equals(request.getParameter("blog"))) {
  %><hr /><form action="viewmetadata.jsp" method="POST">
<input type="hidden" name="blog" value="<%=request.getParameter("blog")%>" />
To access remote instances from this instance, please supply the Syndie administration password: <input type="password" name="password" />
<input type="submit" name="action" value="Authorize" />
</form><%
    }
  }
}
%></td></tr>
</table>
</body>