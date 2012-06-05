<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
    response.setStatus(404, "Not Found");
%>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("WebApp Not Found")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<script type="text/javascript">
  var failMessage = "<hr><b><%=intl._("Router is down")%><\/b>";
  function requestAjax1() { ajax("/xhr1.jsp", "xhr", <%=intl.getRefresh()%>000); }
  function initAjax() { setTimeout(requestAjax1, <%=intl.getRefresh()%>000);  }
</script>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1><%=intl._("Web Application Not Running")%></h1>
<div class="sorry" id="warning">
<%=intl._("The requested web application is not running.")%>
<%=intl._("Please visit the <a href=\"/configclients.jsp#webapp\">config clients page</a> to start it.")%>
</div></body></html>
