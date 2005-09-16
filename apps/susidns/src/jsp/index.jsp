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
<%@ page contentType="text/html"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<title>introduction - susidns v${version.version}</title>
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

<div id="content">
<h3>Huh? what addressbook?</h3>
<p>
The addressbook application is part of your i2p installation. It regularly updates your hosts.txt file
from distributed sources. It keeps your hosts.txt up to date, so it automatically contains all new
eepsites announced on <a href="http://orion.i2p">orion</a>
or in the <a href="http://forum.i2p/viewforum.php?f=16">forum</a>.
</p>
<p>
(To speak the truth: In its default configuration the addressbook does not poll
orion, but dev.i2p only. Subscribing to <a href="http://orion.i2p">orion</a> is an easy task,
just add <a href="http://orion.i2p/hosts.txt">http://orion.i2p/hosts.txt</a> to your <a href="subscriptions.jsp">subscriptions</a> file.)
</p>
<p>If you have questions about naming in i2p, there is an excellent <a href="http://forum.i2p.net/viewtopic.php?t=134">introduction</a>
from duck in the forum.</p>
<h3>How does the addressbook work?</h3>
<p>The addressbook application regularly (normally once per hour) polls your subscriptions and merges their content
into your so called router addressbook (normally your plain hosts.txt). Then it merges your so called master addressbook (normally
your userhosts.txt) into the router addressbook as well. If configured the router addressbook is now written to the so published addressbook, 
which is a publicly available copy of your hosts.txt somewhere in your eepsites document root. (Yes, this means that, with activated publication,
your once private keys from userhosts.txt now are publicly available for everybody.)
</p>
<p><img src="images/how.png" border="0" alt="addressbook working scheme"/></p>
</div>

<div id="footer">
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}">susi</a> 2005</p>
</div>
</body>
</html>
