<%@page contentType="text/plain"
%><jsp:useBean id="helper" class="net.i2p.router.web.helpers.StatHelper"
/><jsp:setProperty name="helper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>"
/><jsp:setProperty name="helper" property="peer" value="<%=net.i2p.data.DataHelper.stripHTML(request.getParameter(\"peer\"))%>"
/><% helper.storeWriter(out);
%><jsp:getProperty name="helper" property="profile" />
