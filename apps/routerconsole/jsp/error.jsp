<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
    // Let's make this easy...
    final Integer ERROR_CODE = (Integer) request.getAttribute(org.mortbay.jetty.servlet.ServletHandler.__J_S_ERROR_STATUS_CODE);
    final String ERROR_URI = (String) request.getAttribute(org.mortbay.jetty.servlet.ServletHandler.__J_S_ERROR_REQUEST_URI);
    final String ERROR_MESSAGE = (String) request.getAttribute(org.mortbay.jetty.servlet.ServletHandler.__J_S_ERROR_MESSAGE);
    if (ERROR_CODE != null && ERROR_MESSAGE != null) {
        // this is deprecated but we don't want sendError()
        response.setStatus(ERROR_CODE.intValue(), ERROR_MESSAGE);
    }
    // If it can't find the iframe or viewtheme.jsp I wonder if the whole thing blows up...
%>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Page Not Found")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<script type="text/javascript">
  var failMessage = "<hr><b><%=intl._("Router is down")%><\/b>";
  function requestAjax1() { ajax("/xhr1.jsp", "xhr", <%=intl.getRefresh()%>000); }
  function initAjax() { setTimeout(requestAjax1, <%=intl.getRefresh()%>000);  }
</script>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1><%=ERROR_CODE%> <%=ERROR_MESSAGE%></h1>
<div class="sorry" id="warning">
<%=intl._("Sorry! You appear to be requesting a non-existent Router Console page or resource.")%><hr>
<%=intl._("Error 404")%>: <%=ERROR_URI%> <%=intl._("not found")%>.
</div></body></html>
