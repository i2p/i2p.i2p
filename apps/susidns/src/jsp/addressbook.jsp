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
%>
<%@ page contentType="text/html"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="book" class="i2p.susi.dns.AddressbookBean" scope="session" />
<jsp:setProperty name="book" property="*" />
<jsp:setProperty name="book" property="resetDeletionMarks" value="1"/>
<c:forEach items="${paramValues.checked}" var="checked">
<jsp:setProperty name="book" property="markedForDeletion" value="${checked}"/>
</c:forEach>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<title>${book.book} addressbook - susidns v${version.version}</title>
<link rel="stylesheet" type="text/css" href="css.css">
</head>
<body>
<div class="page">
<div id="logo">
<img src="images/logo.png" alt="susidns logo" border="0"/>
</div>
<hr>
<div id="navi">
<p>addressbooks
<a href="addressbook.jsp?book=master&filter=none&begin=0&end=99">master</a> |
<a href="addressbook.jsp?book=router&filter=none&begin=0&end=99">router</a> |
<a href="addressbook.jsp?book=published&filter=none&begin=0&end=99">published</a> |
<a href="addressbook.jsp?book=private&filter=none&begin=0&end=99">private</a> *
<a href="subscriptions.jsp">subscriptions</a> *
<a href="config.jsp">configuration</a> *
<a href="index.jsp">overview</a>
</p>
</div>
<hr>
<div id="headline">
<h3>${book.book} addressbook at ${book.fileName}</h3>
</div>

<div id="messages">${book.messages}</div>

<span>${book.loadBookMessages}</span>

<c:if test="${book.notEmpty}">
<div id="filter">
<p>Filter:
<a href="addressbook.jsp?filter=a&begin=0&end=99">a</a>
<a href="addressbook.jsp?filter=b&begin=0&end=99">b</a>
<a href="addressbook.jsp?filter=c&begin=0&end=99">c</a> 
<a href="addressbook.jsp?filter=d&begin=0&end=99">d</a>
<a href="addressbook.jsp?filter=e&begin=0&end=99">e</a>
<a href="addressbook.jsp?filter=f&begin=0&end=99">f</a>
<a href="addressbook.jsp?filter=g&begin=0&end=99">g</a>
<a href="addressbook.jsp?filter=h&begin=0&end=99">h</a>
<a href="addressbook.jsp?filter=i&begin=0&end=99">i</a>
<a href="addressbook.jsp?filter=j&begin=0&end=99">j</a>
<a href="addressbook.jsp?filter=k&begin=0&end=99">k</a>
<a href="addressbook.jsp?filter=l&begin=0&end=99">l</a>
<a href="addressbook.jsp?filter=m&begin=0&end=99">m</a>
<a href="addressbook.jsp?filter=n&begin=0&end=99">n</a>
<a href="addressbook.jsp?filter=o&begin=0&end=99">o</a>
<a href="addressbook.jsp?filter=p&begin=0&end=99">p</a>
<a href="addressbook.jsp?filter=q&begin=0&end=99">q</a>
<a href="addressbook.jsp?filter=r&begin=0&end=99">r</a>
<a href="addressbook.jsp?filter=s&begin=0&end=99">s</a>
<a href="addressbook.jsp?filter=t&begin=0&end=99">t</a>
<a href="addressbook.jsp?filter=u&begin=0&end=99">u</a>
<a href="addressbook.jsp?filter=v&begin=0&end=99">v</a>
<a href="addressbook.jsp?filter=w&begin=0&end=99">w</a>
<a href="addressbook.jsp?filter=x&begin=0&end=99">x</a>
<a href="addressbook.jsp?filter=y&begin=0&end=99">y</a>
<a href="addressbook.jsp?filter=z&begin=0&end=99">z</a>
<a href="addressbook.jsp?filter=0-9&begin=0&end=99">0-9</a>
<a href="addressbook.jsp?filter=none&begin=0&end=99">all</a></p>
<c:if test="${book.hasFilter}">
<p>Current filter: ${book.filter}
(<a href="addressbook.jsp?filter=none&begin=0&end=99">clear filter</a>)</p>
</c:if>
</div>

<form method="POST" action="addressbook.jsp">
<input type="hidden" name="begin" value="0"/>
<input type="hidden" name="end" value="99"/>
<div id="search">
<table><tr>
<td class="search">Search: <input type="text" name="search" value="${book.search}" size="20" /></td>
<td class="search"><input type="image" src="images/search.png" name="submitsearch" value="search" alt="Search" /></td>
</tr>
</table>
</div>

</form>
</c:if>

<form method="POST" action="addressbook.jsp">
<input type="hidden" name="serial" value="${book.serial}"/>
<input type="hidden" name="begin" value="0"/>
<input type="hidden" name="end" value="99"/>

<c:if test="${book.notEmpty}">

<div id="book">
<jsp:setProperty name="book" property="trClass"	value="0" />
<table class="book" cellspacing="0" cellpadding="5">
<tr class="head">

<c:if test="${book.master || book.router || book.published || book.private}">
<th>&nbsp;</th>
</c:if>

<th>Name</th>
<th>Destination</th>
</tr>
<!-- limit iterator, or "Form too large" may result on submit, and is a huge web page if we don't -->
<c:forEach items="${book.entries}" var="addr" begin="${book.begin}" end="${book.end}">
<tr class="list${book.trClass}">
<c:if test="${book.master || book.router || book.published || book.private}">
<td class="checkbox"><input type="checkbox" name="checked" value="${addr.name}" alt="Mark for deletion"></td>
</c:if>
<td class="names"><a href="http://${addr.name}/">${addr.name}</a> -
<span class="addrhlpr"><a href="http://${addr.name}/?i2paddresshelper=${addr.destination}">(addrhlpr)</a></span>
</td>
<td class="destinations"><textarea rows="1" style="height: 3em;" cols="40" wrap="off" readonly="readonly" name="dest_${addr.name}" >${addr.destination}</textarea></td>
</tr>
</c:forEach>
</table>
</div>

<c:if test="${book.master || book.router || book.published || book.private}">
<div id="buttons">
<p class="buttons"><input type="image" name="action" value="delete" src="images/delete.png" alt="Delete checked" />
</p>
</div>
</c:if>

</c:if>

<c:if test="${book.isEmpty}">
<div id="book">
<p class="book">The ${book.book} addressbook is empty.</p>
</div>
</c:if>

<div id="add">
<p class="add">
<h3>Add new destination:</h3>
<b>Hostname:</b> <input type="text" name="hostname" value="${book.hostname}" size="20">
<b>Destination:</b> <textarea name="destination" rows="1" style="height: 3em;" cols="40" wrap="off" >${book.destination}</textarea><br/>
</p><p>
<input type="image" name="action" value="add" src="images/add.png" alt="Add destination" />
</p>
</div>

</form>
<hr>
<div id="footer">
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}">susi</a> 2005</p>
</div>
</div>
</body>
</html>
