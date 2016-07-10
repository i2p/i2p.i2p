<%
/*
 *  This file is part of susidns project, see http://susi.i2p/
 *  
 *  Copyright (C) 2005 <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *  
 */

    // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
    if (request.getCharacterEncoding() == null)
        request.setCharacterEncoding("UTF-8");

    response.setHeader("X-Frame-Options", "SAMEORIGIN");
    response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'");
    response.setHeader("X-XSS-Protection", "1; mode=block");
    response.setHeader("X-Content-Type-Options", "nosniff");

%>
<%@page pageEncoding="UTF-8"%>
<%@ page contentType="text/html"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="book" class="i2p.susi.dns.NamingServiceBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<jsp:setProperty name="book" property="*" />
<jsp:setProperty name="book" property="resetDeletionMarks" value="1"/>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title>${book.book} <%=intl._t("addressbook")%> - susidns</title>
<link rel="stylesheet" type="text/css" href="<%=book.getTheme()%>susidns.css">
</head>
<body>
<div class="page">
<div id="logo">
<a href="index"><img src="<%=book.getTheme()%>images/logo.png" alt="" title="<%=intl._t("Overview")%>" border="0"/></a>
</div>
<hr>
<div id="navi">
<p>
<%=intl._t("Address books")%>:
<a href="addressbook?book=private&amp;filter=none&amp;begin=0&amp;end=49"><%=intl._t("private")%></a> |
<a href="addressbook?book=master&amp;filter=none&amp;begin=0&amp;end=49"><%=intl._t("master")%></a> |
<a href="addressbook?book=router&amp;filter=none&amp;begin=0&amp;end=49"><%=intl._t("router")%></a> |
<a href="addressbook?book=published&amp;filter=none&amp;begin=0&amp;end=49"><%=intl._t("published")%></a> *
<a href="subscriptions"><%=intl._t("Subscriptions")%></a> *
<a href="config"><%=intl._t("Configuration")%></a> *
<a href="index"><%=intl._t("Overview")%></a>
</p>
</div>
<hr>
<div id="headline">
<h3><%=intl._t("Address book")%>: <%=intl._t(book.getBook())%></h3>
<h4><%=intl._t("Storage")%>: ${book.displayName}</h4>
</div>

<div id="book">
<%
    String detail = request.getParameter("h");
    if (detail == null) {
        %><p>No host specified</p><%
    } else {
        detail = net.i2p.data.DataHelper.stripHTML(detail);
        java.util.List<i2p.susi.dns.AddressBean> addrs = book.getLookupAll();
        if (addrs == null) {
            %><p>Not found: <%=detail%></p><%
        } else {
            for (i2p.susi.dns.AddressBean addr : addrs) {
                String b32 = addr.getB32();
%>
<jsp:setProperty name="book" property="trClass"	value="0" />
<table class="book" cellspacing="0" cellpadding="5">
<tr class="list${book.trClass}">
<td><%=intl._t("Host Name")%></td>
<td><a href="http://<%=addr.getName()%>/" target="_top"><%=addr.getDisplayName()%></a></td>
</tr><tr class="list${book.trClass}">
<%
    if (addr.isIDN()) {
%>
<td><%=intl._t("Encoded Name")%></td>
<td><a href="http://<%=addr.getName()%>/" target="_top"><%=addr.getName()%></a></td>
</tr><tr class="list${book.trClass}">
<%
    }
%>
<td><%=intl._t("Base 32 Address")%></td>
<td><a href="http://<%=b32%>/" target="_top"><%=b32%></a></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._t("Base 64 Hash")%></td>
<td><%=addr.getB64()%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._t("Address Helper")%></td>
<td><a href="http://<%=addr.getName()%>/?i2paddresshelper=<%=addr.getDestination()%>" target="_top"><%=intl._t("link")%></a></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._t("Public Key")%></td>
<td><%=intl._t("ElGamal 2048 bit")%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._t("Signing Key")%></td>
<td><%=addr.getSigType()%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._t("Certificate")%></td>
<td><%=addr.getCert()%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._t("Added Date")%></td>
<td><%=addr.getAdded()%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._t("Validated")%></td>
<td><%=addr.isValidated() ? intl._t("yes") : intl._t("no")%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._t("Source")%></td>
<td><%=addr.getSource()%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._t("Last Modified")%></td>
<td><%=addr.getModded()%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._t("Notes")%></td>
<td><%=addr.getNotes()%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._t("Destination")%></td>
<td class="destinations"><textarea rows="1" style="height:3em;" wrap="off" cols="70" readonly="readonly" ><%=addr.getDestination()%></textarea></td>
</tr></table>
</div>
<div id="buttons">
<form method="POST" action="addressbook">
<p class="buttons">
<input type="hidden" name="serial" value="${book.serial}">
<input type="hidden" name="begin" value="0">
<input type="hidden" name="end" value="49">
<input type="hidden" name="checked" value="<%=detail%>">
<input class="delete" type="submit" name="action" value="<%=intl._t("Delete Entry")%>" >
</p>
</form>
</div>
<div><table><tr><td>
<img src="/imagegen/id?s=320&amp;c=<%=addr.getB64().replace("=", "%3d")%>" width="320" height="320">
</td><td>
<img src="/imagegen/qr?s=320&amp;t=<%=addr.getName()%>&amp;c=http%3a%2f%2f<%=addr.getName()%>%2f%3fi2paddresshelper%3d<%=addr.getDestination()%>">
</td></tr></table>
<hr>
<%
            }  // foreach addr
%>
</div>
<%
        }  // addrs == null
    }  // detail == null
%>
<div id="footer">
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}" target="_top">susi</a> 2005</p>
</div>
</div>
</body>
</html>
