<%
    // NOTE: Do the header carefully so there is no whitespace before the <?xml... line

    // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
    if (request.getCharacterEncoding() == null)
        request.setCharacterEncoding("UTF-8");

%><%@page pageEncoding="UTF-8"
%><%@page trimDirectiveWhitespaces="true"
%><%@page contentType="text/html" import="net.i2p.i2ptunnel.web.IndexBean"
%><?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<jsp:useBean class="net.i2p.i2ptunnel.web.IndexBean" id="indexBean" scope="request" />
<jsp:setProperty name="indexBean" property="*" />
<jsp:useBean class="net.i2p.i2ptunnel.web.Messages" id="intl" scope="request" />
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
<head>
    <title><%=intl._("I2P Tunnel Manager - List")%></title>
    
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=UTF-8" />
    <link href="/themes/console/images/favicon.ico" type="image/x-icon" rel="shortcut icon" />
    
    <% if (indexBean.allowCSS()) {
  %><link href="<%=indexBean.getTheme()%>default.css" rel="stylesheet" type="text/css" /> 
    <link href="<%=indexBean.getTheme()%>i2ptunnel.css" rel="stylesheet" type="text/css" />
    <% }
  %>
</head>
<body id="tunnelListPage">
	    <div id="pageHeader">
    </div>

    <div id="statusMessagePanel" class="panel">
        <div class="header">
            <h4><%=intl._("Status Messages")%></h4>
        </div>

        <div class="separator">
            <hr />
        </div>

        <textarea id="statusMessages" rows="4" cols="60" readonly="readonly"><jsp:getProperty name="indexBean" property="messages" /></textarea>

        <div class="separator">
            <hr />
        </div>

        <div class="footer">
            <div class="toolbox">
                <a class="control" href="list"><%=intl._("Refresh")%></a>
            </div>
        </div>    
    </div>

    <div id="globalOperationsPanel" class="panel">
        <div class="header"></div>
        <div class="footer">
            <div class="toolbox">
                <a class="control" href="list?nonce=<%=indexBean.getNextNonce()%>&amp;action=Stop%20all"><%=intl._("Stop All")%></a> <a class="control" href="list?nonce=<%=indexBean.getNextNonce()%>&amp;action=Start%20all"><%=intl._("Start All")%></a> <a class="control" href="list?nonce=<%=indexBean.getNextNonce()%>&amp;action=Restart%20all"><%=intl._("Restart All")%></a> <a class="control" href="list?nonce=<%=indexBean.getNextNonce()%>&amp;action=Reload%20configuration"><%=intl._("Reload Config")%></a>
            </div>
        </div> 
    </div>



    <div id="localServerTunnelList" class="panel">
        <div class="header">
            
    <h4><%=intl._("I2P Server Tunnels")%></h4>
        </div>

        
  <div class="separator"> </div>

        <div class="nameHeaderField rowItem">
            <label><%=intl._("Name")%>:</label>
        </div>
        <div class="previewHeaderField rowItem">
            <label><%=intl._("Points at")%>:</label>
        </div>
        <div class="targetHeaderField rowItem">
            <label><%=intl._("Preview")%>:</label>
        </div>
        <div class="statusHeaderField rowItem">
            <label><%=intl._("Status")%>:</label>
