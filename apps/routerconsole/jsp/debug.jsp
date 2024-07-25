<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<%
  /*
   *   Do not tag this file for translation.
   */
%>
<html><head><title>I2P Router Console - Debug</title>
<%@include file="css.jsi" %>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<h1>Router Debug</h1>
<div class="main" id="debug">

<div class="confignav">
<span class="tab"><a href="/debug">Port Mapper</a></span>
<span class="tab"><a href="/debug?d=1">App Manager</a></span>
<span class="tab"><a href="/debug?d=2">Update Manager</a></span>
<span class="tab"><a href="/debug?d=3">Router Session Key Manager</a></span>
<span class="tab"><a href="/debug?d=4">Client Session Key Managers</a></span>
<span class="tab"><a href="/debug?d=5">Router DHT</a></span>
<span class="tab"><a href="/debug?d=6">Translation Status</a></span>
</div>

<%
    /*
     *  Quick and easy place to put debugging stuff
     */
    net.i2p.router.RouterContext ctx = (net.i2p.router.RouterContext) net.i2p.I2PAppContext.getGlobalContext();

String dd = request.getParameter("d");
if (dd == null || dd.equals("0")) {

    /*
     *  Print out the status for the PortMapper
     */
    ctx.portMapper().renderStatusHTML(out);

    /*
     *  Print out the status for the InternalServerSockets
     */
    net.i2p.util.InternalServerSocket.renderStatusHTML(out);

} else if (dd.equals("1")) {

    /*
     *  Print out the status for the AppManager
     */

    out.print("<div class=\"debug_section\" id=\"appmanager\">");
    ctx.routerAppManager().renderStatusHTML(out);
            out.print("</div>");

} else if (dd.equals("2")) {

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

} else if (dd.equals("3")) {

    /*
     *  Print out the status for all the SessionKeyManagers
     */
    out.print("<div class=\"debug_section\" id=\"skm\">");
    out.print("<h2>Router Session Key Manager</h2>");
    ctx.sessionKeyManager().renderStatusHTML(out);
    out.print("</div>");

} else if (dd.equals("4")) {

    out.print("<h2>Client Session Key Managers</h2>");
    java.util.Set<net.i2p.data.Destination> clients = ctx.clientManager().listClients();
    java.util.Set<net.i2p.crypto.SessionKeyManager> skms = new java.util.HashSet<net.i2p.crypto.SessionKeyManager>(clients.size());
    int i = 0;
    for (net.i2p.data.Destination dest : clients) {
        net.i2p.data.Hash h = dest.calculateHash();
        net.i2p.crypto.SessionKeyManager skm = ctx.clientManager().getClientSessionKeyManager(h);
        if (skm != null) {
            out.print("<div class=\"debug_section\" id=\"cskm" + (i++) + "\"><h2>");
            net.i2p.router.TunnelPoolSettings tps = ctx.tunnelManager().getInboundSettings(h);
            if (tps != null) {
                String nick = tps.getDestinationNickname();
                if (nick != null)
                    out.print(net.i2p.data.DataHelper.escapeHTML(nick));
                else
                    out.print("<font size=\"-2\">" + dest.toBase32() + "</font>");
            } else {
                out.print("<font size=\"-2\">" + dest.toBase32() + "</font>");
            }
            out.print(" Session Key Manager</h2>");
            if (skms.add(skm))
                skm.renderStatusHTML(out);
            else
                out.print("<p>See Session Key Manager for alternate destination above</p>");
            out.print("</div>");
        }
    }
} else if (dd.equals("5")) {

    /*
     *  Print out the status for the NetDB
     */
    out.print("<h2 id=\"dht\">Router DHT</h2>");
    ctx.netDb().renderStatusHTML(out);

} else if (dd.equals("6")) {

    /*
     *  Print out the status of the translations
     */
    java.io.InputStream is = this.getClass().getResourceAsStream("/net/i2p/router/web/resources/translationstatus.html");
    if (is == null) {
        out.println("Translation status not available");
    } else {
        java.io.Reader br = null;
        try {
            br = new java.io.InputStreamReader(is, "UTF-8");
            char[] buf = new char[4096];
            int read;
            while ( (read = br.read(buf)) >= 0) {
                out.write(buf, 0, read);
            }
        } finally {
            if (br != null) try { br.close(); } catch (java.io.IOException ioe) {}
        }
    }
}

%>
</div></body></html>
