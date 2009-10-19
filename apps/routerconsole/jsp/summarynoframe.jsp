<%@page import="net.i2p.router.web.SummaryHelper" %>
<%
/*
 * Note:
 * This is included almost 30 times, so keep whitespace etc. to a minimum.
 */
%>
<jsp:useBean class="net.i2p.router.web.SummaryHelper" id="helper" scope="request" />
<jsp:setProperty name="helper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<jsp:setProperty name="helper" property="action" value="<%=request.getParameter("action")%>" />
<jsp:setProperty name="helper" property="updateNonce" value="<%=request.getParameter("updateNonce")%>" />
<jsp:setProperty name="helper" property="consoleNonce" value="<%=request.getParameter("consoleNonce")%>" />
<jsp:setProperty name="helper" property="requestURI" value="<%=request.getRequestURI()%>" />
<jsp:setProperty name="helper" property="writer" value="<%=out%>" />
<%
    // moved to java for ease of translation and to avoid 30 copies
    helper.renderSummaryBar();
%>
