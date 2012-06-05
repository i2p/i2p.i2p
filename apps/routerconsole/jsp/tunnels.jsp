<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("tunnel summary")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<script type="text/javascript">
  var failMessage = "<hr><b><%=intl._("Router is down")%><\/b>";
  function requestAjax1() { ajax("/xhr1.jsp", "xhr", <%=intl.getRefresh()%>000); }
  function initAjax() { setTimeout(requestAjax1, <%=intl.getRefresh()%>000);  }
</script>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %><h1><%=intl._("I2P Tunnel Summary")%></h1>
<div class="main" id="main">
 <jsp:useBean class="net.i2p.router.web.TunnelHelper" id="tunnelHelper" scope="request" />
 <jsp:setProperty name="tunnelHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <% tunnelHelper.storeWriter(out); %>
 <jsp:getProperty name="tunnelHelper" property="tunnelSummary" />
</div></body></html>
