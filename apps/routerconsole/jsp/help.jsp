<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
  /*
   *   Do not tag this file for translation - copy it to help_xx.jsp and translate inline.
   */
%>
<html><head><title>I2P Router Console - help</title>
<%@include file="css.jsi" %>
<script src="/js/ajax.js" type="text/javascript"></script>
<script type="text/javascript">
  var failMessage = "<hr><b><%=intl._("Router is down")%><\/b>";
  function requestAjax1() { ajax("/xhr1.jsp", "xhr", <%=intl.getRefresh()%>000); }
  function initAjax() { setTimeout(requestAjax1, <%=intl.getRefresh()%>000);  }
</script>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1>I2P Router Help &amp; Support</h1>
<div class="main" id="main"><p>
If you'd like to help improve or translate the documentation, or
help with other aspects of the project, please see the documentation for
<a href="http://www.i2p2.i2p/getinvolved.html">volunteers.</a>
</p><p>Further assistance is available here:</p>
<ul class="links">
<li class="tidylist"><a href="http://www.i2p2.i2p/faq.html">FAQ on www.i2p2.i2p</a></li>
<li class="tidylist"><a href="http://www.i2p2.i2p/faq_de.html">Deutsch FAQ</a>.</li></ul>
<br>You may also try the <a href="http://forum.i2p/">I2P forum</a>
or IRC.

<h2>Summary Bar Information</h2><p>
Many of the stats on the summary bar may be
<a href="configstats.jsp">configured</a> to be
<a href="graphs.jsp">graphed</a> for further analysis.
</p><h3>General</h3><ul>
<li class="tidylist"><b>Local Identity:</b>
The first four characters (24 bits) of your 44-character (256-bit) Base64 router hash.
The full hash is shown on your <a href="netdb.jsp?r=.">router info page</a>.
Never reveal this to anyone, as your router info contains your IP.</li>
<li class="tidylist"><b>Version:</b>
The version of the I2P software you are running.</li>
<%
/* <li class="tidylist"><b>Now:</b>
The current time (UTC) and the skew, if any. I2P requires your computer's time be accurate.
If the skew is more than a few seconds, please correct the problem by adjusting
your computer's time.</li> */
%>
<li class="tidylist"><b>Reachability:</b>
The router's view of whether it can be contacted by other routers.
Further information is on the <a href="confignet#help">configuration page</a>.
</li></ul><h3>Peers</h3><ul>
<li class="tidylist"><b>Active:</b>
The first number is the number of peers you've sent or received a message from in the last few minutes.
This may range from 8-10 to several hundred, depending on your total bandwidth,
shared bandwidth, and locally-generated traffic.
The second number is the number of peers seen in the last hour or so.
Do not be concerned if these numbers vary widely.
<a href="configstats.jsp#router.activePeers">[Enable graphing]</a>.</li>
<li class="tidylist"><b>Fast:</b>
This is the number of peers you use for building client tunnels. It is generally in the
range 8-30. Your fast peers are shown on the <a href="profiles.jsp">profiles page</a>.
<a href="configstats.jsp#router.fastPeers">[Enable graphing]</a></li>
<li class="tidylist"><b>High Capacity:</b>
This is the number of peers you use for building some of your exploratory tunnels. It is generally in the
range 8-75. The fast peers are included in the high capacity tier.
Your high capacity peers are shown on the <a href="profiles.jsp">profiles page</a>.
<a href="configstats.jsp#router.highCapacityPeers">[Enable graphing]</a></li>
<li class="tidylist"><b>Well Integrated:</b>
This is the number of peers you use for network database inquiries.
These are usually the "floodfill" peers.
Your well integrated peers are shown on the bottom of the <a href="profiles.jsp">profiles page</a>.</li>
<li class="tidylist"><b>Known:</b>
This is the total number of routers you know about.
They are listed on the <a href="netdb.jsp">network database page</a>.
This may range from under 100 to 1000 or more.
This number is not the total size of the network;
it may vary widely depending on your total bandwidth,
shared bandwidth, and locally-generated traffic.
I2P does not require a router to know every other router.</li>
</ul><h3>Bandwidth in/out</h3><div align="justify">
Should be self-explanatory. All values are in bytes per second, not bits per second.
Change your bandwidth limits on the <a href="confignet#help">configuration page</a>.
Bandwidth is <a href="graphs.jsp">graphed</a> by default.</div>

