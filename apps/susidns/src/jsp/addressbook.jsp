<%
/*
 * Created on Sep 02, 2005
 *
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
 * $Revision: 1.3 $
 */

    // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
    if (request.getCharacterEncoding() == null)
        request.setCharacterEncoding("UTF-8");

    response.setHeader("X-Frame-Options", "SAMEORIGIN");
    response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; form-action 'self'; frame-ancestors 'self'; object-src 'none'; media-src 'none'");
    response.setHeader("X-XSS-Protection", "1; mode=block");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Accept-Ranges", "none");

%><%@page pageEncoding="UTF-8" contentType="text/html" import="net.i2p.servlet.RequestWrapper"
%><%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="book" class="i2p.susi.dns.NamingServiceBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<%
   String importMessages = null;
   if (intl._t("Import").equals(request.getParameter("action"))) {
       RequestWrapper wrequest = new RequestWrapper(request);
       importMessages = book.importFile(wrequest);
   }
%>
<jsp:setProperty name="book" property="*" />
<jsp:setProperty name="book" property="resetDeletionMarks" value="1"/>
<c:forEach items="${paramValues.checked}" var="checked">
<jsp:setProperty name="book" property="markedForDeletion" value="${checked}"/>
</c:forEach>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title>${book.book} <%=intl._t("address book")%> - susidns</title>
<link rel="stylesheet" type="text/css" href="<%=book.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<script src="/js/resetScroll.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<script src="js/messages.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
</head>
<body>
<div class="page">
<hr>
<div id="navi" class="${book.getBook()}">
<a id="overview" href="index"><%=intl._t("Overview")%></a>&nbsp;
<a class="abook private" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook local" href="addressbook?book=local&amp;filter=none"><%=intl._t("Local")%></a>&nbsp;
<a class="abook router" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook published" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id="subs" href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id="config" href="config"><%=intl._t("Configuration")%></a>
</div>
<hr>
<div class="headline" id="addressbook">
<h3 id="addrtitle"><%=intl._t("Address book")%>: <%=intl._t(book.getBook())%></h3>
<h4 id="storagepath"><%=intl._t("Storage")%>: ${book.displayName}</h4>

<%
    // This is what does the form processing.
    // We need to do this before any notEmpty test and before loadBookMessages() which displays the entry count.
    // Messages will be displayed below.
    String formMessages = book.getMessages();
%>

${book.loadBookMessages}

<% if (book.getBook().equals("private")) { %>
<div id="bookdescription">
<p class="bookdescription">
<%=intl._t("Addresses in the Private Address Book are not shared with anyone else unless you copy them by hand.")%>
</p>
</div>
<% } %>

<% if (book.getBook().equals("published")) { %>
<div id="bookdescription">
<p class="bookdescription">
<%=intl._t("The Published Address Book is used for sharing addresses using your I2P Site.")%>
<%=intl._t("Sites in the Private Address Book are not published here.")%>
</p>
</div>
<% } %>

<% if (book.getBook().equals("local")) { %>
<div id="bookdescription">
<p class="bookdescription">
<%=intl._t("This is the Local Address Book.")%>
<%=intl._t("Use it for sites that you host on this computer.")%>
</p>
</div>
<% } %>

<% if (book.getBook().equals("router")) { %>
<div id="bookdescription">
<p class="bookdescription">
<%=intl._t("The Router Address Book is automatically generated by combining the Subscriptions, Local, Private, and Published address books.")%>
</p>
</div>
<% } %>

<c:if test="${book.notEmpty}">
<% if (book.getEntries().length > 0) { /* Don't show if no results. Can't figure out how to do this with c:if */ %>
<form action="export" method="GET" target="_top">
<div id="export">
<input type="hidden" name="book" value="${book.book}">
<c:if test="${book.search} != null && ${book.search}.length() > 0">
<input type="hidden" name="search" value="${book.search}">
</c:if>
<c:if test="${book.hasFilter}">
<input type="hidden" name="filter" value="${book.filter}">
</c:if>
<input type="submit" class="export" value="<%=intl._t("Export in hosts.txt format")%>" />
</div>
</form>
<% } /* book.getEntries().length() > 0 */ %>
</c:if><% /* book.notEmpty */ %>

</div><% /* headline */ %>

<% /* need this whether book is empty or not to display the form messages */ %>
<div id="messages"><%=formMessages%><%
   if (importMessages != null) {
       %><%=importMessages%><%
   }
%></div>

