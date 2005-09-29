<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.syndie.web.*, net.i2p.syndie.*" %><% 
request.setCharacterEncoding("UTF-8"); 
%><jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" 
/><!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 TRANSITIONAL//EN" "http://www.w3c.org/TR/1999/REC-html401-19991224/loose.dtd">
<html>
<head>
<title>SyndieMedia metadata</title>
<link href="style.jsp" rel="stylesheet" type="text/css" >
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr class="b_toplogo"><td colspan="5" valign="top" align="left" class="b_toplogo"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2" class="b_leftnav"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2" class="b_rightnav"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr class="b_content"><td valign="top" align="left" colspan="3" class="b_content"><%
ArchiveViewerBean.renderMetadata(user, request.getRequestURI(), request.getParameterMap(), out); 
if (user.getAuthenticated()) {
  if ("Authorize".equals(request.getParameter("action"))) {
    %><span class="b_metaStatus"><%=BlogManager.instance().authorizeRemoteAccess(user, request.getParameter("password"))%></span><%
  }
  if (!user.getAllowAccessRemote()) { 
    if (user.getBlog().toBase64().equals(request.getParameter("blog"))) {
  %><hr /><form action="viewmetadata.jsp" method="POST">
<input type="hidden" name="blog" value="<%=request.getParameter("blog")%>" />
<span class="b_metaAuthorize">To access remote instances from this instance, please supply the Syndie administration password:</span> 
<input class="b_metaAuthorize" type="password" name="password" />
<input class="b_metaAuthorizeSubmit" type="submit" name="action" value="Authorize" />
</form><%
    }
  }
}
%></td></tr>
</table>
</body>