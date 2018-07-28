<%@page contentType="text/plain"
%><jsp:useBean id="helper" class="net.i2p.router.web.helpers.StatHelper"
/><%
   String i2pcontextId = null;
   try {
       i2pcontextId = (String) session.getAttribute("i2pcontextId");
   } catch (IllegalStateException ise) {}
%><jsp:setProperty name="helper" property="contextId" value="<%=i2pcontextId%>"
/><jsp:setProperty name="helper" property="peer" value="<%=net.i2p.data.DataHelper.stripHTML(request.getParameter(\"peer\"))%>"
/><% helper.storeWriter(out);
%><jsp:getProperty name="helper" property="profile" />
