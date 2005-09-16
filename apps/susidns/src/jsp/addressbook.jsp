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
 * $Revision: 1.22 $
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

<div id="logo">
<img src="images/logo.png" alt="susidns logo" border="0"/>
</div>

<div id="navi">
<p>addressbooks
<a href="addressbook.jsp?book=master">master</a> |
<a href="addressbook.jsp?book=router">router</a> |
<a href="addressbook.jsp?book=published">published</a> *
<a href="subscriptions.jsp">subscriptions</a> *
<a href="config.jsp">configuration</a>
</p>
</div>

<div id="headline">
<h3>${book.book} addressbook at ${book.fileName}</h3>
</div>

<div id="messages">${book.messages}</div>

<div id="filter">
<p>Filter: <a href="addressbook.jsp?filter=a">a</a>
<a href="addressbook.jsp?filter=b">b</a>
<a href="addressbook.jsp?filter=c">c</a> 
<a href="addressbook.jsp?filter=d">d</a>
<a href="addressbook.jsp?filter=e">e</a>
<a href="addressbook.jsp?filter=f">f</a>
<a href="addressbook.jsp?filter=g">g</a>
<a href="addressbook.jsp?filter=h">h</a>
<a href="addressbook.jsp?filter=i">i</a>
<a href="addressbook.jsp?filter=j">j</a>
<a href="addressbook.jsp?filter=k">k</a>
<a href="addressbook.jsp?filter=l">l</a>
<a href="addressbook.jsp?filter=m">m</a>
<a href="addressbook.jsp?filter=n">n</a>
<a href="addressbook.jsp?filter=o">o</a>
<a href="addressbook.jsp?filter=p">p</a>
<a href="addressbook.jsp?filter=q">q</a>
<a href="addressbook.jsp?filter=r">r</a>
<a href="addressbook.jsp?filter=s">s</a>
<a href="addressbook.jsp?filter=t">t</a>
<a href="addressbook.jsp?filter=u">u</a>
<a href="addressbook.jsp?filter=v">v</a>
<a href="addressbook.jsp?filter=w">w</a>
<a href="addressbook.jsp?filter=x">x</a>
<a href="addressbook.jsp?filter=y">y</a>
<a href="addressbook.jsp?filter=z">z</a>
<a href="addressbook.jsp?filter=0-9">0-9</a>
<a href="addressbook.jsp?filter=none">all</a></p>
<c:if test="${book.hasFilter}">
<p>Current filter: ${book.filter}</p>
</c:if>
</div>

<form method="POST" action="addressbook.jsp">
<div id="search">
<table><tr>
<td class="search">Search: <input type="text" name="search" value="${book.search}" size="20" /></td>
<td class="search"><input type="image" src="images/search.png" name="submitsearch" value="search" alt="Search" /></td>
</tr>
</table>
</div>

</form>

<form method="POST" action="addressbook.jsp">
<input type="hidden" name="serial" value="${book.serial}"/>

<c:if test="${book.notEmpty}">

<div id="book">
<jsp:setProperty name="book" property="trClass"	value="0" />
<table class="book" cellspacing="0" cellpadding="5">
<tr class="head">

<c:if test="${book.master || book.router}">
<th>&nbsp;</th>
</c:if>

<th>Name</th>
<th>Destination</th>
</tr>
<c:forEach items="${book.entries}" var="addr">
<tr class="list${book.trClass}">
<c:if test="${book.master || book.router}">
<td class="checkbox"><input type="checkbox" name="checked" value="${addr.name}" alt="Mark for deletion"></td>
</c:if>
<td class="names"><a href="http://${addr.name}/">${addr.name}</a> -
<span class="addrhlpr"><a href="http://${addr.name}/?i2paddresshelper=${addr.destination}">(addrhlpr)</a></span>
</td>
<td class="destinations"><input type="text" name="dest_${addr.name}" value="${addr.destination}" size="20"></td>
</tr>
</c:forEach>
</table>
</div>

<c:if test="${book.master}||${book.router}">
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
Hostname: <input type="text" name="hostname" value="" size="20"> Destination: <input type="text" name="destination" value="" size="20"><br/>
<input type="image" name="action" value="add" src="images/add.png" alt="Add destination" />
</p>
</div>

</form>

<div id="footer">
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}">susi</a> 2005</p>
</div>
</body>
</html>
