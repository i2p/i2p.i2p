<%@page contentType="text/html" import="net.i2p.i2ptunnel.web.EditBean"%><?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
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
    <title>I2PTunnel Webmanager - Edit</title>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=UTF-8" />

    <% if (editBean.allowCSS()) {
  %><link href="/themes/console/images/favicon.ico" type="image/x-icon" rel="shortcut icon" />
    <link href="<%=editBean.getTheme()%>default.css" rel="stylesheet" type="text/css" /> 
    <link href="<%=editBean.getTheme()%>i2ptunnel.css" rel="stylesheet" type="text/css" />
    <% }
  %>
</head>
<body id="tunnelEditPage">
    <div id="pageHeader">
    </div>

    <form method="post" action="index.jsp">

        <div id="tunnelEditPanel" class="panel">
            <div class="header">
                <%
                String tunnelTypeName = "";
                String tunnelType = "";
                if (curTunnel >= 0) {
                    tunnelTypeName = editBean.getTunnelType(curTunnel);
                    tunnelType = editBean.getInternalType(curTunnel);
                  %><h4>Edit proxy settings</h4><% 
                } else {
                    tunnelTypeName = editBean.getTypeName(request.getParameter("type"));
                    tunnelType = request.getParameter("type");
                  %><h4>New proxy settings</h4><% 
                } %>
                <input type="hidden" name="tunnel" value="<%=request.getParameter("tunnel")%>" />
                <input type="hidden" name="nonce" value="<%=editBean.getNextNonce()%>" />
                <input type="hidden" name="type" value="<%=tunnelType%>" />
            </div>
      
            <div class="separator">
                <hr />
            </div>

            <div id="nameField" class="rowItem">
                <label for="name" accesskey="N">
                    <span class="accessKey">N</span>ame:
                </label>
                <input type="text" size="30" maxlength="50" name="name" id="name" title="Tunnel Name" value="<%=editBean.getTunnelName(curTunnel)%>" class="freetext" />               
            </div>
            <div id="typeField" class="rowItem">
                <label>Type:</label>
                <span class="text"><%=tunnelTypeName%></span>
            </div>
            <div id="descriptionField" class="rowItem">
                <label for="description" accesskey="e">
                    D<span class="accessKey">e</span>scription:
                </label>
                <input type="text" size="60" maxlength="80" name="description"  id="description" title="Tunnel Description" value="<%=editBean.getTunnelDescription(curTunnel)%>" class="freetext" />                
            </div>
                 
            <div class="subdivider">
                <hr />
            </div>
                 
            <div id="accessField" class="rowItem">
         <% if ("streamrclient".equals(tunnelType)) { %>
                <label>Target:</label>
         <% } else { %>
                <label>Access Point:</label>
         <% } %>
            </div>
            <div id="portField" class="rowItem">
                <label for="port" accesskey="P">
                    <span class="accessKey">P</span>ort:
                    <% String value = editBean.getClientPort(curTunnel);
                       if (value == null || "".equals(value.trim()))
                           out.write(" <font color=\"red\">(required)</font>");
                     %>
                </label>
                <input type="text" size="6" maxlength="5" id="port" name="port" title="Access Port Number" value="<%=editBean.getClientPort(curTunnel)%>" class="freetext" />               
            </div>
         <% String otherInterface = "";
            String clientInterface = editBean.getClientInterface(curTunnel);
            if ("streamrclient".equals(tunnelType)) {   
                otherInterface = clientInterface;
            } else { %>
            <div id="reachField" class="rowItem">
                <label for="reachableBy" accesskey="r">
                    <span class="accessKey">R</span>eachable by:
                </label>
                <select id="reachableBy" name="reachableBy" title="Valid IP for Client Access" class="selectbox">
                  <%   if (!("127.0.0.1".equals(clientInterface)) &&
                           !("0.0.0.0".equals(clientInterface)) &&
                            (clientInterface != null) &&
                            (clientInterface.trim().length() > 0)) {
                            otherInterface = clientInterface;
                       }
                  %><option value="127.0.0.1"<%=("127.0.0.1".equals(clientInterface) ? " selected=\"selected\"" : "")%>>Locally (127.0.0.1)</option>
                    <option value="0.0.0.0"<%=("0.0.0.0".equals(clientInterface) ? " selected=\"selected\"" : "")%>>Everyone (0.0.0.0)</option>
                    <option value="other"<%=(!("".equals(otherInterface))    ? " selected=\"selected\"" : "")%>>LAN Hosts (Please specify your LAN address)</option>
                </select>                
            </div> 
         <% } // streamrclient %>
            <div id="otherField" class="rowItem">
                <label for="reachableByOther" accesskey="O">
         <% if ("streamrclient".equals(tunnelType)) { %>
                    Host:
                    <% String vvv = otherInterface;
                       if (vvv == null || "".equals(vvv.trim()))
                           out.write(" <font color=\"red\">(required)</font>");
                     %>
         <% } else { %>
                    <span class="accessKey">O</span>ther:
         <% } %>
                </label>
                <input type="text" size="20" id="reachableByOther" name="reachableByOther" title="Alternative IP for Client Access" value="<%=otherInterface%>" class="freetext" />                
            </div>
                                            
            <div class="subdivider">
                <hr />
            </div>
           
            <% if ("httpclient".equals(tunnelType) || "connectclient".equals(tunnelType)) {
          %><div id="destinationField" class="rowItem">
                <label for="proxyList" accesskey="x">
                    Outpro<span class="accessKey">x</span>ies:
                </label>
                <input type="text" size="30" id="proxyList" name="proxyList" title="List of Outproxy I2P destinations" value="<%=editBean.getClientDestination(curTunnel)%>" class="freetext" />                
            </div>
            <% } else if ("client".equals(tunnelType) || "ircclient".equals(tunnelType) || "streamrclient".equals(tunnelType)) {
          %><div id="destinationField" class="rowItem">
                <label for="targetDestination" accesskey="T">
                    <span class="accessKey">T</span>unnel Destination:
                    <% String value2 = editBean.getClientDestination(curTunnel);
                       if (value2 == null || "".equals(value2.trim()))
                           out.write(" <font color=\"red\">(required)</font>");
                     %>
                </label>
                <input type="text" size="30" id="targetDestination" name="targetDestination" title="Destination of the Tunnel" value="<%=editBean.getClientDestination(curTunnel)%>" class="freetext" />                
                <span class="comment">(name or destination)</span>
            </div>
         <% } %>
         <% if (!"streamrclient".equals(tunnelType)) { %>
            <div id="profileField" class="rowItem">
                <label for="profile" accesskey="f">
                    Pro<span class="accessKey">f</span>ile:
                </label>
                <select id="profile" name="profile" title="Connection Profile" class="selectbox">
                    <% boolean interactiveProfile = editBean.isInteractive(curTunnel);
                  %><option <%=(interactiveProfile == true  ? "selected=\"selected\" " : "")%>value="interactive">interactive connection </option>
                    <option <%=(interactiveProfile == false ? "selected=\"selected\" " : "")%>value="bulk">bulk connection (downloads/websites/BT) </option>
                </select>                
            </div>
            <div id="delayConnectField" class="rowItem">
                <label for="connectDelay" accesskey="y">
                    Dela<span class="accessKey">y</span> Connect:
                </label>
                <input value="1000" type="checkbox" id="connectDelay" name="connectDelay" title="Delay Connection"<%=(editBean.shouldDelay(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <span class="comment">(for request/response connections)</span>
            </div>
            <div id="sharedtField" class="rowItem">
                <label for="shared" accesskey="h">
                    S<span class="accessKey">h</span>ared Client:
                </label>
                <input value="true" type="checkbox" id="shared" name="shared" title="Share tunnels with other clients"<%=(editBean.isSharedClient(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <span class="comment">(Share tunnels with other clients and irc/httpclients? Change requires restart of client proxy)</span>
            </div>
         <% } // !streamrclient %>
            <div id="startupField" class="rowItem">
                <label for="startOnLoad" accesskey="a">
                    <span class="accessKey">A</span>uto Start:
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="startOnLoad" title="Start Tunnel Automatically"<%=(editBean.startAutomatically(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <span class="comment">(Check the Box for 'YES')</span>
            </div>
            
            <div class="footer">
            </div>
        </div>

        <div id="tunnelAdvancedNetworking" class="panel">
            <div class="header">
                <h4>Advanced networking options</h4>
                <span class="comment">(NOTE: when this client proxy is configured to share tunnels, then these options are for all the shared proxy clients!)</span>
            </div>

            <div class="separator">
                <hr />
            </div>
            
            <div id="tunnelOptionsField" class="rowItem">
                <label>Tunnel Options:</label>
            </div>
            <div id="depthField" class="rowItem">
                <label for="tunnelDepth" accesskey="t">
                    Dep<span class="accessKey">t</span>h:
                </label>
                <select id="tunnelDepth" name="tunnelDepth" title="Depth of each Tunnel" class="selectbox">
                    <% int tunnelDepth = editBean.getTunnelDepth(curTunnel, 2);
                  %><option value="0"<%=(tunnelDepth == 0 ? " selected=\"selected\"" : "") %>>0 hop tunnel (low anonymity, low latency)</option>
                    <option value="1"<%=(tunnelDepth == 1 ? " selected=\"selected\"" : "") %>>1 hop tunnel (medium anonymity, medium latency)</option>
                    <option value="2"<%=(tunnelDepth == 2 ? " selected=\"selected\"" : "") %>>2 hop tunnel (high anonymity, high latency)</option>
                    <option value="3"<%=(tunnelDepth == 3 ? " selected=\"selected\"" : "") %>>3 hop tunnel (very high anonymity, poor performance)</option>
                <% if (tunnelDepth > 3) { 
                %>    <option value="<%=tunnelDepth%>" selected="selected"><%=tunnelDepth%> hop tunnel (very poor performance)</option>
                <% }
              %></select>
            </div>
            <div id="varianceField" class="rowItem">
                <label for="tunnelVariance" accesskey="v">
                    <span class="accessKey">V</span>ariance:
                </label>
                <select id="tunnelVariance" name="tunnelVariance" title="Level of Randomization for Tunnel Depth" class="selectbox">
                    <% int tunnelVariance = editBean.getTunnelVariance(curTunnel, 0);
                  %><option value="0"<%=(tunnelVariance  ==  0 ? " selected=\"selected\"" : "") %>>0 hop variance (no randomisation, consistant performance)</option>
                    <option value="1"<%=(tunnelVariance  ==  1 ? " selected=\"selected\"" : "") %>>+ 0-1 hop variance (medium additive randomisation, subtractive performance)</option>
                    <option value="2"<%=(tunnelVariance  ==  2 ? " selected=\"selected\"" : "") %>>+ 0-2 hop variance (high additive randomisation, subtractive performance)</option>
                    <option value="-1"<%=(tunnelVariance == -1 ? " selected=\"selected\"" : "") %>>+/- 0-1 hop variance (standard randomisation, standard performance)</option>
                    <option value="-2"<%=(tunnelVariance == -2 ? " selected=\"selected\"" : "") %>>+/- 0-2 hop variance (not recommended)</option>
                <% if (tunnelVariance > 2 || tunnelVariance < -2) {
                %>    <option value="<%=tunnelVariance%>" selected="selected"><%= (tunnelVariance > 2 ? "+ " : "+/- ") %>0-<%=tunnelVariance%> hop variance</option>
                <% }
              %></select>                
            </div>                
            <div id="countField" class="rowItem">
                <label for="tunnelQuantity" accesskey="C">
                    <span class="accessKey">C</span>ount:
                </label>
                <select id="tunnelQuantity" name="tunnelQuantity" title="Number of Tunnels in Group" class="selectbox">
                    <% int tunnelQuantity = editBean.getTunnelQuantity(curTunnel, 2);
                  %><option value="1"<%=(tunnelQuantity == 1 ? " selected=\"selected\"" : "") %>>1 inbound, 1 outbound tunnel  (low bandwidth usage, less reliability)</option>
                    <option value="2"<%=(tunnelQuantity == 2 ? " selected=\"selected\"" : "") %>>2 inbound, 2 outbound tunnels (standard bandwidth usage, standard reliability)</option>
                    <option value="3"<%=(tunnelQuantity == 3 ? " selected=\"selected\"" : "") %>>3 inbound, 3 outbound tunnels (higher bandwidth usage, higher reliability)</option>
                <% if (tunnelQuantity > 3) {
                %>    <option value="<%=tunnelQuantity%>" selected="selected"><%=tunnelQuantity%> tunnels</option>
                <% }
              %></select>                
            </div>
            <div id="backupField" class="rowItem">
                <label for="tunnelBackupQuantity" accesskey="b">
                    <span class="accessKey">B</span>ackup Count:
                </label>
                <select id="tunnelBackupQuantity" name="tunnelBackupQuantity" title="Number of Reserve Tunnels" class="selectbox">
                    <% int tunnelBackupQuantity = editBean.getTunnelBackupQuantity(curTunnel, 0);
                  %><option value="0"<%=(tunnelBackupQuantity == 0 ? " selected=\"selected\"" : "") %>>0 backup tunnels (0 redundancy, no added resource usage)</option>
                    <option value="1"<%=(tunnelBackupQuantity == 1 ? " selected=\"selected\"" : "") %>>1 backup tunnel each direction (low redundancy, low resource usage)</option>
                    <option value="2"<%=(tunnelBackupQuantity == 2 ? " selected=\"selected\"" : "") %>>2 backup tunnels each direction (medium redundancy, medium resource usage)</option>
                    <option value="3"<%=(tunnelBackupQuantity == 3 ? " selected=\"selected\"" : "") %>>3 backup tunnels each direction (high redundancy, high resource usage)</option>
                <% if (tunnelBackupQuantity > 3) {
                %>    <option value="<%=tunnelBackupQuantity%>" selected="selected"><%=tunnelBackupQuantity%> backup tunnels</option>
                <% }
              %></select>                
            </div>
                            
            <div class="subdivider">
                <hr />
            </div>
            
            <div id="optionsField" class="rowItem">
                <label>I2CP Options:</label>
            </div>
            <div id="optionsHostField" class="rowItem">
                <label for="clientHost" accesskey="o">
                    H<span class="accessKey">o</span>st:
                </label>
                <input type="text" id="clientHost" name="clientHost" size="20" title="I2CP Hostname or IP" value="<%=editBean.getI2CPHost(curTunnel)%>" class="freetext" />                
            </div>
            <div id="optionsPortField" class="rowItem">
                <label for="clientPort" accesskey="r">
                    Po<span class="accessKey">r</span>t:
                </label>
                <input type="text" id="clientPort" name="clientport" size="20" title="I2CP Port Number" value="<%=editBean.getI2CPPort(curTunnel)%>" class="freetext" />                
            </div>
                 
         <% if (!"streamrclient".equals(tunnelType)) { // streamr client sends pings so it will never be idle %>
            <div class="subdivider">
                <hr />
            </div>
           
            <div id="optionsField" class="rowItem">
                <label for="reduce" accesskey="d">
                    Re<span class="accessKey">d</span>uce tunnel quantity when idle:
                </label>
            </div>
            <div id="portField" class="rowItem">
                <label for="access" accesskey="d">
                    Enable:
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="reduce" title="Reduce Tunnels"<%=(editBean.getReduce(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
            <div id="portField" class="rowItem">
                <label for="reduceCount" accesskey="d">
                    Reduced tunnel count:
                </label>
                <input type="text" id="port" name="reduceCount" size="1" maxlength="1" title="Reduced Tunnel Count" value="<%=editBean.getReduceCount(curTunnel)%>" class="freetext" />                
            </div>
            <div id="portField" class="rowItem">
                <label for="reduceTime" accesskey="d">
                    Idle minutes:
                </label>
                <input type="text" id="port" name="reduceTime" size="4" maxlength="4" title="Reduced Tunnel Idle Time" value="<%=editBean.getReduceTime(curTunnel)%>" class="freetext" />                
            </div>
            
            <div class="subdivider">
                <hr />
            </div>
           
            <div id="optionsField" class="rowItem">
                <label for="reduce" accesskey="c">
                    <span class="accessKey">C</span>lose tunnels when idle: <i>Experimental</i>
                </label>
            </div>
            <div id="portField" class="rowItem">
                <label for="access" accesskey="c">
                    Enable:
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="close" title="Close Tunnels"<%=(editBean.getClose(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
            <div id="portField" class="rowItem">
                <label for="access" accesskey="c">
                    New Keys on Reopen:
                </label>
                <table border="0"><tr><!-- I give up -->
                <td><input value="1" type="radio" id="startOnLoad" name="newDest" title="New Destination"
                     <%=(editBean.getNewDest(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <td valign="center">Enable
                <td><input value="0" type="radio" id="startOnLoad" name="newDest" title="New Destination"
                     <%=(editBean.getNewDest(curTunnel) || editBean.getPersistentClientKey(curTunnel) ? "" : " checked=\"checked\"")%> class="tickbox" />                
                <td valign="center">Disable
                </table>
            </div>
            <div id="portField" class="rowItem">
                <label for="reduceTime" accesskey="c">
                    Idle minutes:
                </label>
                <input type="text" id="port" name="closeTime" size="4" maxlength="4" title="Close Tunnel Idle Time" value="<%=editBean.getCloseTime(curTunnel)%>" class="freetext" />                
            </div>
                 
            <div class="subdivider">
                <hr />
            </div>

            <div id="optionsField" class="rowItem">
                <label for="reduce" accesskey="c">
                    <span class="accessKey">D</span>elay tunnel open until required: <i>Experimental</i>
                </label>
            </div>
            <div id="portField" class="rowItem">
                <label for="access" accesskey="c">
                    Enable:
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="delayOpen" title="Delay Tunnel Open"<%=(editBean.getDelayOpen(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
         <% } // !streamrclient %>
                 
            <div class="subdivider">
                <hr />
            </div>

         <% if ("client".equals(tunnelType) || "ircclient".equals(tunnelType)) { %>
            <div id="optionsField" class="rowItem">
                <label for="privKeyFile" accesskey="k">
                    Persistent private <span class="accessKey">k</span>ey:
                </label>
            </div>
            <div id="portField" class="rowItem">
                <label>Enable:</label>
                <input value="2" type="radio" id="startOnLoad" name="newDest" title="New Destination"
                     <%=(editBean.getPersistentClientKey(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
            <div id="reachField" class="rowItem">
                <label>File:</label>
                <input type="text" size="30" id="clientHost" name="privKeyFile" title="Path to Private Key File" value="<%=editBean.getPrivateKeyFile(curTunnel)%>" class="freetext" />               
            </div>
            <div id="destinationField" class="rowItem">
                <label for="localDestination" accesskey="L">
                    <span class="accessKey">L</span>ocal destination:
                </label>
                <textarea rows="1" style="height: 3em;" cols="60" readonly="readonly" id="localDestination" title="Read Only: Local Destination (if known)" wrap="off"><%=editBean.getDestinationBase64(curTunnel)%></textarea>               
                <span class="comment">(if known)</span>
            </div>

            <div class="subdivider">
                <hr />
            </div>
         <% } %>
           
            <div id="customOptionsField" class="rowItem">
                <label for="customOptions" accesskey="u">
                    C<span class="accessKey">u</span>stom options:
                </label>
                <input type="text" id="customOptions" name="customOptions" size="60" title="Custom Options" value="<%=editBean.getCustomOptions(curTunnel)%>" class="freetext" />                
            </div>
            
            <div class="footer">
            </div>
        </div>
        <div id="globalOperationsPanel" class="panel">
            <div class="header"></div>
            <div class="footer">
                <div class="toolbox">
                    <span class="comment">NOTE: If tunnel is currently running, most changes will not take effect until tunnel is stopped and restarted</span>
                    <input type="hidden" value="true" name="removeConfirm" />
                    <button id="controlSave" accesskey="S" class="control" type="submit" name="action" value="Save changes" title="Save Changes"><span class="accessKey">S</span>ave</button>
                    <button id="controlDelete" <%=(editBean.allowJS() ? "onclick=\"if (!confirm('Are you sure you want to delete?')) { return false; }\" " : "")%>accesskey="D" class="control" type="submit" name="action" value="Delete this proxy" title="Delete this Proxy"><span class="accessKey">D</span>elete</button>
                    <button id="controlCancel" class="control" type="submit" name="action" value="" title="Cancel">Cancel</button>
                </div>
            </div> 
        </div>
    </form>
    <div id="pageFooter">
        </div>
    </body>
</html>
