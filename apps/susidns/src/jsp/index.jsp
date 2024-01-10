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
    response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; form-action 'self'; frame-ancestors 'self'; media-src 'none'");
    response.setHeader("X-XSS-Protection", "1; mode=block");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Accept-Ranges", "none");

%>
<%@page pageEncoding="UTF-8"%>
<%@ page contentType="text/html"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="base" class="i2p.susi.dns.BaseBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<!DOCTYPE html>
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title><%=intl._t("Introduction")%> - SusiDNS</title>
<link rel="stylesheet" type="text/css" href="<%=base.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<script src="/js/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<script src="js/messages.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
</head>
<body>
<div class="page">
<hr>
<div id="navi">
<a id="overview" class="active" href="index"><%=intl._t("Overview")%></a>&nbsp;
<a class="abook" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook" href="addressbook?book=local&amp;filter=none"><%=intl._t("Local")%></a>&nbsp;
<a class="abook" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id="subs" href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id="config" href="config"><%=intl._t("Configuration")%></a>
</div>
<hr>
<div id="content">
<h3 id="whatitis"><%=intl._t("What is the address book?")%></h3>
<p class="whatitis">
<%=intl._t("The address book application is part of your I2P installation.")%>
<%=intl._t("You can use it to connect human-readable names, like i2p-projekt.i2p, to I2P Destinations.")%>
</p><p class="whatitis">
<%=intl._t("It regularly updates your hosts.txt file from distributed sources or \"subscriptions\".")%>
<%=intl._t("In the default configuration, the address book is only subscribed to {0}.", "i2p-projekt.i2p")%>
<%=intl._t("Subscribing to additional sites is easy, just add them to your <a href=\"subscriptions\">subscriptions</a>.")%>
</p>
<p class="whatitis">
<%=intl._t("For more information on naming in I2P, see <a href=\"http://i2p-projekt.i2p/naming.html\" target=\"_blank\">the overview</a>.")%>
</p>
<h3 id="howtouse"><%=intl._t("How to use the Address Book?")%></h3>
<p class="howtouse">
<%=intl._t("The I2P Address Book allows you to manage addresses by sorting them into categories.")%>
<%=intl._t("You may use these categories according to how you intend to use the address.")%>
</p>
<ul class="howtouse">
<li><%=intl._t("Router: These addresses are added automatically, by your subscriptions. If you publish an address book, the router address book will be shared with other I2P users.")%></li>
<li><%=intl._t("Local: This is your personal address book, for hosts which you publish and share with others.")%></li>
<li><%=intl._t("Private: This address book if used for addresses which you do not want to share with other I2P users.")%></li>
</ul>
<h3 id="howitworks"><%=intl._t("How does the address book application work?")%></h3>
<p class="howitworks">
<%=intl._t("The address book application regularly polls your subscriptions and merges their content into your \"router\" address book.")%>
<%=intl._t("Then it merges your \"local\" address book into the router address book as well.")%>
<%=intl._t("If configured, the router address book is now written to the \"published\" address book, which will be publicly available if you are running an I2P Site.")%>
</p><p class="howitworks">
<%=intl._t("The router also uses a private address book, which is not merged or published.")%>
<%=intl._t("Hosts in the private address book can be accessed by you but their addresses are never distributed to others.")%>
<%=intl._t("The private address book can also be used for aliases of hosts in your other address books.")%>
</p>
<div class="illustrate howitworks">
<object type="image/svg+xml" data="images/how.svg?<%=net.i2p.CoreVersion.VERSION%>">
<img src="themes/images/how.png" border="0" alt="address book working scheme" title="How the address book works" class="illustrate" />
</object>
</div>
</div>
<div id="footer">
<hr>
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}" target="_blank">susi</a> 2005</p>
</div>
</div>
</body>
</html>