<c:if test="${book.notEmpty}">
<div id="filter">
<c:if test="${book.hasFilter}">
<span><%=intl._t("Current filter")%>: <b>${book.filter}</b>
<a href="addressbook?filter=none&amp;begin=0&amp;end=49"><%=intl._t("clear filter")%></a></span>
</c:if>
<c:if test="${!book.hasFilter}">
<span><%=intl._t("Filter")%></span>
</c:if>
<p>
<a href="addressbook?filter=a&amp;begin=0&amp;end=49">a</a>
<a href="addressbook?filter=b&amp;begin=0&amp;end=49">b</a>
<a href="addressbook?filter=c&amp;begin=0&amp;end=49">c</a>
<a href="addressbook?filter=d&amp;begin=0&amp;end=49">d</a>
<a href="addressbook?filter=e&amp;begin=0&amp;end=49">e</a>
<a href="addressbook?filter=f&amp;begin=0&amp;end=49">f</a>
<a href="addressbook?filter=g&amp;begin=0&amp;end=49">g</a>
<a href="addressbook?filter=h&amp;begin=0&amp;end=49">h</a>
<a href="addressbook?filter=i&amp;begin=0&amp;end=49">i</a>
<a href="addressbook?filter=j&amp;begin=0&amp;end=49">j</a>
<a href="addressbook?filter=k&amp;begin=0&amp;end=49">k</a>
<a href="addressbook?filter=l&amp;begin=0&amp;end=49">l</a>
<a href="addressbook?filter=m&amp;begin=0&amp;end=49">m</a>
<a href="addressbook?filter=n&amp;begin=0&amp;end=49">n</a>
<a href="addressbook?filter=o&amp;begin=0&amp;end=49">o</a>
<a href="addressbook?filter=p&amp;begin=0&amp;end=49">p</a>
<a href="addressbook?filter=q&amp;begin=0&amp;end=49">q</a>
<a href="addressbook?filter=r&amp;begin=0&amp;end=49">r</a>
<a href="addressbook?filter=s&amp;begin=0&amp;end=49">s</a>
<a href="addressbook?filter=t&amp;begin=0&amp;end=49">t</a>
<a href="addressbook?filter=u&amp;begin=0&amp;end=49">u</a>
<a href="addressbook?filter=v&amp;begin=0&amp;end=49">v</a>
<a href="addressbook?filter=w&amp;begin=0&amp;end=49">w</a>
<a href="addressbook?filter=x&amp;begin=0&amp;end=49">x</a>
<a href="addressbook?filter=y&amp;begin=0&amp;end=49">y</a>
<a href="addressbook?filter=z&amp;begin=0&amp;end=49">z</a>
<a href="addressbook?filter=0-9&amp;begin=0&amp;end=49">0-9</a>
<a href="addressbook?filter=xn--&amp;begin=0&amp;end=49"><%=intl._t("other")%></a>
<a href="addressbook?filter=none&amp;begin=0&amp;end=49"><%=intl._t("all")%></a>
</p>
</div>

<div id="search">
<form method="POST" action="addressbook">
<input type="hidden" name="book" value="${book.book}">
<input type="hidden" name="begin" value="0">
<input type="hidden" name="end" value="49">
<div id="booksearch">
<input class="search" type="text" name="search" value="${book.search}" size="20" >
<input class="search" type="submit" name="submitsearch" value="<%=intl._t("Search")%>" >
</div>
</form>
</div>
</c:if>

<%
    // have to only do this once per page
    String susiNonce = book.getSerial();
%>
<c:if test="${book.notEmpty}">
<form method="POST" action="addressbook">
<input type="hidden" name="book" value="${book.book}">
<input type="hidden" name="serial" value="<%=susiNonce%>">
<input type="hidden" name="begin" value="0">
<input type="hidden" name="end" value="49">
<jsp:setProperty name="book" property="trClass"	value="0" />
<div id="book">
<table class="book" id="host_list" cellspacing="0" cellpadding="5">
<tr class="head">

