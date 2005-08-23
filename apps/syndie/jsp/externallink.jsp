<%@page import="net.i2p.data.Base64, net.i2p.syndie.web.*, net.i2p.syndie.sml.*" %>
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
<tr><td valign="top" align="left" colspan="3">Are you sure you really want to go to 
<%
String loc = request.getParameter("location");
String schema = request.getParameter("schema");
String desc = request.getParameter("description");
if (loc != null) loc = HTMLRenderer.sanitizeString(new String(Base64.decode(loc)));
if (schema != null) schema = HTMLRenderer.sanitizeString(new String(Base64.decode(schema)));
if (desc != null) desc = HTMLRenderer.sanitizeString(new String(Base64.decode(desc)));

if ( (loc != null) && (schema != null) ) { 
  out.write(loc + " (" + schema + ")"); 
  if (desc != null)
    out.write(": " + desc);
  out.write("? ");
  out.write("<a href=\"" + loc + "\">yes</a>");
} else {
  out.write("(some unspecified location...)");
}
%></td></tr>
</table>
</body>