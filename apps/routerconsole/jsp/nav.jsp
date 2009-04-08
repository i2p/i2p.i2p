<%@page import="java.io.File" %>
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
</div>
<div class="toolbar">
 <% if (new File("docs/toolbar.html").exists()) { %>
   <jsp:useBean class="net.i2p.router.web.ContentHelper" id="toolbarhelper" scope="request" />
   <jsp:setProperty name="toolbarhelper" property="page" value="docs/toolbar.html" />
   <jsp:setProperty name="toolbarhelper" property="maxLines" value="300" />
   <jsp:getProperty name="toolbarhelper" property="content" />
<% } else { %>
 <!-- Could not find docs/toolbar.html! -->
 <a href="susimail/susimail">Susimail</a> |
 <a href="susidns/index.jsp">SusiDNS</a> |
 <!-- <a href="syndie/">Syndie</a> | -->
 <a href="i2psnark/">I2PSnark</a> |
 <a href="http://127.0.0.1:7658/">My Eepsite</a> <br>
 <a href="i2ptunnel/index.jsp">I2PTunnel</a> |
 <a href="tunnels.jsp">Tunnels</a> |
 <a href="profiles.jsp">Profiles</a> |
 <a href="netdb.jsp">NetDB</a> |
 <a href="logs.jsp">Logs</a> |
 <a href="jobs.jsp">Jobs</a> |
 <a href="graphs.jsp">Graphs</a> |
 <a href="oldstats.jsp">Stats</a> <!-- |
 <a href="oldconsole.jsp">Internals</a> -->
<% } %>
 <jsp:useBean class="net.i2p.router.web.NavHelper" id="navhelper" scope="request" />
 <jsp:setProperty name="navhelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="navhelper" property="clientAppLinks" />
</div>

<jsp:useBean class="net.i2p.router.web.NoticeHelper" id="noticehelper" scope="request" />
<jsp:setProperty name="noticehelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<b><jsp:getProperty name="noticehelper" property="systemNotice" /></b>
