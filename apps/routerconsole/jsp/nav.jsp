<% 
   if (request.getParameter("i2p.contextId") != null) {
       session.setAttribute("i2p.contextId", request.getParameter("i2p.contextId")); 
   }%>

<div class="logo">
 <a href="index.jsp"><img src="i2plogo.png" alt="Router Console" width="187" height="35" /></a><br />
 [<a href="config.jsp">configuration</a> | <a href="help.jsp">help</a>]
</div>

<h3>
 <a href="profiles.jsp">Profiles</a> |
 <a href="netdb.jsp">Network Database</a> |
 <a href="logs.jsp">Logs</a> 
 <jsp:useBean class="net.i2p.router.web.NavHelper" id="navhelper" scope="request" />
 <jsp:setProperty name="navhelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="navhelper" property="clientAppLinks" />
</h3>