<h3>Local destinations</h3><div align="justify">
The local applications connecting through your router.
These may be clients started through <a href="i2ptunnel/index.jsp">I2PTunnel</a>
or external programs connecting through SAM, BOB, or directly to I2CP.
</div><h3>Tunnels in/out</h3><div align="justify">
The actual tunnels are shown on the <a href="tunnels.jsp">the tunnels page</a>.</div><ul>
<li class="tidylist"><div align="justify"><b>Exploratory:</b>
Tunnels built by your router and used for communication with the floodfill peers,
building new tunnels, and testing existing tunnels.</div></li>
<li class="tidylist"><b>Client:</b>
Tunnels built by your router for each client's use.</li>
<li class="tidylist"><b>Participating:</b>
Tunnels built by other routers through your router.
This may vary widely depending on network demand, your
shared bandwidth, and amount of locally-generated traffic.
The recommended method for limiting participating tunnels is
to change your share percentage on the <a href="confignet#help">configuration page</a>.
You may also limit the total number by setting <tt>router.maxParticipatingTunnels=nnn</tt> on
the <a href="configadvanced.jsp">advanced configuration page</a>. <a href="configstats.jsp#tunnel.participatingTunnels">[Enable graphing]</a>.</li>
<li class="tidylist"><b>Share ratio:</b>
The number of participating tunnels you route for others, divided by the total number of hops in
all your exploratory and client tunnels.
A number greater than 1.00 means you are contributing more tunnels to the network than you are using.</li>
</ul>

<h3>Congestion</h3><div align="justify">
Some basic indications of router overload:</div><ul>
<li class="tidylist"><b>Job lag:</b>
How long jobs are waiting before execution. The job queue is listed on the <a href="jobs.jsp">jobs page</a>.
Unfortunately, there are several other job queues in the router that may be congested,
and their status is not available in the router console.
The job lag should generally be zero.
If it is consistently higher than 500ms, your computer is very slow, or the
router has serious problems.
<a href="configstats.jsp#jobQueue.jobLag">[Enable graphing]</a>.</li>
<li class="tidylist"><b>Message delay:</b>
How long an outbound message waits in the queue.
This should generally be a few hundred milliseconds or less.
If it is consistently higher than 1000ms, your computer is very slow,
or you should adjust your bandwidth limits, or your (bittorrent?) clients
may be sending too much data and should have their transmit bandwidth limit reduced.
<a href="configstats.jsp#transport.sendProcessingTime">[Enable graphing]</a> (transport.sendProcessingTime).</li>
<li class="tidylist"><b>Tunnel lag:</b>
This is the round trip time for a tunnel test, which sends a single message
out a client tunnel and in an exploratory tunnel, or vice versa.
It should usually be less than 5 seconds.
If it is consistently higher than that, your computer is very slow,
or you should adjust your bandwidth limits, or there are network problems.
<a href="configstats.jsp#tunnel.testSuccessTime">[Enable graphing]</a> (tunnel.testSuccessTime).</li>
<li class="tidylist"><b>Handle backlog:</b>
This is the number of pending requests from other routers to build a
participating tunnel through your router.
It should usually be close to zero.
If it is consistently high, your computer is too slow,
and you should reduce your share bandwidth limits.</li>
<li class="tidylist"><b>Accepting/Rejecting:</b>
Your router's status on accepting or rejecting
requests from other routers to build a
participating tunnel through your router.
Your router may accept all requests, accept or reject a percentage of requests,
or reject all requests for a number of reasons, to control
the bandwidth and CPU demands and maintain capacity for
local clients.</li></ul>

<h2>Legal stuff</h2><p>The I2P router (router.jar) and SDK (i2p.jar) are almost entirely public domain, with
a few notable exceptions:</p><ul>
<li class="tidylist">ElGamal and DSA code, under the BSD license, written by TheCrypto</li>
<li class="tidylist">SHA256 and HMAC-SHA256, under the MIT license, written by the Legion of the Bouncycastle</li>
<li class="tidylist">AES code, under the Cryptix (MIT) license, written by the Cryptix team</li>
<li class="tidylist">SNTP code, under the BSD license, written by Adam Buckley</li>
<li class="tidylist">The rest is outright public domain, written by jrandom, mihi, hypercubus, oOo,
    ugha, duck, shendaras, and others.</li>
</ul>

<p>On top of the I2P router are a series of client applications, each with their own set of
licenses and dependencies.  This webpage is being served as part of the I2P routerconsole
client application, which is built off a trimmed down <a href="http://jetty.mortbay.com/jetty/index.html">Jetty</a>
instance (trimmed down, as in, we do not include the demo apps or other add-ons, and we simplify configuration),
allowing you to deploy standard JSP/Servlet web applications into your router.  Jetty in turn makes use of
Apache's javax.servlet (javax.servlet.jar) implementation.
This product includes software developed by the Apache Software Foundation
(http://www.apache.org/).</p>

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
LGPL licensed <a href="http://swox.com/gmp/">GMP</a> library, tuned for various PC architectures.  Launchers for windows users 
are built with <a href="http://launch4j.sourceforge.net/">Launch4J</a>, and the installer is built with 
<a href="http://www.izforge.com/izpack/">IzPack</a>.  For
details on other applications available, as well as their licenses, please see the
<a href="http://www.i2p2.i2p/licenses">license policy</a>.  Source for the I2P code and most bundled
client applications can be found on our <a href="http://www.i2p2.i2p/download">download page</a>.
.</p>

<h2>Change Log</h2>
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <% java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "history.txt"); %>
 <jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="256" />
 <jsp:setProperty name="contenthelper" property="startAtBeginning" value="true" />
 <jsp:getProperty name="contenthelper" property="textContent" />

 <p><a href="/history.txt">View the full change log</a>
 </p><hr></div></body></html>
