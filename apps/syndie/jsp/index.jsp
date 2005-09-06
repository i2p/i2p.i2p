<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.syndie.web.*" %><% 
request.setCharacterEncoding("UTF-8"); 
%><!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 TRANSITIONAL//EN" "http://www.w3c.org/TR/1999/REC-html401-19991224/loose.dtd">
<html>
<head>
<title>SyndieMedia</title>
<link href="style.jsp" rel="stylesheet" type="text/css" >
<link href="rss.jsp?<%
if (request.getParameter("blog") != null)
  out.write("blog=" + request.getParameter("blog") + "&");
if (request.getParameter("entry") != null)
  out.write("entry=" + request.getParameter("entry") + "&");
if (request.getParameter("tag") != null)
  out.write("tag=" + request.getParameter("tag") + "&");
if (request.getParameter("selector") != null)
  out.write("selector=" + request.getParameter("selector") + "&");
%>" rel="alternate" type="application/rss+xml" >
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr class="b_toplogo"><td colspan="5" valign="top" align="left" class="b_toplogo"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2" class="b_leftnav"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2" class="b_rightnav"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr class="b_content"><td valign="top" align="left" colspan="3" class="b_content"><jsp:include page="_bodyindex.jsp" /></td></tr>
</table>
</body>