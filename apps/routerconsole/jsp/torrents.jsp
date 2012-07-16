<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="tester" scope="request" />
<jsp:setProperty name="tester" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<%
    // CSSHelper is also pulled in by css.jsi below...
    boolean testIFrame = tester.allowIFrame(request.getHeader("User-Agent"));
    if (!testIFrame) {
        response.setStatus(302, "Moved");
        response.setHeader("Location", "/i2psnark/");
    } else {
%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("torrents")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<script type="text/javascript">
  var failMessage = "<hr><b><%=intl._("Router is down")%><\/b>";
  function requestAjax1() { ajax("/xhr1.jsp?requestURI=<%=request.getRequestURI()%>", "xhr", <%=intl.getRefresh()%>000); }
  function initAjax() { setTimeout(requestAjax1, <%=intl.getRefresh()%>000);  }
  function injectClass(f) {
      f.className += ' iframed';
      var doc = 'contentDocument' in f? f.contentDocument : f.contentWindow.document;
      doc.body.className += ' iframed';
  }
  function resizeFrame(f) {
      // offsetHeight returns the height of the visible area for an object, in pixels.
      // The value contains the height with the padding, scrollBar, and the border,
      // but does not include the margin. Therefore, any content within the iframe
      // should have no margins at the very top or very bottom to avoid a scrollbar.
      var doc = 'contentDocument' in f? f.contentDocument : f.contentWindow.document;
      var totalHeight = doc.body.offsetHeight;

      // Detect if horizontal scrollbar is present, and add its width to height if so.
      // This prevents a vertical scrollbar appearing when the min-width is passed.
      // FIXME: How to detect horizontal scrollbar in iframe? Always apply for now.
      if (true) {
          // Create the measurement node
          var scrollDiv = document.createElement("div");
          scrollDiv.className = "scrollbar-measure";
          scrollDiv.style.width = "100px";
          scrollDiv.style.height = "100px";
          scrollDiv.style.overflow = "scroll";
          scrollDiv.style.position = "absolute";
          scrollDiv.style.top = "-9999px";
          document.body.appendChild(scrollDiv);

          // Get the scrollbar width
          var scrollbarWidth = scrollDiv.offsetWidth - scrollDiv.clientWidth;
          totalHeight += scrollbarWidth;

          // Delete the div
          document.body.removeChild(scrollDiv);
      }

      f.style.height = totalHeight + "px";
  }
  function setupFrame() {
      f = document.getElementById("i2psnarkframe");
      injectClass(f);
      resizeFrame(f);
  }
</script>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<h1><%=intl._("I2P Torrent Downloader")%> <span class="newtab"><a href="/i2psnark/" target="_blank" title="<%=intl._("Open in new tab")%>"><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/newtab.png" /></a></span></h1>
<div class="main" id="main">
<iframe src="/i2psnark/" width="100%" height="100%" frameborder="0" border="0" name="i2psnarkframe" id="i2psnarkframe" onload="setupFrame()" allowtransparency="true">
</iframe>
</div></body></html>
<%
    }
%>
