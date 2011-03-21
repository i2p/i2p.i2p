<%@page contentType="text/html" import="net.i2p.i2ptunnel.web.EditBean"%><?xml version="1.0" encoding="UTF-8"?>
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
    <title><%=intl._("I2P Tunnel Manager - Edit Server Tunnel")%></title>
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

    <form method="post" action="list">

        <div id="tunnelEditPanel" class="panel">
            <div class="header">
                <%
                String tunnelTypeName = "";
                String tunnelType = "";
                if (curTunnel >= 0) {
                    tunnelTypeName = editBean.getTunnelType(curTunnel);
                    tunnelType = editBean.getInternalType(curTunnel);
                  %><h4><%=intl._("Edit server settings")%></h4><% 
                } else {
                    tunnelTypeName = editBean.getTypeName(request.getParameter("type"));
                    tunnelType = request.getParameter("type");
                  %><h4><%=intl._("New server settings")%></h4><% 
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
                    <%=intl._("Name")%>(<span class="accessKey">N</span>):
                </label>
                <input type="text" size="30" maxlength="50" name="name" id="name" title="Tunnel Name" value="<%=editBean.getTunnelName(curTunnel)%>" class="freetext" />               
            </div>
            <div id="typeField" class="rowItem">
                <label><%=intl._("Type")%>:</label>
                <span class="text"><%=tunnelTypeName%></span>
            </div>
            <div id="descriptionField" class="rowItem">
                <label for="description" accesskey="e">
                    <%=intl._("Description")%>(<span class="accessKey">e</span>):
                </label>
                <input type="text" size="60" maxlength="80" name="description"  id="description" title="Tunnel Description" value="<%=editBean.getTunnelDescription(curTunnel)%>" class="freetext" />                
            </div>
            <div id="startupField" class="rowItem">
                <label for="startOnLoad" accesskey="a">
                    <%=intl._("Auto Start")%>(<span class="accessKey">A</span>):
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="startOnLoad" title="Start Tunnel Automatically"<%=(editBean.startAutomatically(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <span class="comment"><%=intl._("(Check the Box for 'YES')")%></span>
            </div>
                 
            <div class="subdivider">
                <hr />
            </div>
                 
            <div id="targetField" class="rowItem">
         <% if ("streamrserver".equals(tunnelType)) { %>
                <label><%=intl._("Access Point")%>:</label>
         <% } else { %>
                <label><%=intl._("Target")%>:</label>
         <% } %>
            </div>
         <% if (!"streamrserver".equals(tunnelType)) { %>
            <div id="hostField" class="rowItem">
                <label for="targetHost" accesskey="H">
                    <%=intl._("Host")%>(<span class="accessKey">H</span>):
                </label>
                <input type="text" size="20" id="targetHost" name="targetHost" title="Target Hostname or IP" value="<%=editBean.getTargetHost(curTunnel)%>" class="freetext" />                
            </div>
         <% } /* !streamrserver */ %>
            <div id="portField" class="rowItem">
                <label for="targetPort" accesskey="P">
                    <%=intl._("Port")%>(<span class="accessKey">P</span>):
                    <% String value = editBean.getTargetPort(curTunnel);
                       if (value == null || "".equals(value.trim())) {
                           out.write(" <font color=\"red\">(");
                           out.write(intl._("required"));
                           out.write(")</font>");
                       }   
                     %>
                </label>
                <input type="text" size="6" maxlength="5" id="targetPort" name="targetPort" title="Target Port Number" value="<%=editBean.getTargetPort(curTunnel)%>" class="freetext" />               
            </div>
            
         <% if ("httpbidirserver".equals(tunnelType)) { %>
            <div class="subdivider">
                <hr />
            </div>
            <div id="accessField" class="rowItem">
                <label><%=intl._("Access Point")%>:</label>
            </div> 
            <div id="portField" class="rowItem">
           	<label for="port" accesskey="P">
           	     <span class="accessKey">P</span>ort:
           	     <% String value4 = editBean.getClientPort(curTunnel);
           	        if (value4 == null || "".equals(value4.trim())) {
           	            out.write(" <font color=\"red\">(");
           	            out.write(intl._("required"));
           	            out.write(")</font>");
           	        }
               	      %>
              	 </label>
                 <input type="text" size="6" maxlength="5" id="port" name="port" title="Access Port Number" value="<%=editBean.getClientPort(curTunnel)%>" class="freetext" />
            </div>
         <% } /* httpbidirserver */ %>
         <% if ("httpbidirserver".equals(tunnelType) || "streamrserver".equals(tunnelType)) { %>
            <div id="reachField" class="rowItem">
                <label for="reachableBy" accesskey="r">
                    <%=intl._("Reachable by")%>(<span class="accessKey">R</span>):
                </label>
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
            </div>
         <% } /* httpbidirserver || streamrserver */ %>

            <div class="subdivider">
                <hr />
            </div>
            
            <% if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
          %><div id="websiteField" class="rowItem">
                <label for="spoofedHost" accesskey="W">
                    <%=intl._("Website name")%>(<span class="accessKey">W</span>):
                </label>
                <input type="text" size="20" id="targetHost" name="spoofedHost" title="Website Host Name" value="<%=editBean.getSpoofedHost(curTunnel)%>" class="freetext" />                
                <span class="comment"><%=intl._("(leave blank for outproxies)")%></span>
            </div>
            <% }
          %><div id="privKeyField" class="rowItem">
                <label for="privKeyFile" accesskey="k">
                    <%=intl._("Private key file")%>(<span class="accessKey">k</span>):
                    <% String value3 = editBean.getPrivateKeyFile(curTunnel);
                       if (value3 == null || "".equals(value3.trim())) {
                           out.write(" <font color=\"red\">(");
                           out.write(intl._("required"));
                           out.write(")</font>");
                       }
                     %>
                </label>
                <input type="text" size="30" id="privKeyFile" name="privKeyFile" title="Path to Private Key File" value="<%=editBean.getPrivateKeyFile(curTunnel)%>" class="freetext" />               
            </div>

            <div id="destinationField" class="rowItem">
                <label for="localDestination" accesskey="L">
                    <%=intl._("Local destination")%>(<span class="accessKey">L</span>):
                </label>
                <textarea rows="1" style="height: 3em;" cols="60" readonly="readonly" id="localDestination" title="Read Only: Local Destination (if known)" wrap="off" spellcheck="false"><%=editBean.getDestinationBase64(curTunnel)%></textarea>               
         <% if (!"".equals(editBean.getDestinationBase64(curTunnel))) { %>    
            <a href="/susidns/addressbook.jsp?book=private&amp;hostname=<%=editBean.getTunnelName(curTunnel)%>&amp;destination=<%=editBean.getDestinationBase64(curTunnel)%>#add"><%=intl._("Add to local addressbook")%></a>    
         <% } %>
            </div>

            <% if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
          %><div id="sigField" class="rowItem">
                <label for="signature">
                    <%=intl._("Hostname Signature")%>
                </label>
                <input type="text" size="30" readonly="readonly" title="Use to prove that the website name is for this destination" value="<%=editBean.getNameSignature(curTunnel)%>" wrap="off" class="freetext" />                
            </div>
            <% } %>

            <div class="footer">
            </div>
        </div>

        <div id="tunnelAdvancedNetworking" class="panel">
            <div class="header">
                <h4><%=intl._("Advanced networking options")%></h4>
            </div>

            <div class="separator">
                <hr />
            </div>
            
            <div id="tunnelOptionsField" class="rowItem">
                <label><%=intl._("Tunnel Options")%>:</label>
            </div>
            <div id="depthField" class="rowItem">
                <label for="tunnelDepth" accesskey="t">
                    <%=intl._("Length")%>(<span class="accessKey">t</span>):
                </label>
                <select id="tunnelDepth" name="tunnelDepth" title="Length of each Tunnel" class="selectbox">
                    <% int tunnelDepth = editBean.getTunnelDepth(curTunnel, 2);
                  %><option value="0"<%=(tunnelDepth == 0 ? " selected=\"selected\"" : "") %>><%=intl._("0 hop tunnel (low anonymity, low latency)")%></option>
                    <option value="1"<%=(tunnelDepth == 1 ? " selected=\"selected\"" : "") %>><%=intl._("1 hop tunnel (medium anonymity, medium latency)")%></option>
                    <option value="2"<%=(tunnelDepth == 2 ? " selected=\"selected\"" : "") %>><%=intl._("2 hop tunnel (high anonymity, high latency)")%></option>
                    <option value="3"<%=(tunnelDepth == 3 ? " selected=\"selected\"" : "") %>><%=intl._("3 hop tunnel (very high anonymity, poor performance)")%></option>
                <% if (tunnelDepth > 3) { 
                %>    <option value="<%=tunnelDepth%>" selected="selected"><%=tunnelDepth%> <%=intl._("hop tunnel (very poor performance)")%></option>
                <% }
              %></select>
            </div>
            <div id="varianceField" class="rowItem">
                <label for="tunnelVariance" accesskey="v">
                    <%=intl._("Variance")%>(<span class="accessKey">V</span>):
                </label>
                <select id="tunnelVariance" name="tunnelVariance" title="Level of Randomization for Tunnel Depth" class="selectbox">
                    <% int tunnelVariance = editBean.getTunnelVariance(curTunnel, 0);
                  %><option value="0"<%=(tunnelVariance  ==  0 ? " selected=\"selected\"" : "") %>><%=intl._("0 hop variance (no randomisation, consistant performance)")%></option>
                    <option value="1"<%=(tunnelVariance  ==  1 ? " selected=\"selected\"" : "") %>><%=intl._("+ 0-1 hop variance (medium additive randomisation, subtractive performance)")%></option>
                    <option value="2"<%=(tunnelVariance  ==  2 ? " selected=\"selected\"" : "") %>><%=intl._("+ 0-2 hop variance (high additive randomisation, subtractive performance)")%></option>
                    <option value="-1"<%=(tunnelVariance == -1 ? " selected=\"selected\"" : "") %>><%=intl._("+/- 0-1 hop variance (standard randomisation, standard performance)")%></option>
                    <option value="-2"<%=(tunnelVariance == -2 ? " selected=\"selected\"" : "") %>><%=intl._("+/- 0-2 hop variance (not recommended)")%></option>
                <% if (tunnelVariance > 2 || tunnelVariance < -2) {
                %>    <option value="<%=tunnelVariance%>" selected="selected"><%= (tunnelVariance > 2 ? "+ " : "+/- ") %>0-<%=tunnelVariance%> <%=intl._("hop variance")%></option>
                <% }
              %></select>                
            </div>
            <div id="countField" class="rowItem">
                <label for="tunnelQuantity" accesskey="C">
                    <%=intl._("Count")%>(<span class="accessKey">C</span>):
                </label>
                <select id="tunnelQuantity" name="tunnelQuantity" title="Number of Tunnels in Group" class="selectbox">
                    <% int tunnelQuantity = editBean.getTunnelQuantity(curTunnel, 2);
                  %><option value="1"<%=(tunnelQuantity == 1 ? " selected=\"selected\"" : "") %>><%=intl._("1 inbound, 1 outbound tunnel  (low bandwidth usage, less reliability)")%></option>
                    <option value="2"<%=(tunnelQuantity == 2 ? " selected=\"selected\"" : "") %>><%=intl._("2 inbound, 2 outbound tunnels (standard bandwidth usage, standard reliability)")%></option>
                    <option value="3"<%=(tunnelQuantity == 3 ? " selected=\"selected\"" : "") %>><%=intl._("3 inbound, 3 outbound tunnels (higher bandwidth usage, higher reliability)")%></option>
                <% if (tunnelQuantity > 3) {
                %>    <option value="<%=tunnelQuantity%>" selected="selected"><%=tunnelQuantity%> <%=intl._("tunnels")%></option>
                <% }
              %></select>                
            </div>
            <div id="backupField" class="rowItem">
                <label for="tunnelBackupQuantity" accesskey="b">
                    <%=intl._("Backup Count")%>(<span class="accessKey">B</span>):
                </label>
                <select id="tunnelBackupQuantity" name="tunnelBackupQuantity" title="Number of Reserve Tunnels" class="selectbox">
                    <% int tunnelBackupQuantity = editBean.getTunnelBackupQuantity(curTunnel, 0);
                  %><option value="0"<%=(tunnelBackupQuantity == 0 ? " selected=\"selected\"" : "") %>><%=intl._("0 backup tunnels (0 redundancy, no added resource usage)")%></option>
                    <option value="1"<%=(tunnelBackupQuantity == 1 ? " selected=\"selected\"" : "") %>><%=intl._("1 backup tunnel each direction (low redundancy, low resource usage)")%></option>
                    <option value="2"<%=(tunnelBackupQuantity == 2 ? " selected=\"selected\"" : "") %>><%=intl._("2 backup tunnels each direction (medium redundancy, medium resource usage)")%></option>
                    <option value="3"<%=(tunnelBackupQuantity == 3 ? " selected=\"selected\"" : "") %>><%=intl._("3 backup tunnels each direction (high redundancy, high resource usage)")%></option>
                <% if (tunnelBackupQuantity > 3) {
                %>    <option value="<%=tunnelBackupQuantity%>" selected="selected"><%=tunnelBackupQuantity%> <%=intl._("backup tunnels")%></option>
                <% }
              %></select>                
            </div>
                            
            <div class="subdivider">
                <hr />
            </div>
            
         <% if (!"streamrserver".equals(tunnelType)) { %>
            <div id="profileField" class="rowItem">
                <label for="profile" accesskey="f">
                    <%=intl._("Profile")%>(<span class="accessKey">f</span>):
                </label>
                <select id="profile" name="profile" title="Connection Profile" class="selectbox">
                    <% boolean interactiveProfile = editBean.isInteractive(curTunnel);
                  %><option <%=(interactiveProfile == true  ? "selected=\"selected\" " : "")%>value="interactive"><%=intl._("interactive connection")%> </option>
                    <option <%=(interactiveProfile == false ? "selected=\"selected\" " : "")%>value="bulk"><%=intl._("bulk connection (downloads/websites/BT)")%> </option>
                </select>                
            </div> 

            <div class="subdivider">
                <hr />
            </div>
         <% } /* !streamrserver */ %>

            <div id="optionsField" class="rowItem">
                <label><%=intl._("Router I2CP Address")%>:</label>
            </div>
            <div id="optionsHostField" class="rowItem">
                <label for="clientHost" accesskey="o">
                    <%=intl._("Host")%>(<span class="accessKey">o</span>):
                </label>
                <input type="text" id="clientHost" name="clientHost" size="20" title="I2CP Hostname or IP" value="<%=editBean.getI2CPHost(curTunnel)%>" class="freetext" <% if (editBean.isRouterContext()) { %> readonly="readonly" <% } %> />                
            </div>
            <div id="optionsPortField" class="rowItem">
                <label for="clientPort" accesskey="r">
                    <%=intl._("Port")%>(<span class="accessKey">r</span>):
                </label>
                <input type="text" id="clientPort" name="clientport" size="20" title="I2CP Port Number" value="<%=editBean.getI2CPPort(curTunnel)%>" class="freetext" <% if (editBean.isRouterContext()) { %> readonly="readonly" <% } %> />                
            </div>
            
            <div class="subdivider">
                <hr />
            </div>
           
            <div id="optionsField" class="rowItem">
                <label for="encrypt" accesskey="e">
                    <%=intl._("Encrypt Leaseset")%>(<span class="accessKey">E</span>):
                </label>
            </div>
            <div id="portField" class="rowItem">
                <label for="encrypt" accesskey="e">
                    <%=intl._("Enable")%>:
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="encrypt" title="ONLY clients with the encryption key will be able to connect"<%=(editBean.getEncrypt(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
            <div id="portField" class="rowItem">
                <label for="encrypt" accesskey="e">
                    <%=intl._("Encryption Key")%>:
                </label>
                <textarea rows="1" style="height: 3em;" cols="44" id="portField" name="encryptKey" title="Encrypt Key" wrap="off" spellcheck="false"><%=editBean.getEncryptKey(curTunnel)%></textarea>               
            </div>
            <div id="portField" class="rowItem">
                <label for="force" accesskey="c">
                    <%=intl._("Generate New Key")%>:
                </label>
                <button accesskey="S" class="control" type="submit" name="action" value="Generate" title="Generate New Key Now"><%=intl._("Generate")%></button>
                <span class="comment"><%=intl._("(Tunnel must be stopped first)")%></span>
            </div>
                 
            <div class="subdivider">
                <hr />
            </div>
           
            <div id="optionsField" class="rowItem">
                <label for="access" accesskey="s">
                    <%=intl._("Restricted Access List")%>(<span class="accessKey">s</span>):
                </label>
            </div>
            <div id="portField" class="rowItem">
                <label><%=intl._("Disable")%></label>
                <input value="0" type="radio" id="startOnLoad" name="accessMode" title="Allow all clients"<%=(editBean.getAccessMode(curTunnel).equals("0") ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <label><%=intl._("Whitelist")%></label>
                <input value="1" type="radio" id="startOnLoad" name="accessMode" title="Allow listed clients only"<%=(editBean.getAccessMode(curTunnel).equals("1") ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <label><%=intl._("Blacklist")%></label>
                <input value="2" type="radio" id="startOnLoad" name="accessMode" title="Reject listed clients"<%=(editBean.getAccessMode(curTunnel).equals("2") ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
            <div id="hostField" class="rowItem">
                <label for="accessList" accesskey="s">
                    <%=intl._("Access List")%>:
                </label>
                <textarea rows="2" style="height: 8em;" cols="60" id="hostField" name="accessList" title="Access List" wrap="off" spellcheck="false"><%=editBean.getAccessList(curTunnel)%></textarea>               
            </div>
                 
            <div class="subdivider">
                <hr />
            </div>

            <div class="rowItem">
              <div id="optionsField" class="rowItem">
                  <label><%=intl._("Inbound connection limits (0=unlimited)")%><br /><%=intl._("Per client")%>:</label>
              </div>
              <div id="portField" class="rowItem">
                  <label><%=intl._("Per minute")%>:</label>
                  <input type="text" id="port" name="limitMinute" value="<%=editBean.getLimitMinute(curTunnel)%>" class="freetext" />                
              </div>
              <div id="portField" class="rowItem">
                  <label><%=intl._("Per hour")%>:</label>
                  <input type="text" id="port" name="limitHour" value="<%=editBean.getLimitHour(curTunnel)%>" class="freetext" />                
              </div>
              <div id="portField" class="rowItem">
                  <label><%=intl._("Per day")%>:</label>
                  <input type="text" id="port" name="limitDay" value="<%=editBean.getLimitDay(curTunnel)%>" class="freetext" />                
              </div>
            </div>
            <div class="rowItem">
              <div id="optionsField" class="rowItem">
                  <label><%=intl._("Total")%>:</label>
              </div>
              <div id="portField" class="rowItem">
                  <input type="text" id="port" name="totalMinute" value="<%=editBean.getTotalMinute(curTunnel)%>" class="freetext" />                
              </div>
              <div id="portField" class="rowItem">
                  <input type="text" id="port" name="totalHour" value="<%=editBean.getTotalHour(curTunnel)%>" class="freetext" />                
              </div>
              <div id="portField" class="rowItem">
                  <input type="text" id="port" name="totalDay" value="<%=editBean.getTotalDay(curTunnel)%>" class="freetext" />                
              </div>
            </div>
            <div class="rowItem">
              <div id="optionsField" class="rowItem">
                  <label><%=intl._("Max concurrent connections (0=unlimited)")%>:</label>
              </div>
              <div id="portField" class="rowItem">
                  <input type="text" id="port" name="maxStreams" value="<%=editBean.getMaxStreams(curTunnel)%>" class="freetext" />                
              </div>
            </div>

            <div class="subdivider">
                <hr />
            </div>
           
            <div id="optionsField" class="rowItem">
                <label for="reduce" accesskey="d">
                    <%=intl._("Reduce tunnel quantity when idle")%>(<span class="accessKey">d</span>):
                </label>
            </div>
            <div id="portField" class="rowItem">
                <label for="access" accesskey="d">
                    <%=intl._("Enable")%>:
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="reduce" title="Reduce Tunnels"<%=(editBean.getReduce(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
            <div id="portField" class="rowItem">
                <label for="reduceCount" accesskey="d">
                    <%=intl._("Reduced tunnel count")%>:
                </label>
                <input type="text" id="port" name="reduceCount" size="1" maxlength="1" title="Reduced Tunnel Count" value="<%=editBean.getReduceCount(curTunnel)%>" class="freetext" />                
            </div>
            <div id="portField" class="rowItem">
                <label for="reduceTime" accesskey="d">
                    <%=intl._("Idle minutes")%>:
                </label>
                <input type="text" id="port" name="reduceTime" size="4" maxlength="4" title="Reduced Tunnel Idle Time" value="<%=editBean.getReduceTime(curTunnel)%>" class="freetext" />                
            </div>

            <div class="subdivider">
                <hr />
            </div>
           
            <div id="tunnelOptionsField" class="rowItem">
                <label for="cert" accesskey="c">
                    <%=intl._("New Certificate type")%>(<span class="accessKey">C</span>):
                </label>
            </div>
            <div id="hostField" class="rowItem">
              <div id="portField" class="rowItem">
                <label><%=intl._("None")%></label>
                <input value="0" type="radio" id="startOnLoad" name="cert" title="No Certificate"<%=(editBean.getCert(curTunnel)==0 ? " checked=\"checked\"" : "")%> class="tickbox" />                
              </div>
              <div id="portField" class="rowItem">
                <label><%=intl._("Hashcash (effort)")%></label>
                <input value="1" type="radio" id="startOnLoad" name="cert" title="Hashcash Certificate"<%=(editBean.getCert(curTunnel)==1 ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <input type="text" id="port" name="effort" size="2" maxlength="2" title="Hashcash Effort" value="<%=editBean.getEffort(curTunnel)%>" class="freetext" />                
              </div>
            </div>
            <div id="portField" class="rowItem">
                <label for="force" accesskey="c">
                    <%=intl._("Hashcash Calc Time")%>:
                </label>
                <button accesskey="S" class="control" type="submit" name="action" value="Estimate" title="Estimate Calculation Time"><%=intl._("Estimate")%></button>
            </div>
            <div id="hostField" class="rowItem">
              <div id="portField" class="rowItem">
                <label><%=intl._("Hidden")%></label>
                <input value="2" type="radio" id="startOnLoad" name="cert" title="Hidden Certificate"<%=(editBean.getCert(curTunnel)==2 ? " checked=\"checked\"" : "")%> class="tickbox" />                
              </div>
              <div id="portField" class="rowItem">
                <label for="signer" accesskey="c">
                    <%=intl._("Signed (signed by)")%>:
                </label>
                <input value="3" type="radio" id="startOnLoad" name="cert" title="Signed Certificate"<%=(editBean.getCert(curTunnel)==3 ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <input type="text" id="port" name="signer" size="50" title="Cert Signer" value="<%=editBean.getSigner(curTunnel)%>" class="freetext" />                
              </div>
            </div>
            <div id="portField" class="rowItem">
                <label for="force" accesskey="c">
                    <%=intl._("Modify Certificate")%>:
                </label>
                <button accesskey="S" class="control" type="submit" name="action" value="Modify" title="Force New Cert Now"><%=intl._("Modify")%></button>
                <span class="comment"><%=intl._("(Tunnel must be stopped first)")%></span>
            </div>
                 
            <div class="subdivider">
                <hr />
            </div>
                 
            <div id="customOptionsField" class="rowItem">
                <label for="customOptions" accesskey="u">
                    <%=intl._("Custom options")%>(<span class="accessKey">u</span>):
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
                    <span class="comment"><%=intl._("NOTE: If tunnel is currently running, most changes will not take effect until tunnel is stopped and restarted.")%></span>
                    <div class="separator"><hr /></div>
                    <input type="hidden" value="true" name="removeConfirm" />
                    <button id="controlCancel" class="control" type="submit" name="action" value="" title="Cancel"><%=intl._("Cancel")%></button>
                    <button id="controlDelete" <%=(editBean.allowJS() ? "onclick=\"if (!confirm('Are you sure you want to delete?')) { return false; }\" " : "")%>accesskey="D" class="control" type="submit" name="action" value="Delete this proxy" title="Delete this Proxy"><%=intl._("Delete")%>(<span class="accessKey">D</span>)</button>
                    <button id="controlSave" accesskey="S" class="control" type="submit" name="action" value="Save changes" title="Save Changes"><%=intl._("Save")%>(<span class="accessKey">S</span>)</button>
                </div>
            </div> 
        </div>
    </form>
    <div id="pageFooter">
    </div>
</body>
</html>
