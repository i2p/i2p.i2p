<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.data.*, net.i2p.syndie.web.*, net.i2p.syndie.sml.*" %><% 
request.setCharacterEncoding("UTF-8"); 
%><!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 TRANSITIONAL//EN" "http://www.w3c.org/TR/1999/REC-html401-19991224/loose.dtd">
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
<tr class="b_content"><td valign="top" align="left" colspan="3" class="b_content">
<span class="b_externalWarning">Are you sure you really want to go to 
<%
String loc = request.getParameter("location");
String schema = request.getParameter("schema");
String desc = request.getParameter("description");
if (loc != null) loc = HTMLRenderer.sanitizeString(DataHelper.getUTF8(Base64.decode(loc)));
if (schema != null) schema = HTMLRenderer.sanitizeString(DataHelper.getUTF8(Base64.decode(schema)));
if (desc != null) desc = HTMLRenderer.sanitizeString(DataHelper.getUTF8(Base64.decode(desc)));

if ( (loc != null) && (schema != null) ) { 
  out.write("<span class=\"b_externalLoc\">" + loc + "</span> <span class=\"b_externalNet\"(" + schema + ")</span>"); 
  if (desc != null)
    out.write(": <span class=\"b_externalDesc\"" + desc + "\"</span>");
  out.write("? ");
  out.write("<a class=\"b_external\" href=\"" + loc + "\">yes</a>");
} else {
  out.write("<span class=\"b_externalUnknown\">(some unspecified location...)</span>");
}
%></span></td></tr>
</table>
</body>