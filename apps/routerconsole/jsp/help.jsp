<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - help</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<div class="main" id="main">
hmm.  we should probably have some help text here.<br />

<h2>Legal stuff</h2>
The I2P router (router.jar) and SDK (i2p.jar) are almost entirely public domain, with 
a few notable exceptions:<ul>
<li>ElGamal and DSA code, under the BSD license, written by TheCrypto</li>
<li>SHA256 and HMAC-SHA256, under the MIT license, written by the Legion of the Bouncycastle</li>
<li>AES code, under the Cryptix (MIT) license, written by the Cryptix team</li>
<li>SNTP code, under the BSD license, written by Adam Buckley</li>
<li>The rest is outright public domain, written by jrandom, mihi, hypercubus, oOo, 
    ugha, duck, shendaras, and others.</li>
</ul>

<p>On top of the I2P router are a series of client applications, each with their own set of
licenses and dependencies.  This webpage is being served as part of the I2P routerconsole
client application, which is built off a trimmed down <a href="http://jetty.mortbay.com/jetty/index.html">Jetty</a>
instance (trimmed down, as in, we do not include the demo apps or other add-ons, and we simplify configuration), 
allowing you to deploy standard JSP/Servlet web applications into your router.  Jetty in turn makes use of 
Apache's javax.servlet (javax.servlet.jar) implementation, as well as their xerces-j XML parser (xerces.jar).
Their XML parser requires the Sun XML APIs (JAXP) which is included in binary form (xml-apis.jar) as required 
by their binary code license.  This product includes software developed by the Apache Software Foundation 
(http://www.apache.org/). </p>

<p>Another application you can see on this webpage is <a href="http://www.i2p2.i2p/i2ptunnel">I2PTunnel</a>
(your <a href="i2ptunnel/" target="_blank">web interface</a>) - a GPL'ed application written by mihi that
lets you tunnel normal TCP/IP traffic over I2P (such as the eepproxy and the irc proxy).  There is also a
<a href="http://susi.i2p/">susimail</a> web based mail client <a href="susimail/susimail">available</a> on
the console, which is a GPL'ed application written by susi23.  The addressbook application, written by 
<a href="http://ragnarok.i2p/">Ragnarok</a> helps maintain your hosts.txt files (see ./addressbook/ for
more information).</p>

<p>The router by default also includes human's public domain <a href="http://www.i2p2.i2p/sam">SAM</a> bridge,
which other client applications (such the <a href="http://duck.i2p/i2p-bt/">bittorrent port</a>) can use.  
There is also an optimized library for doing large number calculations - jbigi - which in turn uses the 
LGPL licensed <a href="http://swox.com/gmp/">GMP</a> library, tuned for various PC architectures.  Launchers for windows users are built with <a href="http://launch4j.sourceforge.net/">Launch4J</a>, and the installer is built with <a href="http://www.izforge.com/izpack/">IzPack</a>.  For 
details on other applications available, as well as their licenses, please see the 
<a href="http://www.i2p2.i2p/licenses">license policy</a>.  Source for the I2P code and most bundled
client applications can be found on our <a href="http://www.i2p2.i2p/download">download page</a>.
.</p>

<h2>Release history</h2>
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <jsp:setProperty name="contenthelper" property="page" value="history.txt" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="500" />
 <jsp:setProperty name="contenthelper" property="startAtBeginning" value="true" />
 <jsp:getProperty name="contenthelper" property="textContent" />
 
 <p>
 A more complete list of changes can be found 
 in the history.txt file in your i2p directory.
 </p>
</div>

</body>
</html>