<hr />        </div>
        
        <%
        for (int curServer = 0; curServer < indexBean.getTunnelCount(); curServer++) {
            if (indexBean.isClient(curServer)) continue;
            
      %>
        <div class="nameField rowItem">
            <label><%=intl._("Name")%>:</label>
            <span class="text"><a href="edit?tunnel=<%=curServer%>" title="Edit Server Tunnel Settings for <%=indexBean.getTunnelName(curServer)%>"><%=indexBean.getTunnelName(curServer)%></a></span>
        </div>
        <div class="previewField rowItem">
            <label><%=intl._("Points at")%>:</label>
            <span class="text">
        <%
            if (indexBean.isServerTargetLinkValid(curServer)) {
          %>
            <a href="http://<%=indexBean.getServerTarget(curServer)%>/" title="Test HTTP server, bypassing I2P"><%=indexBean.getServerTarget(curServer)%></a>
        <%
            } else {
          %><%=indexBean.getServerTarget(curServer)%>
        <%
            }
          %></span>
        </div>
        <div class="targetField rowItem">
            <%
            if (("httpserver".equals(indexBean.getInternalType(curServer)) || ("httpbidirserver".equals(indexBean.getInternalType(curServer)))) && indexBean.getTunnelStatus(curServer) == IndexBean.RUNNING) {
          %><label><%=intl._("Preview")%>:</label>    
            <a class="control" title="Test HTTP server through I2P" href="http://<%=indexBean.getDestHashBase32(curServer)%>.b32.i2p"><%=intl._("Preview")%></a>
            <%
            } else if (indexBean.getTunnelStatus(curServer) == IndexBean.RUNNING) {
          %><span class="text"><%=intl._("Base32 Address")%>:<br /><%=indexBean.getDestHashBase32(curServer)%>.b32.i2p</span>
        <%
            } else {
          %><span class="comment"><%=intl._("No Preview")%></span>
        <%
            }
      %></div>
        <div class="statusField rowItem">
            <label><%=intl._("Status")%>:</label>
            <%
            switch (indexBean.getTunnelStatus(curServer)) {
                case IndexBean.STARTING:
          %><div class="statusStarting text"><%=intl._("Starting...")%></div>    
            <a class="control" title="Stop this Tunnel" href="list?nonce=<%=indexBean.getNextNonce()%>&amp;action=stop&amp;tunnel=<%=curServer%>"><%=intl._("Stop")%></a>
        <%
                break;
                case IndexBean.RUNNING:
          %><div class="statusRunning text"><%=intl._("Running")%></div>    
            <a class="control" title="Stop this Tunnel" href="list?nonce=<%=indexBean.getNextNonce()%>&amp;action=stop&amp;tunnel=<%=curServer%>"><%=intl._("Stop")%></a>
        <%
                break;
                case IndexBean.NOT_RUNNING:
          %><div class="statusNotRunning text"><%=intl._("Stopped")%></div>    
            <a class="control" title="Start this Tunnel" href="list?nonce=<%=indexBean.getNextNonce()%>&amp;action=start&amp;tunnel=<%=curServer%>"><%=intl._("Start")%></a>
        <%
                break;
            }
      %></div>

        <div class="descriptionField rowItem">
            <label><%=intl._("Description")%>:</label>
            <div class="text"><%=indexBean.getTunnelDescription(curServer)%></div>
        </div>

        <div class="subdivider">
            <hr />
        </div>
        <%
        }
      %>
        <div class="separator">
            <hr />
        </div>
           
        <div class="footer">
            <form id="addNewServerTunnelForm" action="edit"> 
            <div class="toolbox">
                    
        <label><%=intl._("New server tunnel")%>:</label>
                    <select name="type">
                        <option value="server"><%=intl._("Standard")%></option>
                        <option value="httpserver">HTTP</option>
                        <option value="httpbidirserver">HTTP bidir</option>
                        <option value="ircserver">IRC</option>
                        <option value="streamrserver">Streamr</option>
                    </select>
                    <input class="control" type="submit" value="<%=intl._("Create")%>" />
                </div>
            </form>
        </div>
    </div>    


    <div id="localClientTunnelList" class="panel">
        <div class="header">
            
    <h4><%=intl._("I2P Client Tunnels")%></h4>
        </div>

        
  <div class="separator"> </div>
        
        <div class="nameHeaderField rowItem">
            <label><%=intl._("Name")%>:</label>
        </div>
        <div class="portHeaderField rowItem">
            <label><%=intl._("Port")%>:</label>
        </div>
        <div class="typeHeaderField rowItem">
            <label><%=intl._("Type")%>:</label>
        </div>
        <div class="interfaceHeaderField rowItem">
            <label><%=intl._("Interface")%>:</label>
        </div>
        <div class="statusHeaderField rowItem">
            <label><%=intl._("Status")%>:</label>
        </div>

        <div class="separator">
            <hr />
        </div>
        <%
        for (int curClient = 0; curClient < indexBean.getTunnelCount(); curClient++) {
            if (!indexBean.isClient(curClient)) continue;
      %>
        <div class="nameField rowItem">
            <label><%=intl._("Name")%>:</label>
            <span class="text"><a href="edit?tunnel=<%=curClient%>" title="Edit Tunnel Settings for <%=indexBean.getTunnelName(curClient)%>"><%=indexBean.getTunnelName(curClient)%></a></span>
        </div>
        <div class="portField rowItem">
            <label><%=intl._("Port")%>:</label>
            <span class="text">
         <%
               String cPort= indexBean.getClientPort(curClient);
               if ("".equals(cPort)) {
                   out.write("<font color=\"red\">");
                   out.write(intl._("Port not set"));
                   out.write("</font>");
               } else {
                   out.write(cPort);
               }
          %>
            </span>
        </div>
        <div class="typeField rowItem">
            <label><%=intl._("Type")%>:</label>
            <span class="text"><%=indexBean.getTunnelType(curClient)%></span>
        </div>
        <div class="interfaceField rowItem">
            <label><%=intl._("Interface")%>:</label>
            <span class="text">
         <%
               /* should only happen for streamr client */
               String cHost= indexBean.getClientInterface(curClient);
               if (cHost == null || "".equals(cHost)) {
                   out.write("<font color=\"red\">");
                   out.write(intl._("Host not set"));
                   out.write("</font>");
               } else {
                   out.write(cHost);
               }
          %>
            </span>
        </div>
        <div class="statusField rowItem">
            <label><%=intl._("Status")%>:</label>
            <%
            switch (indexBean.getTunnelStatus(curClient)) {
                case IndexBean.STARTING:
          %><div class="statusStarting text"><%=intl._("Starting...")%></div>
            <a class="control" title="Stop this Tunnel" href="list?nonce=<%=indexBean.getNextNonce()%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._("Stop")%></a>
        <%
                break;
                case IndexBean.STANDBY:
          %><div class="statusStarting text"><%=intl._("Standby")%></div>
            <a class="control" title="Stop this Tunnel" href="list?nonce=<%=indexBean.getNextNonce()%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._("Stop")%></a>
        <%
                break;
                case IndexBean.RUNNING:
          %><div class="statusRunning text"><%=intl._("Running")%></div>
            <a class="control" title="Stop this Tunnel" href="list?nonce=<%=indexBean.getNextNonce()%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._("Stop")%></a>
        <%
                break;
                case IndexBean.NOT_RUNNING:
          %><div class="statusNotRunning text"><%=intl._("Stopped")%></div>
            <a class="control" title="Start this Tunnel" href="list?nonce=<%=indexBean.getNextNonce()%>&amp;action=start&amp;tunnel=<%=curClient%>"><%=intl._("Start")%></a>
        <%
                break;
            }
      %></div>

        <div class="destinationField rowItem">
            <label>
            <% if ("httpclient".equals(indexBean.getInternalType(curClient)) || "connectclient".equals(indexBean.getInternalType(curClient)) ||
                   "sockstunnel".equals(indexBean.getInternalType(curClient)) || "socksirctunnel".equals(indexBean.getInternalType(curClient))) { %>
                <%=intl._("Outproxy")%>:
            <% } else { %>
                <%=intl._("Destination")%>:
            <% } %>
            </label>
            <div class="text">
            <% String cdest = indexBean.getClientDestination(curClient);
               if (cdest.length() > 0) {
                   %><%=cdest%><%
               } else {
                   %><i><%=intl._("none")%></i><%
               } %>
            </div>
        </div>

        <div class="descriptionField rowItem">
            <label><%=intl._("Description")%>:</label>
            <div class="text"><%=indexBean.getTunnelDescription(curClient)%></div>
        </div>

        <div class="subdivider">
            <hr />
        </div>
        <%
        }
      %>            
        <div class="separator">
            <hr />
        </div>
    
        <div class="footer">
            <form id="addNewClientTunnelForm" action="edit">
                <div class="toolbox">
                    
        <label><%=intl._("New client tunnel")%>:</label>
                    <select name="type">
                        <option value="client"><%=intl._("Standard")%></option>
                        <option value="httpclient">HTTP</option>
                        <option value="ircclient">IRC</option>
                        <option value="sockstunnel">SOCKS 4/4a/5</option>
                        <option value="socksirctunnel">SOCKS IRC</option>
                        <option value="connectclient">CONNECT</option>
                        <option value="streamrclient">Streamr</option>
                    </select>
                    <input class="control" type="submit" value="<%=intl._("Create")%>" />
                </div>
            </form>
        </div>
    </div>
    <div id="pageFooter">
    </div>
</body>
</html>
