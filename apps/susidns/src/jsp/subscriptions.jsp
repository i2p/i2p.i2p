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

    // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
    if (request.getCharacterEncoding() == null)
        request.setCharacterEncoding("UTF-8");

    response.setHeader("X-Frame-Options", "SAMEORIGIN");
    response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'");
    response.setHeader("X-XSS-Protection", "1; mode=block");

%>
<%@page pageEncoding="UTF-8"%>
<%@ page contentType="text/html"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="subs" class="i2p.susi.dns.SubscriptionsBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<jsp:setProperty name="subs" property="*" />
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title><%=intl._t("subscriptions")%> - susidns</title>
<link rel="stylesheet" type="text/css" href="<%=subs.getTheme()%>susidns.css">
</head>
<body>
<div class="page">
<div id="logo">
<a href="index"><img src="<%=subs.getTheme()%>images/logo.png" alt="" title="<%=intl._t("Overview")%>" border="0"/></a>
</div><hr>
<div id="navi">
<p>
<%=intl._t("Address books")%>:
<a href="addressbook?book=private"><%=intl._t("private")%></a> |
<a href="addressbook?book=master"><%=intl._t("master")%></a> |
<a href="addressbook?book=router"><%=intl._t("router")%></a> |
<a href="addressbook?book=published"><%=intl._t("published")%></a> *
<%=intl._t("Subscriptions")%> *
<a href="config"><%=intl._t("Configuration")%></a> *
<a href="index"><%=intl._t("Overview")%></a>
</p>
</div><hr>
<div id="headline">
<h3>${subs.fileName}</h3>
</div>
<div id="messages">${subs.messages}</div>
<form method="POST" action="subscriptions">
<div id="content">
<input type="hidden" name="serial" value="${subs.serial}" >
<textarea name="content" rows="10" cols="80">${subs.content}</textarea>
</div>
<div id="buttons">
<input class="reload" type="submit" name="action" value="<%=intl._t("Reload")%>" >
<input class="accept" type="submit" name="action" value="<%=intl._t("Save")%>" >
</div>
</form>
<div id="help">
<p class="help">
<%=intl._t("The subscription file contains a list of i2p URLs.")%>
<%=intl._t("The addressbook application regularly checks this list for new eepsites.")%>
<%=intl._t("Those URLs refer to published hosts.txt files.")%>
<%=intl._t("The default subscription is the hosts.txt from {0}, which is updated infrequently.", "i2p-projekt.i2p")%>
<%=intl._t("So it is a good idea to add additional subscriptions to sites that have the latest addresses.")%>
<a href="http://i2p-projekt.i2p/faq.html#subscriptions" target="_top"><%=intl._t("See the FAQ for a list of subscription URLs.")%></a>
</p>
</div>
<div id="footer">
<hr>
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}" target="_top">susi</a> 2005</p>
</div>
</div>
</body>
</html>
