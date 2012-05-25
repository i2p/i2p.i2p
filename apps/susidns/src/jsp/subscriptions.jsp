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
 * $Revision: 1.2 $
 */
%>
<%@ page contentType="text/html"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="subs" class="i2p.susi.dns.SubscriptionsBean" scope="session" />
<jsp:setProperty name="subs" property="*" />
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<title>subscriptions - susidns v${version.version}</title>
<link rel="stylesheet" type="text/css" href="css.css">
</head>
<body>
<div class="page">
<div id="logo">
<img src="images/logo.png" alt="susidns logo" border="0"/>
</div><hr>
<div id="navi">
<p>addressbooks
<a href="addressbook.jsp?book=master">master</a> |
<a href="addressbook.jsp?book=router">router</a> |
<a href="addressbook.jsp?book=published">published</a> |
<a href="addressbook.jsp?book=private">private</a> *
subscriptions *
<a href="config.jsp">configuration</a> *
<a href="index.jsp">overview</a>
</p>
</div><hr>
<div id="headline">
<h3>${subs.fileName}</h3>
</div>
<div id="messages">${subs.messages}</div>
<form method="POST" action="subscriptions.jsp">
<div id="content">
<input type="hidden" name="serial" value="${subs.serial}" />
<textarea name="content" rows="10" cols="80">${subs.content}</textarea>
</div>
<div id="buttons">
<input type="image" src="images/save.png" name="action" value="save" alt="Save Subscriptions" />
<input type="image" src="images/reload.png" name="action" value="reload" alt="Reload Subscriptions" />
</div>
</form>
<div id="help">
<h3>Explanation</h3>
<p class="help">
The subscription file contains a list of (i2p) URLs. The addressbook application
regularly (once per hour) checks this list for new eepsites. Those URLs simply contain the published hosts.txt
file of other people. The default subscription is the hosts.txt from www.i2p2.i2p, which is updated infrequently.
So it is a good idea to add additional subscriptions to sites that have the latest addresses.
</p>
</div><hr>
<div id="footer">
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}">susi</a> 2005</p>
</div>
</div>
</body>
</html>
