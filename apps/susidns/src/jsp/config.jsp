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
%>
<%@ page contentType="text/html" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application"/>
<jsp:useBean id="cfg" class="i2p.susi.dns.ConfigBean" scope="session"/>
<jsp:setProperty name="cfg" property="*" />
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<title>configuration - susidns v${version.version}</title>
<link rel="stylesheet" type="text/css" href="css.css">
</head>
<body>
<div class="page">
<div id="logo">
<img src="images/logo.png" alt="susidns logo" border="0"/>
</div><hr>
<div id="navi">
<p>
addressbooks
<a href="addressbook.jsp?book=master">master</a> |
<a href="addressbook.jsp?book=router">router</a> |
<a href="addressbook.jsp?book=published">published</a> |
<a href="addressbook.jsp?book=private">private</a> *
<a href="subscriptions.jsp">subscriptions</a> *
configuration *
<a href="index.jsp">overview</a>
</p>
</div><hr>
<div id="headline">
<h3>${cfg.fileName}</h3>
</div>
<div id="messages">${cfg.messages}</div>
<form method="POST" action="config.jsp">
<div id="config">
<input type="hidden" name="serial" value="${cfg.serial}" />
<textarea name="config" rows="10" cols="80">${cfg.config}</textarea>
</div>
<div id="buttons">
<input type="image" src="images/save.png" name="action" value="save" alt="Save Config"/>
<input type="image" src="images/reload.png" name="action" value="reload" alt="Reload Config"/>
</div>
</form>
<div id="help">
<h3>Hints</h3>
<ol>
<li>All file or directory paths here are relative to the addressbook's working directory, which normally
is located at $I2P/addressbook/.</li>
<li>If you want to manually add lines to an addressbook, add them to the private or master addressbooks. The router
addressbook and the published addressbook are overwritten by the addressbook application.</li>
<li><b>Important:</b>When you publish your addressbook, <b>ALL</b> destinations from the master and router addressbooks appear there.
Use the private addressbook for private destinations, these are not published.
</li>
</ol>
<h3>Options</h3>
<ul>
<li><b>subscriptions</b> - file containing the list of subscriptions URLs (no need to change)</li>
<li><b>update_delay</b> - update interval in hours (no need to change)</li>
<li><b>published_addressbook</b> - your public hosts.txt file (choose a path within your webserver document root)</li>
<li><b>router_addressbook</b> - your hosts.txt (don't change)</li>
<li><b>master_addressbook</b> - your personal addressbook, it never gets overwritten by the addressbook (don't change)</li>
<li><b>private_addressbook</b> - your private addressbook, it is never published (defaults to ../privatehosts.txt, don't change)</li>
<li><b>proxy_port</b> - http port for your eepProxy (no need to change)</li>
<li><b>proxy_host</b> - hostname for your eepProxy (no need to change)</li>
<li><b>should_publish</b> - true/false whether to write the published addressbook</li>
<li><b>etags</b> - file containing the etags header from the fetched subscription URLs (no need to change)</li>
<li><b>last_modified</b> - file containing the modification timestamp for each fetched subscription URL (no need to change)</li>
<li><b>log</b> - file to log activity to (change to /dev/null if you like)</li>
</ul>
</div><hr>
<div id="footer">
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}">susi</a> 2005 </p>
</div>
</div>
</body>
</html>
