<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("network database")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<script type="text/javascript">
  var failMessage = "<hr><b><%=intl._("Router is down")%><\/b>";
  function requestAjax1() { ajax("/xhr1.jsp?requestURI=<%=request.getRequestURI()%>", "xhr", <%=intl.getRefresh()%>000); }
  function initAjax() { setTimeout(requestAjax1, <%=intl.getRefresh()%>000);  }
</script>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1><%=intl._("I2P Network Database")%></h1>
<div class="main" id="main">
 <div class="wideload">
 <jsp:useBean class="net.i2p.router.web.NetDbHelper" id="netdbHelper" scope="request" />
 <jsp:setProperty name="netdbHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<%
    netdbHelper.storeWriter(out);
    if (allowIFrame)
        netdbHelper.allowGraphical();
%>
 <jsp:setProperty name="netdbHelper" property="full" value="<%=request.getParameter(\"f\")%>" />
 <jsp:setProperty name="netdbHelper" property="router" value="<%=request.getParameter(\"r\")%>" />
 <jsp:setProperty name="netdbHelper" property="lease" value="<%=request.getParameter(\"l\")%>" />
 <jsp:getProperty name="netdbHelper" property="netDbSummary" />
</div></div></body></html>
