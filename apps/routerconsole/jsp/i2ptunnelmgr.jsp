<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="tester" scope="request" />
<%
   String i2pcontextId1 = null;
   try {
       i2pcontextId1 = (String) session.getAttribute("i2p.contextId");
   } catch (IllegalStateException ise) {}
%>
<jsp:setProperty name="tester" property="contextId" value="<%=i2pcontextId1%>" />
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
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Hidden Services Manager")%>
<script src="/js/iframed.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">
/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

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
      f.addEventListener("load", function() {
          injectClass(f);
          injectClassSpecific(f);
          resizeFrame(f);
      }, true);
  }

/* @license-end */
</script>
</head><body>

<%@include file="summary.jsi" %>

<h1><%=intl._t("Hidden Services Manager")%> <span class="newtab"><a href="/i2ptunnel/" target="_blank" title="<%=intl._t("Open in new tab")%>"><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/newtab.png" /></a></span></h1>
<div class="main" id="tunnelmgr">
<iframe src="/i2ptunnel/" width="100%" height="100%" frameborder="0" border="0" name="i2ptunnelframe" id="i2ptunnelframe" allowtransparency="true">
<%=intl._t("Your browser does not support iFrames.")%>
&nbsp;<a href="/i2ptunnel/"><%=intl._t("Click here to continue.")%></a>
</iframe>
</div></body></html>
<%
    }
%>
