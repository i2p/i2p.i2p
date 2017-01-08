<%@page contentType="text/html" import="net.i2p.i2ptunnel.web.EditBean"
%><%@page trimDirectiveWhitespaces="true"
%><?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.web.Messages" id="intl" scope="request" />
<% String tun = request.getParameter("tunnel");
   int curTunnel = -1;
   if (tun != null) {
     try {
       curTunnel = Integer.parseInt(tun);
     } catch (NumberFormatException nfe) {
       curTunnel = -1;
     }
   }
%>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
<head>
    <title><%=intl._t("Hidden Services Manager")%> - <%=intl._t("Edit Hidden Service")%></title>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=UTF-8" />
    <link href="/themes/console/images/favicon.ico" type="image/x-icon" rel="shortcut icon" />

    <% if (editBean.allowCSS()) {
  %><link rel="icon" href="<%=editBean.getTheme()%>images/favicon.ico" />
    <link href="<%=editBean.getTheme()%>i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css" /> 
    <% }
  %>
<style type='text/css'>
input.default { width: 1px; height: 1px; visibility: hidden; }
</style>
</head>
<body id="tunnelEditPage">

<%

  if (editBean.isInitialized()) {

%>
    <form method="post" action="list">

<div class="panel">

                <%
                String tunnelTypeName;
                String tunnelType;
                if (curTunnel >= 0) {
                    tunnelTypeName = editBean.getTunnelType(curTunnel);
                    tunnelType = editBean.getInternalType(curTunnel);
                  %><h2><%=intl._t("Edit Server Settings")%> (<%=editBean.getTunnelName(curTunnel)%>)</h2><% 
                } else {
                    tunnelTypeName = editBean.getTypeName(request.getParameter("type"));
                    tunnelType = net.i2p.data.DataHelper.stripHTML(request.getParameter("type"));
                  %><h2><%=intl._t("New Server Settings")%></h2><% 
                } %>
                <input type="hidden" name="tunnel" value="<%=curTunnel%>" />
                <input type="hidden" name="nonce" value="<%=net.i2p.i2ptunnel.web.IndexBean.getNextNonce()%>" />
                <input type="hidden" name="type" value="<%=tunnelType%>" />
                <%
                // these are four keys that are generated automatically on first save,
                // and we want to persist in i2ptunnel.config, but don't want to
                // show clogging up the custom options form.
                String key = editBean.getKey1(curTunnel);
                if (key != null && key.length() > 0) { %>
                    <input type="hidden" name="key1" value="<%=key%>" />
                <% }
                key = editBean.getKey2(curTunnel);
                if (key != null && key.length() > 0) { %>
                    <input type="hidden" name="key2" value="<%=key%>" />
                <% }
                key = editBean.getKey3(curTunnel);
                if (key != null && key.length() > 0) { %>
                    <input type="hidden" name="key3" value="<%=key%>" />
                <% }
                key = editBean.getKey4(curTunnel);
                if (key != null && key.length() > 0) { %>
                    <input type="hidden" name="key4" value="<%=key%>" />
                <% } %>
                <input type="submit" class="default" name="action" value="Save changes" />

    <table id="serverTunnelEdit" class="tunnelConfig">
        <tr>
            <th>
                <%=intl._t("Name")%>
            </th>
            <th>
                <%=intl._t("Type")%>
            </th>
        </tr>

        <tr>
            <td>
                <input type="text" size="30" maxlength="50" name="name" title="Tunnel Name" value="<%=editBean.getTunnelName(curTunnel)%>" class="freetext tunnelName" />
            </td>
            <td>
                <%=tunnelTypeName%>
            </td>
        </tr>

        <tr>
            <th>
                <%=intl._t("Description")%>
            </th>

            <th>
                <%=intl._t("Auto Start Tunnel")%>
            </th>
        </tr>

        <tr>
            <td>
                <input type="text" size="60" maxlength="80" name="nofilter_description"  title="Tunnel Description" value="<%=editBean.getTunnelDescription(curTunnel)%>" class="freetext tunnelDescription" />                
            </td>

            <td>
                <input value="1" type="checkbox" name="startOnLoad" title="Start Tunnel Automatically"<%=(editBean.startAutomatically(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />
                <%=intl._t("Automatically start tunnel when router starts")%>
            </td>
        </tr>

        <tr>
            <th colspan="2">
         <% if ("streamrserver".equals(tunnelType)) { %>
                <%=intl._t("Access Point")%>
         <% } else { %>
                <%=intl._t("Target")%>
         <% } %>
            </th>
        </tr>

        <tr>
         <% if (!"streamrserver".equals(tunnelType)) { %>
            <td>
                <b><%=intl._t("Host")%>:</b>
                <input type="text" size="20" name="targetHost" title="Target Hostname or IP" value="<%=editBean.getTargetHost(curTunnel)%>" class="freetext host" />
            </td>
         <% } /* !streamrserver */ %>

            <td>
                <b><%=intl._t("Port")%>:</b>
                    <% String value = editBean.getTargetPort(curTunnel);
                       if (value == null || "".equals(value.trim())) {
                           out.write(" <span class=\"required\"><font color=\"red\">(");
                           out.write(intl._t("required"));
                           out.write(")</font></span>");
                       }   
                     %>
                <input type="text" size="6" maxlength="5" id="targetPort" name="targetPort" title="Target Port Number" value="<%=editBean.getTargetPort(curTunnel)%>" class="freetext port" placeholder="required" />
            </td>

         <% if (!"streamrserver".equals(tunnelType)) { %>
        </tr>

        <tr>
            <td colspan="2">
                <input value="1" type="checkbox" name="useSSL" title="Use SSL to connect to target" <%=(editBean.isSSLEnabled(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />
                <%=intl._t("Use SSL to connect to target")%>
         <% } /* !streamrserver */ %>
            </td>
         <% if ("httpbidirserver".equals(tunnelType)) { %>
        </tr>

        <tr>
            <th colspan="2">
                <%=intl._t("Access Point")%>
            </th>
        </tr>

        <tr>
            <td>
                <b><%=intl._t("Port")%>:</b>
           	    
           	     <% String value4 = editBean.getClientPort(curTunnel);
           	        if (value4 == null || "".equals(value4.trim())) {
           	            out.write(" <span class=\"required\"><font color=\"red\">(");
           	            out.write(intl._t("required"));
           	            out.write(")</font></span>");
           	        }
               	      %>

                 <input type="text" size="6" maxlength="5" name="port" title="Access Port Number" value="<%=editBean.getClientPort(curTunnel)%>" class="freetext port" placeholder="required" />
            </td>
         <% } /* httpbidirserver */ %>
         <% if ("httpbidirserver".equals(tunnelType) || "streamrserver".equals(tunnelType)) { %>

            <td>
                <b><%=intl._t("Reachable by")%>:</b>

                <select id="reachableBy" name="reachableBy" title="IP for Client Access" class="selectbox">
              <%
                    String clientInterface = editBean.getClientInterface(curTunnel);
                    for (String ifc : editBean.interfaceSet()) {
                        out.write("<option value=\"");
                        out.write(ifc);
                        out.write('\"');
                        if (ifc.equals(clientInterface))
                            out.write(" selected=\"selected\"");
                        out.write('>');
                        out.write(ifc);
                        out.write("</option>\n");
                    }
              %>
                </select>
            </td>
         <% } /* httpbidirserver || streamrserver */ %>
        </tr>

            
            <% if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
          %>

        <tr>
            <th>
                <%=intl._t("Website Hostname")%>
            </th>
            <th></th>
        </tr>

        <tr>
            <td>
                <input type="text" size="20" id="websiteName" name="spoofedHost" title="Website Hostname" value="<%=editBean.getSpoofedHost(curTunnel)%>" class="freetext" />
                <%=intl._t("(leave blank for outproxies)")%>
            </td>
            <td></td>
        </tr>
            <% }
          %>
          
        <tr>
            <th colspan="2">
                <%=intl._t("Private key file")%>
            </th>
        </tr>

        <tr>
            <td colspan="2">
                    <% String value3 = editBean.getPrivateKeyFile(curTunnel);
                       if (value3 == null || "".equals(value3.trim())) {
                           out.write(" <span class=\"required\"><font color=\"red\">(");
                           out.write(intl._t("required"));
                           out.write(")</font></span>");
                       }
                     %>
                <input type="text" size="30" id="privKeyFile" name="privKeyFile" title="Path to Private Key File" value="<%=editBean.getPrivateKeyFile(curTunnel)%>" class="freetext" placeholder="required" />
            </td>
        </tr>

        <tr>
            <th colspan="2">
                <%=intl._t("Local destination")%>
            </th>
        </tr>

        <tr>
            <td colspan="2">
                <textarea rows="1" style="height: 3em;" cols="60" readonly="readonly" id="localDestination" title="Read Only: Local Destination (if known)" wrap="off" spellcheck="false"><%=editBean.getDestinationBase64(curTunnel)%></textarea>
            </td>
        </tr>

<%
  /******
%>
            <% if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
                   String sig = editBean.getNameSignature(curTunnel);
                   if (sig.length() > 0) {
          %><div id="sigField" class="rowItem">
                <label for="signature">
                    <%=intl._t("Hostname Signature")%>
                </label>
                <input type="text" size="30" readonly="readonly" title="Use to prove that the website name is for this destination" value="<%=sig%>" wrap="off" class="freetext" />                
            </div>
         <%
                   }  // sig
               }  // type
  ****/

            String b64 = editBean.getDestinationBase64(curTunnel);
            if (!"".equals(b64)) {
         %>
        <tr>

        <%
                b64 = b64.replace("=", "%3d");
                String name = editBean.getSpoofedHost(curTunnel);
                if (name == null || name.equals(""))
                    name = editBean.getTunnelName(curTunnel);
                // mysite.i2p is set in the installed i2ptunnel.config
                if (name != null && !name.equals("") && !name.equals("mysite.i2p") && !name.contains(" ") && name.endsWith(".i2p")) {
         %>

            <td class="buttons" colspan="2">
              <a class="control" title="<%=intl._t("Generate QR Code")%>" href="/imagegen/qr?s=320&amp;t=<%=name%>&amp;c=http%3a%2f%2f<%=name%>%2f%3fi2paddresshelper%3d<%=b64%>" target="_top"><%=intl._t("Generate QR Code")%></a>
              <a class="control" href="/susidns/addressbook.jsp?book=private&amp;hostname=<%=name%>&amp;destination=<%=b64%>#add"><%=intl._t("Add to local addressbook")%></a>    
              <a class="control" href="register?tunnel=<%=curTunnel%>"><%=intl._t("Registration Authentication")%></a>
            </td>
        <%
                } else {
          %>
            <td class="infohelp" colspan="2">
                <%=intl._t("Note: In order to enable QR code generation or registration authentication, configure the Name field above with .i2p suffix eg.  mynewserver.i2p")%>
            </td>
        <%
                }  // name
         %>
        </tr>

        <%
            }  // b64

         %>
    </table>

    <h3><%=intl._t("Advanced Networking Options")%></h3>

    <table id="#advancedServerTunnelOptions" class="tunnelConfig">
        <tr>
            <th colspan="2">
                <%=intl._t("Tunnel Options")%>
            </th>
        </tr>

        <tr>
            <td>
                <b><%=intl._t("Length")%></b>
            </td>

            <td>
                <b><%=intl._t("Variance")%></b>
            </td>
        </tr>

        <tr>
            <td>
                <select id="tunnelDepth" name="tunnelDepth" title="Length of each Tunnel" class="selectbox">
                    <% int tunnelDepth = editBean.getTunnelDepth(curTunnel, 3);
                  %><option value="0"<%=(tunnelDepth == 0 ? " selected=\"selected\"" : "") %>><%=intl._t("0 hop tunnel (no anonymity)")%></option>
                    <option value="1"<%=(tunnelDepth == 1 ? " selected=\"selected\"" : "") %>><%=intl._t("1 hop tunnel (low anonymity)")%></option>
                    <option value="2"<%=(tunnelDepth == 2 ? " selected=\"selected\"" : "") %>><%=intl._t("2 hop tunnel (medium anonymity)")%></option>
                    <option value="3"<%=(tunnelDepth == 3 ? " selected=\"selected\"" : "") %>><%=intl._t("3 hop tunnel (high anonymity)")%></option>
                <% if (editBean.isAdvanced()) {
                  %><option value="4"<%=(tunnelDepth == 4 ? " selected=\"selected\"" : "") %>>4 hop tunnel</option>
                    <option value="5"<%=(tunnelDepth == 5 ? " selected=\"selected\"" : "") %>>5 hop tunnel</option>
                    <option value="6"<%=(tunnelDepth == 6 ? " selected=\"selected\"" : "") %>>6 hop tunnel</option>
                    <option value="7"<%=(tunnelDepth == 7 ? " selected=\"selected\"" : "") %>>7 hop tunnel</option>
                <% } else if (tunnelDepth > 3) { 
                %>    <option value="<%=tunnelDepth%>" selected="selected"><%=tunnelDepth%> <%=intl._t("hop tunnel (very poor performance)")%></option>
                <% }
              %></select>
            </td>

            <td>
                <select id="tunnelVariance" name="tunnelVariance" title="Level of Randomization for Tunnel Depth" class="selectbox">
                    <% int tunnelVariance = editBean.getTunnelVariance(curTunnel, 0);
                  %><option value="0"<%=(tunnelVariance  ==  0 ? " selected=\"selected\"" : "") %>><%=intl._t("0 hop variance (no randomization, consistent performance)")%></option>
                    <option value="1"<%=(tunnelVariance  ==  1 ? " selected=\"selected\"" : "") %>><%=intl._t("+ 0-1 hop variance (medium additive randomization, subtractive performance)")%></option>
                    <option value="2"<%=(tunnelVariance  ==  2 ? " selected=\"selected\"" : "") %>><%=intl._t("+ 0-2 hop variance (high additive randomization, subtractive performance)")%></option>
                    <option value="-1"<%=(tunnelVariance == -1 ? " selected=\"selected\"" : "") %>><%=intl._t("+/- 0-1 hop variance (standard randomization, standard performance)")%></option>
                    <option value="-2"<%=(tunnelVariance == -2 ? " selected=\"selected\"" : "") %>><%=intl._t("+/- 0-2 hop variance (not recommended)")%></option>
                <% if (tunnelVariance > 2 || tunnelVariance < -2) {
                %>    <option value="<%=tunnelVariance%>" selected="selected"><%= (tunnelVariance > 2 ? "+ " : "+/- ") %>0-<%=tunnelVariance%> <%=intl._t("hop variance")%></option>
                <% }
              %></select>
            </td>
        </tr>

        <tr>
            <td>
                <b><%=intl._t("Count")%></b>
            </td>

            <td>
                <b><%=intl._t("Backup Count")%></b>
            </td>
        </tr>

        <tr>
            <td>
                <select id="tunnelQuantity" name="tunnelQuantity" title="Number of Tunnels in Group" class="selectbox">
                    <%=editBean.getQuantityOptions(curTunnel)%>
                </select>
            </td>

            <td>
                <select id="tunnelBackupQuantity" name="tunnelBackupQuantity" title="Number of Reserve Tunnels" class="selectbox">
                    <% int tunnelBackupQuantity = editBean.getTunnelBackupQuantity(curTunnel, 0);
                  %><option value="0"<%=(tunnelBackupQuantity == 0 ? " selected=\"selected\"" : "") %>><%=intl._t("0 backup tunnels (0 redundancy, no added resource usage)")%></option>
                    <option value="1"<%=(tunnelBackupQuantity == 1 ? " selected=\"selected\"" : "") %>><%=intl._t("1 backup tunnel each direction (low redundancy, low resource usage)")%></option>
                    <option value="2"<%=(tunnelBackupQuantity == 2 ? " selected=\"selected\"" : "") %>><%=intl._t("2 backup tunnels each direction (medium redundancy, medium resource usage)")%></option>
                    <option value="3"<%=(tunnelBackupQuantity == 3 ? " selected=\"selected\"" : "") %>><%=intl._t("3 backup tunnels each direction (high redundancy, high resource usage)")%></option>
                <% if (tunnelBackupQuantity > 3) {
                %>    <option value="<%=tunnelBackupQuantity%>" selected="selected"><%=tunnelBackupQuantity%> <%=intl._t("backup tunnels")%></option>
                <% }
              %></select>
            </td>
        </tr>
                           
         <% if (!"streamrserver".equals(tunnelType)) { %>

        <tr>
            <th colspan="2">
                <%=intl._t("Profile")%>
            </th>
        </tr>

        <tr>
            <td colspan="2">
                <select id="profile" name="profile" title="Connection Profile" class="selectbox">
                    <% boolean interactiveProfile = editBean.isInteractive(curTunnel);
                  %><option <%=(interactiveProfile == true  ? "selected=\"selected\" " : "")%>value="interactive"><%=intl._t("interactive connection")%> </option>
                    <option <%=(interactiveProfile == false ? "selected=\"selected\" " : "")%>value="bulk"><%=intl._t("bulk connection (downloads/websites/BT)")%> </option>
                </select>
            </td>
        </tr>

         <% } /* !streamrserver */ %>

        <tr>
            <th colspan="2">
                <%=intl._t("Router I2CP Address")%>
            </th>
        </tr>
        <tr>
            <td>
                <b><%=intl._t("Host")%>:</b>
                <input type="text" id="clientHost" name="clientHost" size="20" title="I2CP Hostname or IP" value="<%=editBean.getI2CPHost(curTunnel)%>" class="freetext" <% if (editBean.isRouterContext()) { %> readonly="readonly" <% } %> />
            </td>
            <td>
                <b><%=intl._t("Port")%>:</b>
                <input type="text" id="clientPort" name="clientport" size="20" title="I2CP Port Number" value="<%=editBean.getI2CPPort(curTunnel)%>" class="freetext" <% if (editBean.isRouterContext()) { %> readonly="readonly" <% } %> />
            </td>
        </tr>

        <tr>
            <th colspan="2">
                <%=intl._t("Encrypt Leaseset")%>
            </th>
        </tr>

        <tr>
            <td colspan="2">
                <input value="1" type="checkbox" id="startOnLoad" name="encrypt" title="ONLY clients with the encryption key will be able to connect"<%=(editBean.getEncrypt(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />
                <%=intl._t("Only allow clients with the encryption key to connect to this server")%>
            </td>
        </tr>

        <tr>
            <td>
                <b><%=intl._t("Encryption Key")%></b>
            </td>

            <td>
                <b><%=intl._t("Generate New Key")%></b> (<%=intl._t("Tunnel must be stopped first")%>)
            </td>
        </tr>

        <tr>
            <td>
                <textarea rows="1" style="height: 3em;" cols="44" id="leasesetKey" name="encryptKey" title="Encrypt Key" wrap="off" spellcheck="false"><%=editBean.getEncryptKey(curTunnel)%></textarea>
            </td>

            <td>
                <button class="control" type="submit" name="action" value="Generate" title="Generate New Key Now"><%=intl._t("Generate")%></button>
            </td>
        </tr>

        <tr>
            <th colspan="2">
                <%=intl._t("Restricted Access List")%>
            </th>
        </tr>

        <tr>
            <td colspan="2">
                <% /* can't use <label> here */ %>
                <span class="multiOption"><input value="0" type="radio" name="accessMode" title="<%=intl._t("Allow all clients")%>"<%=(editBean.getAccessMode(curTunnel).equals("0") ? " checked=\"checked\"" : "")%> class="tickbox" />
                    <%=intl._t("Disable")%></span>
                <span class="multiOption"><input value="2" type="radio" name="accessMode" title="<%=intl._t("Reject listed clients")%>"<%=(editBean.getAccessMode(curTunnel).equals("2") ? " checked=\"checked\"" : "")%> class="tickbox" />
                    <%=intl._t("Blacklist")%></span>
                <span class="multiOption"><input value="1" type="radio" name="accessMode" title="<%=intl._t("Allow listed clients only")%>"<%=(editBean.getAccessMode(curTunnel).equals("1") ? " checked=\"checked\"" : "")%> class="tickbox" />
                    <%=intl._t("Whitelist")%></span>
            </td>
        </tr>

        <tr>
            <td colspan="2">
                <b><%=intl._t("Access List")%></b> (<%=intl._t("Specify clients, 1 per line")%>)
            </td>
        </tr>

        <tr>
            <td colspan="2">
                <textarea rows="2" style="height: 8em;" cols="60" name="accessList" title="Access List" wrap="off" spellcheck="false"><%=editBean.getAccessList(curTunnel)%></textarea>
            </td>
        </tr>

        <tr>
            <th colspan="2">
                <%=intl._t("Server Access Options")%>
            </th>
        </tr>

            <% if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
            %>

        <tr>
            <td>
                <input value="1" type="checkbox" name="rejectInproxy" title="<%=intl._t("Deny inproxy access when enabled")%>" <%=(editBean.isRejectInproxy(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />
                <%=intl._t("Block Access via Inproxies")%>
            </td>

            <td>
                <input value="1" type="checkbox" name="rejectReferer" title="<%=intl._t("Deny accesseses with referers (probably from inproxies)")%>" <%=(editBean.isRejectReferer(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />
                <%=intl._t("Block Accesses containing Referers")%>
            </td>
        </tr>

        <tr>
            <td>
                <input value="1" type="checkbox" name="rejectUserAgents" title="<%=intl._t("Deny User-Agents matching these strings (probably from inproxies)")%>" <%=(editBean.isRejectUserAgents(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />
                <%=intl._t("Block these User-Agents")%>
            </td>

            <td>
                <input type="text" id="userAgents" name="userAgents" size="20" title="<%=intl._t("comma separated, e.g. Mozilla,Opera (case-sensitive)")%>" value="<%=editBean.getUserAgents(curTunnel)%>" class="freetext" />
            </td>
        </tr>
            <% } // httpserver
            %>

        <tr>
            <td>
                <input value="1" type="checkbox" name="uniqueLocal" title="<%=intl._t("Use unique IP addresses for each connecting client (local non-SSL servers only)")%>" <%=(editBean.getUniqueLocal(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />
                <%=intl._t("Unique Local Address per Client")%>
            </td>

            <td>
                <input value="1" type="checkbox" name="multihome" title="<%=intl._t("Only enable if you are hosting this service on multiple routers")%>" <%=(editBean.getMultihome(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />
                <%=intl._t("Optimize for Multihoming")%>
            </td>
        </tr>

        <tr>
            <th colspan="2">
                <%=intl._t("Server Throttling")%>
            </th>
        </tr>
        <tr>
            <td id="throttle" colspan="4">

                <table id="throttler">
                    <tr>
                        <th colspan="5">
                            <%=intl._t("Inbound connection limits (0=unlimited)")%>
                        </th>
                    </tr>
                    <tr>
                        <td></td>
                        <td><b><%=intl._t("Per Minute")%></b></td>
                        <td><b><%=intl._t("Per Hour")%></b></td>
                        <td><b><%=intl._t("Per Day")%></b></td>
                        <td class="blankColumn"></td>
                    </tr>
                    <tr>
                        <td><b><%=intl._t("Per Client")%></b></td>
                        <td>
                            <input type="text" name="limitMinute" value="<%=editBean.getLimitMinute(curTunnel)%>" class="freetext" />
                        </td>
                        <td>
                            <input type="text" name="limitHour" value="<%=editBean.getLimitHour(curTunnel)%>" class="freetext" />
                        </td>
                        <td>
                            <input type="text" name="limitDay" value="<%=editBean.getLimitDay(curTunnel)%>" class="freetext" />
                        </td>
                        <td class="blankColumn"></td>
                    </tr>
                    <tr>
                        <td><b><%=intl._t("Total")%></b></td>
                        <td>
                            <input type="text" name="totalMinute" value="<%=editBean.getTotalMinute(curTunnel)%>" class="freetext" />
                        </td>
                        <td>
                            <input type="text" name="totalHour" value="<%=editBean.getTotalHour(curTunnel)%>" class="freetext" />
                        </td>
                        <td>
                            <input type="text" name="totalDay" value="<%=editBean.getTotalDay(curTunnel)%>" class="freetext" />
                        </td>
                        <td class="blankColumn"></td>
                    </tr>
                    <tr>
                        <th colspan="5"><%=intl._t("Max concurrent connections (0=unlimited)")%></th>
                    </tr>
                    <tr>
                        <td></td>
                        <td>
                            <input type="text" name="maxStreams" value="<%=editBean.getMaxStreams(curTunnel)%>" class="freetext" />
                        </td>
                        <td></td>
                        <td></td>
                        <td class="blankColumn"></td>
                    </tr>

            <% if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
              %>
                    <tr>
                        <th colspan="5">
                            <%=intl._t("POST limits (0=unlimited)")%>
                        </th>
                    </tr>
                    <tr>
                        <td></td>
                        <td>
                            <b><%=intl._t("Per Period")%></b>
                        </td>
                        <td>
                            <b><%=intl._t("Ban Duration")%></b>
                        </td>
                        <td></td>
                        <td class="blankColumn"></td>
                    </tr>
                    <tr>
                        <td>
                            <b><%=intl._t("Per Client")%>
                            </b>
                        </td>
                        <td>
                            <input type="text" name="postMax" value="<%=editBean.getPostMax(curTunnel)%>" class="freetext quantity"/>
                        </td>
                        <td>
                            <input type="text" name="postBanTime" value="<%=editBean.getPostBanTime(curTunnel)%>" class="freetext period"/>
                            <%=intl._t("minutes")%>
                        </td>
                        <td></td>
                        <td class="blankColumn"></td>
                    </tr>
                    <tr>
                        <td>
                            <b><%=intl._t("Total")%>
                            </b>
                        </td>
                        <td>
                            <input type="text" name="postTotalMax" value="<%=editBean.getPostTotalMax(curTunnel)%>" class="freetext quantity"/>
                        </td>
                        <td>
                            <input type="text" name="postTotalBanTime" value="<%=editBean.getPostTotalBanTime(curTunnel)%>" class="freetext period"/>
                            <%=intl._t("minutes")%>
                        </td>
                        <td></td>
                        <td class="blankColumn"></td>
                    </tr>
                    <tr>
                        <td>
                            <b><%=intl._t("POST limit period")%>
                            </b>
                        </td>
                        <td>
                            <input type="text" name="postCheckTime" value="<%=editBean.getPostCheckTime(curTunnel)%>" class="freetext period"/>
                            <%=intl._t("minutes")%>
                        </td>
                        <td></td>
                        <td></td>
                        <td class="blankColumn"></td>
                    </tr>

            <% } // httpserver
          %>


                </table>
            </td>
        </tr>

        <tr>
            <th colspan="2">
                <%=intl._t("Reduce tunnel quantity when idle")%>
            </th>
        </tr>

        <tr>
            <td colspan="2">
                <input value="1" type="checkbox" id="startOnLoad" name="reduce" title="Reduce Tunnels"<%=(editBean.getReduce(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />
                <%=intl._t("Reduce tunnel quantity when idle to conserve resources")%>
            </td>
        </tr>
        <tr>
            <td>
                <b><%=intl._t("Reduced tunnel count")%>:</b>
                <input type="text" id="reduceCount" name="reduceCount" size="1" maxlength="1" title="Reduced Tunnel Count" value="<%=editBean.getReduceCount(curTunnel)%>" class="freetext quantity" />
            </td>

            <td>
                <b><%=intl._t("Idle period")%>:</b>
                <input type="text" id="reduceTime" name="reduceTime" size="4" maxlength="4" title="Reduced Tunnel Idle Time" value="<%=editBean.getReduceTime(curTunnel)%>" class="freetext period" />
                <%=intl._t("minutes")%>
            </td>
        </tr>
           
<% /***************** %>
            <div id="tunnelOptionsField" class="rowItem">
                <label for="cert" accesskey="c">
                    <%=intl._t("New Certificate type")%>(<span class="accessKey">C</span>):
                </label>
            </div>
            <div id="hostField" class="rowItem">
              <div id="portField" class="rowItem">
                <label><%=intl._t("None")%></label>
                <input value="0" type="radio" id="startOnLoad" name="cert" title="No Certificate"<%=(editBean.getCert(curTunnel)==0 ? " checked=\"checked\"" : "")%> class="tickbox" />                
              </div>
              <div id="portField" class="rowItem">
                <label><%=intl._t("Hashcash (effort)")%></label>
                <input value="1" type="radio" id="startOnLoad" name="cert" title="Hashcash Certificate"<%=(editBean.getCert(curTunnel)==1 ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <input type="text" id="port" name="effort" size="2" maxlength="2" title="Hashcash Effort" value="<%=editBean.getEffort(curTunnel)%>" class="freetext" />                
              </div>
            </div>
            <div id="portField" class="rowItem">
                <label for="force" accesskey="c">
                    <%=intl._t("Hashcash Calc Time")%>:
                </label>
                <button accesskey="S" class="control" type="submit" name="action" value="Estimate" title="Estimate Calculation Time"><%=intl._t("Estimate")%></button>
            </div>
            <div id="hostField" class="rowItem">
              <div id="portField" class="rowItem">
                <label><%=intl._t("Hidden")%></label>
                <input value="2" type="radio" id="startOnLoad" name="cert" title="Hidden Certificate"<%=(editBean.getCert(curTunnel)==2 ? " checked=\"checked\"" : "")%> class="tickbox" />                
              </div>
              <div id="portField" class="rowItem">
                <label for="signer" accesskey="c">
                    <%=intl._t("Signed (signed by)")%>:
                </label>
                <input value="3" type="radio" id="startOnLoad" name="cert" title="Signed Certificate"<%=(editBean.getCert(curTunnel)==3 ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <input type="text" id="port" name="signer" size="50" title="Cert Signer" value="<%=editBean.getSigner(curTunnel)%>" class="freetext" />                
              </div>
            </div>
            <div id="portField" class="rowItem">
                <label for="force" accesskey="c">
                    <%=intl._t("Modify Certificate")%>:
                </label>
                <button accesskey="S" class="control" type="submit" name="action" value="Modify" title="Force New Cert Now"><%=intl._t("Modify")%></button>
                <span class="comment"><%=intl._t("(Tunnel must be stopped first)")%></span>
            </div>
<% **********************/ %>

         <% if (true /* editBean.isAdvanced() */ ) {
                int currentSigType = editBean.getSigType(curTunnel, tunnelType);
           %>
        <tr>
            <th colspan="2">
                <%=intl._t("Signature type")%> (<%=intl._t("Experts only! Changes B32!")%>)
            </th>
        </tr>
        <tr>
            <td colspan="2">
                <span class="multiOption">
                    <input value="0" type="radio" id="startOnLoad" name="sigType" title="Default"<%=(currentSigType==0 ? " checked=\"checked\"" : "")%> class="tickbox" />
                    DSA-SHA1
                </span>
           <% if (editBean.isSigTypeAvailable(1)) { %>
                <span class="multiOption">
                    <input value="1" type="radio" id="startOnLoad" name="sigType" title="Advanced users only"<%=(currentSigType==1 ? " checked=\"checked\"" : "")%> class="tickbox" />
                    ECDSA-P256
                </span>
           <% }
              if (editBean.isSigTypeAvailable(2)) { %>
                <span class="multiOption">
                    <input value="2" type="radio" id="startOnLoad" name="sigType" title="Advanced users only"<%=(currentSigType==2 ? " checked=\"checked\"" : "")%> class="tickbox" />
                    ECDSA-P384
                </span>
           <% }
              if (editBean.isSigTypeAvailable(3)) { %>
                <span class="multiOption">
                    <input value="3" type="radio" id="startOnLoad" name="sigType" title="Advanced users only"<%=(currentSigType==3 ? " checked=\"checked\"" : "")%> class="tickbox" />
                    ECDSA-P521
                </span>
           <% }
              if (editBean.isSigTypeAvailable(7)) { %>
                <span class="multiOption">
                    <input value="7" type="radio" id="startOnLoad" name="sigType" title="Advanced users only"<%=(currentSigType==7 ? " checked=\"checked\"" : "")%> class="tickbox" />
                    Ed25519-SHA-512
                </span>
           <% }   // isAvailable %>

            </td>
        </tr>

         <% } // isAdvanced %>

        <tr>
            <th colspan="2">
                <%=intl._t("Custom options")%>
            </th>
        </tr>
        <tr>
            <td colspan="2">
                <input type="text" id="customOptions" name="nofilter_customOptions" size="60" title="Custom Options" value="<%=editBean.getCustomOptions(curTunnel)%>" class="freetext" spellcheck="false"/>
            </td>
        </tr>

        <tr>
            <td class="buttons" colspan="2">
                    <input type="hidden" value="true" name="removeConfirm" />
                    <button id="controlCancel" class="control" type="submit" name="action" value="" title="Cancel"><%=intl._t("Cancel")%></button>
                    <button id="controlDelete" <%=(editBean.allowJS() ? "onclick=\"if (!confirm('Are you sure you want to delete?')) { return false; }\" " : "")%>accesskey="D" class="control" type="submit" name="action" value="Delete this proxy" title="Delete this Proxy"><%=intl._t("Delete")%></button>
                    <button id="controlSave" accesskey="S" class="control" type="submit" name="action" value="Save changes" title="Save Changes"><%=intl._t("Save")%></button>
            </td>
        </tr>
    </table>
</div>
    </form>

<%

  } else {
     %><div id="notReady"><%=intl._t("Tunnels are not initialized yet, please reload in two minutes.")%></div><%
  }  // isInitialized()

%>
</body>
</html>
