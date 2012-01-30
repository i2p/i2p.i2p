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

%>
<%@page pageEncoding="UTF-8"%>
<%@ page contentType="text/html"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="book" class="i2p.susi.dns.NamingServiceBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<jsp:setProperty name="book" property="*" />
<jsp:setProperty name="book" property="resetDeletionMarks" value="1"/>
<c:forEach items="${paramValues.checked}" var="checked">
<jsp:setProperty name="book" property="markedForDeletion" value="${checked}"/>
</c:forEach>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
<title>${book.book} <%=intl._("address book")%> - susidns</title>
<link rel="stylesheet" type="text/css" href="css.css">
</head>
<body>
<div class="page">
<div id="logo">
<a href="index"><img src="images/logo.png" alt="" title="<%=intl._("Overview")%>" border="0"/></a>
</div>
<hr>
<div id="navi">
<p>
<%=intl._("Address books")%>:
<a href="addressbook?book=private&amp;filter=none&amp;begin=0&amp;end=49"><%=intl._("private")%></a> |
<a href="addressbook?book=master&amp;filter=none&amp;begin=0&amp;end=49"><%=intl._("master")%></a> |
<a href="addressbook?book=router&amp;filter=none&amp;begin=0&amp;end=49"><%=intl._("router")%></a> |
<a href="addressbook?book=published&amp;filter=none&amp;begin=0&amp;end=49"><%=intl._("published")%></a> *
<a href="subscriptions"><%=intl._("Subscriptions")%></a> *
<a href="config"><%=intl._("Configuration")%></a> *
<a href="index"><%=intl._("Overview")%></a>
</p>
</div>
<hr>
<div id="headline">
<h3><%=intl._("Address book")%>: <%=intl._(book.getBook())%></h3>
<h4><%=intl._("Storage")%>: ${book.displayName}</h4>
</div>

<div id="messages">${book.messages}</div>

${book.loadBookMessages}

<c:if test="${book.notEmpty}">
<div id="filter">
<p><%=intl._("Filter")%>:
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
<a href="addressbook?filter=xn--&amp;begin=0&amp;end=49"><%=intl._("other")%></a>
<a href="addressbook?filter=none&amp;begin=0&amp;end=49"><%=intl._("all")%></a></p>
<c:if test="${book.hasFilter}">
<p><%=intl._("Current filter")%>: ${book.filter}
(<a href="addressbook?filter=none&amp;begin=0&amp;end=49"><%=intl._("clear filter")%></a>)</p>
</c:if>
</div>

<div id="search">
<form method="POST" action="addressbook">
<input type="hidden" name="begin" value="0">
<input type="hidden" name="end" value="49">
<table><tr>
<td class="search"><%=intl._("Search")%>: <input class="search" type="text" name="search" value="${book.search}" size="20" ></td>
<td class="search"><input class="search" type="submit" name="submitsearch" value="<%=intl._("Search")%>" ></td>
</tr>
</table>
</form></div>
</c:if>

<%
    // have to only do this once per page
    String susiNonce = book.getSerial();
%>
<c:if test="${book.notEmpty}">
<form method="POST" action="addressbook">
<input type="hidden" name="serial" value="<%=susiNonce%>">
<input type="hidden" name="begin" value="0">
<input type="hidden" name="end" value="49">
<jsp:setProperty name="book" property="trClass"	value="0" />
<div id="book">
<table class="book" cellspacing="0" cellpadding="5">
<tr class="head">

<c:if test="${book.master || book.router || book.published || book.private}">
<th>&nbsp;</th>
</c:if>

<th><%=intl._("Name")%></th>
<th colspan="2"><%=intl._("Links")%></th>
<th><%=intl._("Destination")%></th>
</tr>
<!-- limit iterator, or "Form too large" may result on submit, and is a huge web page if we don't -->
<c:forEach items="${book.entries}" var="addr" begin="${book.resultBegin}" end="${book.resultEnd}">
<tr class="list${book.trClass}">
<c:if test="${book.master || book.router || book.published || book.private}">
<td class="checkbox"><input type="checkbox" name="checked" value="${addr.name}" title="<%=intl._("Mark for deletion")%>"></td>
</c:if>
<td class="names"><a href="http://${addr.name}/">${addr.displayName}</a>
</td><td class="names">
<span class="addrhlpr"><a href="http://${addr.b32}/" title="<%=intl._("Base 32 address")%>">b32</a></span>
</td><td class="names">
<span class="addrhlpr"><a href="details?h=${addr.name}" title="<%=intl._("More information on this entry")%>"><%=intl._("details")%></a></span>
</td>
<td class="destinations"><textarea rows="1" style="height: 3em;" cols="40" wrap="off" readonly="readonly" name="dest_${addr.name}" >${addr.destination}</textarea></td>
</tr>
</c:forEach>
</table>
</div>

<c:if test="${book.master || book.router || book.published || book.private}">
<div id="buttons">
<p class="buttons">
<input class="cancel" type="reset" value="<%=intl._("Cancel")%>" >
<input class="delete" type="submit" name="action" value="<%=intl._("Delete Selected")%>" >
</p>
</div></form>
</c:if>

</c:if>

<c:if test="${book.isEmpty}">
<div id="book">
<p class="book"><%=intl._("This address book is empty.")%></p>
</div>
</c:if>

<form method="POST" action="addressbook">
<input type="hidden" name="serial" value="<%=susiNonce%>">
<input type="hidden" name="begin" value="0">
<input type="hidden" name="end" value="49">
<div id="add">
<h3><%=intl._("Add new destination")%>:</h3>
<table><tr><td>
<b><%=intl._("Host Name")%></b></td><td><input type="text" name="hostname" value="${book.hostname}" size="54">
</td></tr><tr><td>
<b><%=intl._("Destination")%></b></td><td><textarea name="destination" rows="1" style="height: 3em;" cols="70" wrap="off" spellcheck="false">${book.destination}</textarea>
</td></tr></table>
<p class="buttons">
<input class="cancel" type="reset" value="<%=intl._("Cancel")%>" >
<input class="accept" type="submit" name="action" value="<%=intl._("Replace")%>" >
<input class="add" type="submit" name="action" value="<%=intl._("Add")%>" >
</p>
</div></form>

<hr>
<div id="footer">
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}">susi</a> 2005</p>
</div>
</div>
</body>
</html>
