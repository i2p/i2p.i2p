<%@page import="net.i2p.data.Base64, net.i2p.syndie.web.*, net.i2p.syndie.sml.*, net.i2p.syndie.*" %>
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
String nameStr = request.getParameter("name");
String locStr = request.getParameter("location");
String schemaStr = request.getParameter("schema");
String name = null;
String location = null;
String schema = null;
try {
    name = new String(Base64.decode(nameStr));
    location = new String(Base64.decode(locStr));
    schema = new String(Base64.decode(schemaStr));
} catch (NullPointerException npe) {
    // ignore
}

if ( (name == null) || (location == null) || (schema == null) ) {
  out.write("<b>No location specified</b>");
} else if (user.getAuthenticated() && ("Add".equals(request.getParameter("action"))) ) {
  out.write("<b>" + BlogManager.instance().addAddress(user, name, location, schema) + "</b>");
} else { %>Are you sure you really want to add the
addressbook mapping of <%=HTMLRenderer.sanitizeString(name)%> to
<input type="text" size="20" value="<%=HTMLRenderer.sanitizeString(location)%>" />, applicable within the
schema <%=HTMLRenderer.sanitizeString(schema)%>?  
<%  if (!user.getAuthenticated()) { %>
<p />If so, add the line 
<input type="text" size="20" value="<%=HTMLRenderer.sanitizeString(name)%>=<%=HTMLRenderer.sanitizeString(location)%>" />
to your <code>userhosts.txt</code>.
<%   } else { %><br />
<a href="addaddress.jsp?name=<%=HTMLRenderer.sanitizeURL(name)%>&location=<%=HTMLRenderer.sanitizeURL(location)%>&schema=<%=HTMLRenderer.sanitizeURL(schema)%>&action=Add">Yes, add it</a>.
<%   } 
} %></td></tr>
</table>
</body>