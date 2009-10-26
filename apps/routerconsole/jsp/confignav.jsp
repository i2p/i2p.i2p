<%
    /*
     *  Included ~10 times, keep whitespace to a minimum
     */
%>
<jsp:useBean class="net.i2p.router.web.ConfigNavHelper" id="navHelper" scope="request" />
<jsp:setProperty name="navHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<jsp:setProperty name="navHelper" property="writer" value="<%=out%>" />
<div class="confignav" id="confignav">
<center>
<%
    // moved to java for ease of translation and to avoid 10 copies
    navHelper.renderNavBar(request.getRequestURI());
%>
</center></div>
