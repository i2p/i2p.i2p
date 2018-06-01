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
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Accept-Ranges", "none");

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
<link rel="stylesheet" type="text/css" href="<%=book.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
</head>
<body>
<div class="page">
<div id="logo">
<a href="index"><img src="<%=book.getTheme()%>images/logo.png" alt="" title="<%=intl._t("Overview")%>" border="0"/></a>
</div>
<hr>
<div id="navi">
<a id="overview" href="index"><%=intl._t("Overview")%></a>&nbsp;
<a class="abook" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook" href="addressbook?book=master&amp;filter=none"><%=intl._t("Master")%></a>&nbsp;
<a class="abook" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id="subs" href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id="config" href="config"><%=intl._t("Configuration")%></a>
</div>
<hr>
<div class="headline">
<h3><%=intl._t("Address book")%>: <%=intl._t(book.getBook())%></h3>
<h4><%=intl._t("Storage")%>: ${book.displayName}</h4>
</div>
<div id="book">
<%
    String detail = request.getParameter("h");
    if (detail == null) {
        %><p>No host specified</p><%
    } else {
        // process save notes form
        book.saveNotes();
        detail = net.i2p.data.DataHelper.stripHTML(detail);
        java.util.List<i2p.susi.dns.AddressBean> addrs = book.getLookupAll();
        if (addrs == null) {
            %><p>Not found: <%=detail%></p><%
        } else {
            boolean haveImagegen = book.haveImagegen();
            // use one nonce for all
            String nonce = book.getSerial();
            boolean showNotes = !book.getBook().equals("published");
            for (i2p.susi.dns.AddressBean addr : addrs) {
                String b32 = addr.getB32();
%>
<jsp:setProperty name="book" property="trClass"	value="0" />
<% if (showNotes) { %>
<form method="POST" action="details">
<input type="hidden" name="book" value="${book.book}">
<input type="hidden" name="serial" value="<%=nonce%>">
<input type="hidden" name="h" value="<%=detail%>">
<input type="hidden" name="destination" value="<%=addr.getDestination()%>">
<% }  // showNotes  %>
<table class="book" id="host_details" cellspacing="0" cellpadding="5">
<tr class="list${book.trClass}">
<td><%=intl._t("Hostname")%></td>
<td><a href="http://<%=addr.getName()%>/" target="_top"><%=addr.getDisplayName()%></a></td>
</tr>
<tr class="list${book.trClass}">
<%
    if (addr.isIDN()) {
%>
<td><%=intl._t("Encoded Name")%></td>
<td><a href="http://<%=addr.getName()%>/" target="_top"><%=addr.getName()%></a></td>
</tr>
<tr class="list${book.trClass}">
<%
    }
%>
<td><%=intl._t("Base 32 Address")%></td>
<td><a href="http://<%=b32%>/" target="_top"><%=b32%></a></td>
</tr>
<tr class="list${book.trClass}">
<td><%=intl._t("Base 64 Hash")%></td>
<td><%=addr.getB64()%></td>
</tr>
<tr class="list${book.trClass}">
<td><%=intl._t("Address Helper")%></td>
<td><a href="http://<%=addr.getName()%>/?i2paddresshelper=<%=addr.getDestination()%>" target="_top"><%=intl._t("link")%></a></td>
</tr>
<tr class="list${book.trClass}">
<td><%=intl._t("Public Key")%></td>
<td><%=intl._t("ElGamal 2048 bit")%></td>
</tr>
<tr class="list${book.trClass}">
<td><%=intl._t("Signing Key")%></td>
<td><%=addr.getSigType()%></td>
</tr>
<tr class="list${book.trClass}">
<td><%=intl._t("Certificate")%></td>
<td><%=addr.getCert()%></td>
</tr>
<tr class="list${book.trClass}">
<td><%=intl._t("Validated")%></td>
<td><%=addr.isValidated() ? intl._t("yes") : intl._t("no")%></td>
</tr>
<% if (showNotes) { %>
<tr class="list${book.trClass}">
<td><%=intl._t("Source")%></td>
<td><%=addr.getSource()%></td>
</tr>
<tr class="list${book.trClass}">
<td><%=intl._t("Added Date")%></td>
<td><%=addr.getAdded()%></td>
</tr>
<tr class="list${book.trClass}">
<td><%=intl._t("Last Modified")%></td>
<td><%=addr.getModded()%></td>
</tr>
<% }  // showNotes  %>
<tr class="list${book.trClass}">
<td><%=intl._t("Destination")%></td>
<td class="destinations"><div class="destaddress" tabindex="0"><%=addr.getDestination()%></div></td>
</tr>
<% if (showNotes) { %>
<tr class="list${book.trClass}">
<td><%=intl._t("Notes")%><br>
<input class="accept" type="submit" name="action" value="<%=intl._t("Save Notes")%>"></td>
<td><textarea name="nofilter_notes" rows="3" style="height:6em" wrap="off" cols="70"><%=addr.getNotes()%></textarea></td>
</tr>
<% }  // showNotes  %>
</table>
<% if (showNotes) { %>
</form>
<% }  // showNotes  %>
<div id="buttons">
<form method="POST" action="addressbook">
<p class="buttons">
<input type="hidden" name="book" value="${book.book}">
<input type="hidden" name="serial" value="<%=nonce%>">
<input type="hidden" name="begin" value="0">
<input type="hidden" name="end" value="49">
<input type="hidden" name="checked" value="<%=detail%>">
<input type="hidden" name="destination" value="<%=addr.getDestination()%>">
<input class="delete" type="submit" name="action" value="<%=intl._t("Delete Entry")%>" >
</p>
</form>
</div><%-- buttons --%>
<%
                if (haveImagegen) {
%>
<div id="visualid">
<h3><%=intl._t("Visual Identification for")%> <span id="idAddress"><%=addr.getName()%></span></h3>
<table>
<tr>
<td><img src="/imagegen/id?s=256&amp;c=<%=addr.getB64().replace("=", "%3d")%>" width="256" height="256"></td>
<td><img src="/imagegen/qr?s=384&amp;t=<%=addr.getName()%>&amp;c=http%3a%2f%2f<%=addr.getName()%>%2f%3fi2paddresshelper%3d<%=addr.getDestination()%>"></td>
</tr>
<tr>
<td colspan="2"><a class="fakebutton" href="/imagegen" title="<%=intl._t("Create your own identification images")%>" target="_blank"><%=intl._t("Launch Image Generator")%></a></td>
</tr>
</table>
</div><%-- visualid --%>
<%
                }  // haveImagegen
%>
<hr>
<%
            }  // foreach addr
        }  // addrs == null
    }  // detail == null
%>
</div><%-- book --%>
<div id="footer">
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}" target="_top">susi</a> 2005</p>
</div>
</div><%-- page --%>
</body>
</html>
