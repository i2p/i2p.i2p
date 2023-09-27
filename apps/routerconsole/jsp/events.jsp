<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("events")%>
 <jsp:useBean class="net.i2p.router.web.helpers.EventLogHelper" id="eventHelper" scope="request" />
 <jsp:setProperty name="eventHelper" property="contextId" value="<%=i2pcontextId%>" />
<% /* GraphHelper sets the defaults in setContextId, so setting the properties must be after the context */ %>
 <jsp:setProperty name="eventHelper" property="*" />
<%
    eventHelper.storeWriter(out);
    eventHelper.storeMethod(request.getMethod());
%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Event Log")%></h1>
<div class="main" id="events">
 <div class="eventspanel">
 <div class="widepanel">
 <jsp:getProperty name="eventHelper" property="allMessages" />
 <jsp:getProperty name="eventHelper" property="form" />
 <jsp:getProperty name="eventHelper" property="events" />
</div></div></div></body></html>
