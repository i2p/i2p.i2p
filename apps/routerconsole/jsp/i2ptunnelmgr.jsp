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
        response.setHeader("Location", "/i2ptunnel/");
        // force commitment
        response.getOutputStream().close();
        return;
    } else {
%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Hidden Services Manager")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<script src="/js/iframed.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
<script type="text/javascript">
  function injectClassSpecific(f) {
      var doc = 'contentDocument' in f? f.contentDocument : f.contentWindow.document;
      if (doc.getElementsByClassName == undefined) {
      doc.getElementsByClassName = function(className)
      {
          var hasClassName = new RegExp("(?:^|\\s)" + className + "(?:$|\\s)");
          var allElements = document.getElementsByTagName("*");
          var results = [];

          var element;
          for (var i = 0; (element = allElements[i]) != null; i++) {
              var elementClass = element.className;
              if (elementClass && elementClass.indexOf(className) != -1 && hasClassName.test(elementClass))
                  results.push(element);
          }

          return results;
      }
      }
      doc.getElementsByClassName('panel')[0].className += ' iframed';
  }
  function setupFrame() {
      f = document.getElementById("i2ptunnelframe");
      injectClass(f);
      injectClassSpecific(f);
      resizeFrame(f);
  }
</script>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<h1><%=intl._t("Hidden Services Manager")%> <span class="newtab"><a href="/i2ptunnel/" target="_blank" title="<%=intl._t("Open in new tab")%>"><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/newtab.png" /></a></span></h1>
<div class="main" id="tunnelmgr">
<iframe src="/i2ptunnel/" width="100%" height="100%" frameborder="0" border="0" name="i2ptunnelframe" id="i2ptunnelframe" onload="setupFrame()" allowtransparency="true">
<%=intl._t("Your browser does not support iFrames.")%>
&nbsp;<a href="/i2ptunnel/"><%=intl._t("Click here to continue.")%></a>
</iframe>
</div></body></html>
<%
    }
%>
