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
 * $Revision: 1.1 $
 */

    // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
    if (request.getCharacterEncoding() == null)
        request.setCharacterEncoding("UTF-8");

%>
<%@page pageEncoding="UTF-8"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@ page contentType="text/html" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application"/>
<jsp:useBean id="cfg" class="i2p.susi.dns.ConfigBean" scope="session"/>
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<jsp:setProperty name="cfg" property="*" />
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
<title><%=intl._("configuration")%> - susidns</title>
<link rel="stylesheet" type="text/css" href="css.css">
</head>
<body>
<div class="page">
<div id="logo">
<a href="index"><img src="images/logo.png" alt="" title="<%=intl._("Overview")%>" border="0"/></a>
</div><hr>
<div id="navi">
<p>
<%=intl._("Address books")%>:
<a href="addressbook?book=private"><%=intl._("private")%></a> |
<a href="addressbook?book=master"><%=intl._("master")%></a> |
<a href="addressbook?book=router"><%=intl._("router")%></a> |
<a href="addressbook?book=published"><%=intl._("published")%></a> *
<a href="subscriptions"><%=intl._("Subscriptions")%></a> *
<%=intl._("Configuration")%> *
<a href="index"><%=intl._("Overview")%></a>
</p>
</div><hr>
<div id="headline">
<h3>${cfg.fileName}</h3>
</div>
<div id="messages">${cfg.messages}</div>
<form method="POST" action="config">
<div id="config">
<input type="hidden" name="serial" value="${cfg.serial}" >
<textarea name="config" rows="10" cols="80">${cfg.config}</textarea>
</div>
<div id="buttons">
<input type="submit" name="action" value="<%=intl._("Reload")%>" >
<input type="submit" name="action" value="<%=intl._("Save")%>" >
</div>
</form>
<div id="help">
<h3><%=intl._("Hints")%></h3>
<ol>
<li>
<%=intl._("File and directory paths here are relative to the addressbook's working directory, which is normally ~/.i2p/addressbook/ (Linux) or %APPDATA%\\I2P\\addressbook\\ (Windows).")%>
</li>
<li>
<%=intl._("If you want to manually add lines to an addressbook, add them to the private or master addressbooks.")%>
<%=intl._("The router addressbook and the published addressbook are updated by the addressbook application.")%>
</li>
<li>
<%=intl._("When you publish your addressbook, ALL destinations from the master and router addressbooks appear there.")%>
<%=intl._("Use the private addressbook for private destinations, these are not published.")%>
</li>
</ol>
<h3><%=intl._("Options")%></h3>
<ul>
<li><b>subscriptions</b> -
<%=intl._("File containing the list of subscriptions URLs (no need to change)")%>
</li>
<li><b>update_delay</b> -
<%=intl._("Update interval in hours")%>
</li>
<li><b>published_addressbook</b> -
<%=intl._("Your public hosts.txt file (choose a path within your webserver document root)")%>
</li>
<li><b>router_addressbook</b> -
<%=intl._("Your hosts.txt (don't change)")%>
</li>
<li><b>master_addressbook</b> -
<%=intl._("Your personal addressbook, these hosts will be published")%>
</li>
<li><b>private_addressbook</b> -
<%=intl._("Your private addressbook, it is never published")%>
</li>
<li><b>proxy_port</b> -
<%=intl._("Port for your eepProxy (no need to change)")%>
</li>
<li><b>proxy_host</b> -
<%=intl._("Hostname for your eepProxy (no need to change)")%>
</li>
<li><b>should_publish</b> -
<%=intl._("Whether to update the published addressbook")%>
</li>
<li><b>etags</b> -
<%=intl._("File containing the etags header from the fetched subscription URLs (no need to change)")%>
</li>
<li><b>last_modified</b> -
<%=intl._("File containing the modification timestamp for each fetched subscription URL (no need to change)")%>
</li>
<li><b>log</b> -
<%=intl._("File to log activity to (change to /dev/null if you like)")%>
</li>
</ul>
</div><hr>
<div id="footer">
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}">susi</a> 2005 </p>
</div>
</div>
</body>
</html>
