<% response.setHeader("Pragma", "no-cache");
   response.setHeader("Cache-Control","no-cache");
   response.setDateHeader("Expires", 0);
   // the above will b0rk if the servlet engine has already flushed
   // the response prior to including nav.jsp, so nav should be 
   // near the top
   
   if (request.getParameter("i2p.contextId") != null) {
       session.setAttribute("i2p.contextId", request.getParameter("i2p.contextId")); 
   }%>

<div class="logo">
 <a href="index.jsp"><img src="i2plogo.png" alt="Router Console" width="187" height="35" /></a><br />
 [<a href="config.jsp">configuration</a> | <a href="help.jsp">help</a>]
</div>

<h4>
 <a href="susimail/susimail">Susimail</a> |
 <a href="susidns/index.jsp">SusiDNS</a> |
 <a href="syndie/index.jsp">Syndie</a> |
 <a href="i2ptunnel/index.jsp">I2PTunnel</a> |
 <a href="tunnels.jsp">Tunnels</a> |
 <a href="profiles.jsp">Profiles</a> |
 <a href="netdb.jsp">NetDB</a> |
 <a href="logs.jsp">Logs</a> |
 <a href="oldstats.jsp">Stats</a> |
 <a href="oldconsole.jsp">Internals</a>
 <jsp:useBean class="net.i2p.router.web.NavHelper" id="navhelper" scope="request" />
 <jsp:setProperty name="navhelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="navhelper" property="clientAppLinks" />
</h4>

<jsp:useBean class="net.i2p.router.web.NoticeHelper" id="noticehelper" scope="request" />
<jsp:setProperty name="noticehelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<b><jsp:getProperty name="noticehelper" property="systemNotice" /></b>
