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
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1>Router Debug</h1>
<div class="main" id="debug">

<div class="confignav">
<span class="tab"><a href="#debug_portmapper">Port Mapper</a></span>
<span class="tab"><a href="#appmanager">App Manager</a></span>
<span class="tab"><a href="#updatemanager">Update Manager</a></span>
<span class="tab"><a href="#skm">Session Key Manager</a></span>
<span class="tab"><a href="#dht">Router DHT</a></span>
</div>

<%
    /*
     *  Quick and easy place to put debugging stuff
     */
    net.i2p.router.RouterContext ctx = (net.i2p.router.RouterContext) net.i2p.I2PAppContext.getGlobalContext();

    /*
     *  Print out the status for the PortMapper
     */
    ctx.portMapper().renderStatusHTML(out);

    /*
     *  Print out the status for the InternalServerSockets
     */
    net.i2p.util.InternalServerSocket.renderStatusHTML(out);

    /*
     *  Print out the status for the AppManager
     */

    out.print("<div class=\"debug_section\" id=\"appmanager\">");
    ctx.routerAppManager().renderStatusHTML(out);
            out.print("</div>");


    /*
     *  Print out the status for the UpdateManager
     */
    out.print("<div class=\"debug_section\" id=\"updatemanager\">");
    net.i2p.app.ClientAppManager cmgr = ctx.clientAppManager();
    if (cmgr != null) {
        net.i2p.router.update.ConsoleUpdateManager umgr =
            (net.i2p.router.update.ConsoleUpdateManager) cmgr.getRegisteredApp(net.i2p.update.UpdateManager.APP_NAME);
        if (umgr != null) {
            umgr.renderStatusHTML(out);
        }
    out.print("</div>");
    }

    /*
     *  Print out the status for all the SessionKeyManagers
     */
    out.print("<div class=\"debug_section\" id=\"skm\">");
    out.print("<h2>Router Session Key Manager</h2>");
    ctx.sessionKeyManager().renderStatusHTML(out);
    java.util.Set<net.i2p.data.Destination> clients = ctx.clientManager().listClients();
    out.print("</div>");
    for (net.i2p.data.Destination dest : clients) {
        net.i2p.data.Hash h = dest.calculateHash();
        net.i2p.crypto.SessionKeyManager skm = ctx.clientManager().getClientSessionKeyManager(h);
        if (skm != null) {
            out.print("<div class=\"debug_section\">");
            out.print("<h2><font size=\"-2\">" + dest.toBase32() + "</font> Session Key Manager</h2>");
            skm.renderStatusHTML(out);
            out.print("</div>");
        }
    }

    /*
     *  Print out the status for the NetDB
     */
    out.print("<h2 id=\"dht\">Router DHT</h2>");
    ctx.netDb().renderStatusHTML(out);

%>
</div></body></html>
