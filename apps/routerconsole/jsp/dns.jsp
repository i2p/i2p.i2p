<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="tester" scope="request" />
<jsp:setProperty name="tester" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<%
    // CSSHelper is also pulled in by css.jsi below...
    boolean testIFrame = tester.allowIFrame(request.getHeader("User-Agent"));
    if (!testIFrame) {
        response.setStatus(307);
        response.setHeader("Location", "/susidns/index");
        // force commitment
        response.getOutputStream().close();
        return;
    } else {
%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("addressbook")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<script src="/js/iframed.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
<script type="text/javascript">
  function setupFrame() {
      f = document.getElementById("susidnsframe");
      injectClass(f);
      resizeFrame(f);
  }
</script>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<h1><%=intl._t("I2P Addressbook")%> <span class="newtab"><a href="/susidns/index" target="_blank" title="<%=intl._t("Open in new tab")%>"><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/newtab.png" /></a></span></h1>
<div class="main" id="dns">
<iframe src="/susidns/index" width="100%" height="100%" frameborder="0" border="0" name="susidnsframe" id="susidnsframe" onload="setupFrame()" allowtransparency="true">
<%=intl._t("Your browser does not support iFrames.")%>
&nbsp;<a href="/susidns/index"><%=intl._t("Click here to continue.")%></a>
</iframe>
</div></body></html>
<%
    }
%>
