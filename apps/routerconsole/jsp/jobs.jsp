<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("job queue")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %><h1><%=intl._t("I2P Router Job Queue")%></h1>
<div class="main" id="jobs">
 <jsp:useBean class="net.i2p.router.web.helpers.JobQueueHelper" id="jobQueueHelper" scope="request" />
 <jsp:setProperty name="jobQueueHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <% jobQueueHelper.storeWriter(out); %>
 <jsp:getProperty name="jobQueueHelper" property="jobQueueSummary" />
</div></body></html>