<% if (book.getEntries().length > 0) { /* Don't show if no results. Can't figure out how to do this with c:if */ %>
<th><%=intl._t("Hostname")%></th>
<th><%=intl._t("Link (b32)")%></th>
<th>Helper</th>
<th>Details</th>
<th><%=intl._t("Destination")%></th>

<c:if test="${book.validBook}">
<th title="<%=intl._t("Select hosts for deletion from address book")%>"></th>
</c:if>

</tr>
<%
    boolean haveImagegen = book.haveImagegen();
%>
<!-- limit iterator, or "Form too large" may result on submit, and is a huge web page if we don't -->
<c:forEach items="${book.entries}" var="addr" begin="${book.resultBegin}" end="${book.resultEnd}">
<tr class="list${book.trClass}">
<td class="names">
<%
    if (haveImagegen) {
%>
<a href="/imagegen/id?s=256&amp;c=${addr.b32}" target="_blank" title="<%=intl._t("View larger version of identicon for this hostname")%>"><img src="/imagegen/id?s=20&amp;c=${addr.b32}"></a>
<%
    }  // haveImagegen
%>
<a href="http://${addr.name}/" target="_top">${addr.displayName}</a></td>
<td class="names"><span class="addrhlpr"><a href="http://${addr.b32}/" target="_blank" title="<%=intl._t("Base 32 address")%>">b32</a></span></td>
<td class="helper"><a href="http://${addr.name}/?i2paddresshelper=${addr.destination}" target="_blank" title="<%=intl._t("Helper link to share host address with option to add to address book")%>">link</a></td>
<td class="names"><span class="addrhlpr"><a href="details?h=${addr.name}&amp;book=${book.book}" title="<%=intl._t("More information on this entry")%>"><%=intl._t("details")%></a></span></td>
<td class="destinations"><div class="destaddress resetScrollLeft" name="dest_${addr.name}" width="200px" tabindex="0">${addr.destination}</div></td>

<c:if test="${book.validBook}">
<td class="checkbox"><input type="checkbox" name="checked" value="${addr.name}" title="<%=intl._t("Mark for deletion")%>"></td>
</c:if>

</tr>

</c:forEach>
<% } /* book..getEntries().length() > 0 */ %>
</table>
</div>

<% if (book.getEntries().length > 0) { /* Don't show if no results. Can't figure out how to do this with c:if */ %>
<c:if test="${book.validBook}">
<div id="buttons">
<p class="buttons">
<input class="cancel" type="reset" value="<%=intl._t("Cancel")%>" >
<input class="delete" type="submit" name="action" value="<%=intl._t("Delete Selected")%>" >
</p>
</div>
</c:if>
<% } /* book..getEntries().length() > 0 */ %>
</form>
</c:if><% /* book.notEmpty */ %>

<c:if test="${book.isEmpty}">
<div id="emptybook">
<p class="book"><%=intl._t("This address book is empty.")%></p>
</div>
</c:if>

<form method="POST" action="addressbook">
<input type="hidden" name="book" value="${book.book}">
<input type="hidden" name="serial" value="<%=susiNonce%>">
<input type="hidden" name="begin" value="0">
<input type="hidden" name="end" value="49">
<div id="add">
<h3 id="addnewaddr" class="unexpanded"><%=intl._t("Add new destination")%></h3>
<table id="addnewaddrtable">
<tr>
<td><b><%=intl._t("Hostname")%></b></td>
<td><input type="text" name="hostname" value="${book.hostname}" size="54"></td>
</tr>
<tr>
<td><b><%=intl._t("Destination or Base 32 Address")%></b></td>
<td><input type="text" name="destination" value="${book.destination}" size="70"></td>
</tr>
</table>
<p class="buttons" id="addnewaddrbutton">
<input class="cancel" type="reset" value="<%=intl._t("Cancel")%>" >
<c:if test="${book.notEmpty}">
<input class="accept" type="submit" name="action" value="<%=intl._t("Replace")%>" >
<% if (!book.getBook().equals("published")) { %>
  <input class="add" type="submit" name="action" value="<%=intl._t("Add Alternate")%>" >
<% } %>
</c:if><% /* book.notEmpty */ %>
<input class="add" type="submit" name="action" value="<%=intl._t("Add")%>" >
</p>
</div>
</form>

<% if (!book.getBook().equals("published")) { %>
<form method="POST" action="addressbook" enctype="multipart/form-data" accept-charset="UTF-8">
<input type="hidden" name="book" value="${book.book}">
<input type="hidden" name="serial" value="<%=susiNonce%>">
<input type="hidden" name="begin" value="0">
<input type="hidden" name="end" value="49">
<div id="import">
<h3 id="importhosts" class="unexpanded"><%=intl._t("Import from hosts.txt file")%></h3>
<table id="importhostsform">
<tr>
<td><b><%=intl._t("File")%></b></td>
<td><input name="file" type="file" accept=".txt" value="" /></td>
</tr>
</table>
<p class="buttons" id="importhostsbuttons">
<input class="cancel" type="reset" value="<%=intl._t("Cancel")%>" >
<input class="download" type="submit" name="action" value="<%=intl._t("Import")%>" >
</p>
</div>
</form>
<% } %>

<div id="footer">
<hr>
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}" target="_top">susi</a> 2005</p>
</div>
</div>
</body>
</html>
