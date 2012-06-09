<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
  /*
   *   Do not tag this file for translation.
   */
%>
<html><head><title>I2P Router Console - Debug</title>
<%@include file="css.jsi" %>
<script src="/js/ajax.js" type="text/javascript"></script>
<script type="text/javascript">
  var failMessage = "<hr><b><%=intl._("Router is down")%><\/b>";
  function requestAjax1() { ajax("/xhr1.jsp", "xhr", <%=intl.getRefresh()%>000); }
  function initAjax() { setTimeout(requestAjax1, <%=intl.getRefresh()%>000);  }
</script>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1>Router SKM</h1>
<div class="main" id="main">
<%
    /*
     *  Quick and easy place to put debugging stuff
     */
    net.i2p.router.RouterContext ctx = (net.i2p.router.RouterContext) net.i2p.I2PAppContext.getGlobalContext();

    /*
     *  Print out the status for all the SessionKeyManagers
     */

    ctx.sessionKeyManager().renderStatusHTML(out);
    java.util.Set<net.i2p.data.Destination> clients = ctx.clientManager().listClients();
    for (net.i2p.data.Destination dest : clients) {
        net.i2p.data.Hash h = dest.calculateHash();
        net.i2p.crypto.SessionKeyManager skm = ctx.clientManager().getClientSessionKeyManager(h);
        if (skm != null) {
            out.print("<h2>" + h.toBase64().substring(0,6) + " SKM</h2>");
            skm.renderStatusHTML(out);
        }
    }
%>
</div></body></html>
