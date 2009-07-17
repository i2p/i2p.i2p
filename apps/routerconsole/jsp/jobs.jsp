<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - job queue</title>
<%@include file="css.jsp" %>
</head><body>

<%@include file="summary.jsp" %>
 <h1>I2P Router Job Queue</h1>
<div class="main" id="main">
 <jsp:useBean class="net.i2p.router.web.JobQueueHelper" id="jobQueueHelper" scope="request" />
 <jsp:setProperty name="jobQueueHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:setProperty name="jobQueueHelper" property="writer" value="<%=out%>" />
 <jsp:getProperty name="jobQueueHelper" property="jobQueueSummary" />
</div>

</body>
</html>
