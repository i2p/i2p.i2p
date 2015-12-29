<%
    // NOTE: Do the header carefully so there is no whitespace before the <?xml... line

    // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
    if (request.getCharacterEncoding() == null)
        request.setCharacterEncoding("UTF-8");

    response.setHeader("X-Frame-Options", "SAMEORIGIN");
    response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'");
    response.setHeader("X-XSS-Protection", "1; mode=block");

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
    <title><%=intl._t("Hidden Services Manager")%></title>
    
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=UTF-8" />
    <link href="/themes/console/images/favicon.ico" type="image/x-icon" rel="shortcut icon" />
    
    <% if (indexBean.allowCSS()) {
  %><link rel="icon" href="<%=indexBean.getTheme()%>images/favicon.ico" />
    <link href="<%=indexBean.getTheme()%>default.css" rel="stylesheet" type="text/css" /> 
    <link href="<%=indexBean.getTheme()%>i2ptunnel.css" rel="stylesheet" type="text/css" />
    <% }
  %>
</head>
<body id="tunnelListPage">
	    <div id="pageHeader">
    </div>

    <div id="statusMessagePanel" class="panel">
        <div class="header">
            <h4><%=intl._t("Status Messages")%></h4>
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
                <a class="control" href="list"><%=intl._t("Refresh")%></a>
            </div>
        </div>    
    </div>
<%

  if (indexBean.isInitialized()) {
      String nextNonce = net.i2p.i2ptunnel.web.IndexBean.getNextNonce();

%>
    <div id="globalOperationsPanel" class="panel">
        <div class="header"></div>
        <div class="footer">
            <div class="toolbox">
                <a class="control" href="wizard"><%=intl._t("Tunnel Wizard")%></a>
                <a class="control" href="list?nonce=<%=nextNonce%>&amp;action=Stop%20all"><%=intl._t("Stop All")%></a>
                <a class="control" href="list?nonce=<%=nextNonce%>&amp;action=Start%20all"><%=intl._t("Start All")%></a>
                <a class="control" href="list?nonce=<%=nextNonce%>&amp;action=Restart%20all"><%=intl._t("Restart All")%></a>
<%--
                //this is really bad because it stops and restarts all tunnels, which is probably not what you want
                <a class="control" href="list?nonce=<%=nextNonce%>&amp;action=Reload%20configuration"><%=intl._t("Reload Config")%></a>
--%>
            </div>
        </div> 
    </div>



    <div id="localServerTunnelList" class="panel">
        <div class="header">
            
    <h4><%=intl._t("I2P Hidden Services")%></h4>
        </div>

        
  <div class="separator"> </div>

        <div class="nameHeaderField rowItem">
            <label><%=intl._t("Name")%>:</label>
        </div>
        <div class="previewHeaderField rowItem">
            <label><%=intl._t("Points at")%>:</label>
        </div>
        <div class="targetHeaderField rowItem">
            <label><%=intl._t("Preview")%>:</label>
        </div>
        <div class="statusHeaderField rowItem">
            <label><%=intl._t("Status")%>:</label>
<hr />        </div>
        
        <%
        for (int curServer = 0; curServer < indexBean.getTunnelCount(); curServer++) {
            if (indexBean.isClient(curServer)) continue;
            
      %>
        <div class="nameField rowItem">
            <label><%=intl._t("Name")%>:</label>
            <span class="text"><a href="edit?tunnel=<%=curServer%>" title="Edit Server Tunnel Settings for <%=indexBean.getTunnelName(curServer)%>"><%=indexBean.getTunnelName(curServer)%></a></span>
        </div>
        <div class="previewField rowItem">
            <label><%=intl._t("Points at")%>:</label>
            <span class="text">
        <%
            if (indexBean.isServerTargetLinkValid(curServer)) {
                if (indexBean.isSSLEnabled(curServer)) { %>
                    <a href="https://<%=indexBean.getServerTarget(curServer)%>/" title="Test HTTPS server, bypassing I2P" target="_top"><%=indexBean.getServerTarget(curServer)%> SSL</a>
             <% } else { %>
                    <a href="http://<%=indexBean.getServerTarget(curServer)%>/" title="Test HTTP server, bypassing I2P" target="_top"><%=indexBean.getServerTarget(curServer)%></a>
        <%
                }
            } else {
          %><%=indexBean.getServerTarget(curServer)%>
        <%
                if (indexBean.isSSLEnabled(curServer)) { %>
                    SSL
        <%
                }
            }
          %></span>
        </div>
        <div class="targetField rowItem">
            <%
            if (("httpserver".equals(indexBean.getInternalType(curServer)) || ("httpbidirserver".equals(indexBean.getInternalType(curServer)))) && indexBean.getTunnelStatus(curServer) == IndexBean.RUNNING) {
          %><label><%=intl._t("Preview")%>:</label>    
            <a class="control" title="Test HTTP server through I2P" href="http://<%=indexBean.getDestHashBase32(curServer)%>" target="_top"><%=intl._t("Preview")%></a>
            <%
            } else if (indexBean.getTunnelStatus(curServer) == IndexBean.RUNNING) {
          %><span class="text"><%=intl._t("Base32 Address")%>:<br /><%=indexBean.getDestHashBase32(curServer)%></span>
        <%
            } else {
          %><span class="comment"><%=intl._t("No Preview")%></span>
        <%
            }
      %></div>
        <div class="statusField rowItem">
            <label><%=intl._t("Status")%>:</label>
            <%
            switch (indexBean.getTunnelStatus(curServer)) {
                case IndexBean.STARTING:
          %><div class="statusStarting text"><%=intl._t("Starting...")%></div>    
            <a class="control" title="Stop this Tunnel" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curServer%>"><%=intl._t("Stop")%></a>
        <%
                break;
                case IndexBean.RUNNING:
          %><div class="statusRunning text"><%=intl._t("Running")%></div>    
            <a class="control" title="Stop this Tunnel" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curServer%>"><%=intl._t("Stop")%></a>
        <%
                break;
                case IndexBean.NOT_RUNNING:
          %><div class="statusNotRunning text"><%=intl._t("Stopped")%></div>    
            <a class="control" title="Start this Tunnel" href="list?nonce=<%=nextNonce%>&amp;action=start&amp;tunnel=<%=curServer%>"><%=intl._t("Start")%></a>
        <%
                break;
            }
      %></div>

        <div class="descriptionField rowItem">
            <label><%=intl._t("Description")%>:</label>
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
                    
        <label><%=intl._t("New hidden service")%>:</label>
                    <select name="type">
                        <option value="httpserver">HTTP</option>
                        <option value="server"><%=intl._t("Standard")%></option>
                        <option value="httpbidirserver">HTTP bidir</option>
                        <option value="ircserver">IRC</option>
                        <option value="streamrserver">Streamr</option>
                    </select>
                    <input class="control" type="submit" value="<%=intl._t("Create")%>" />
                </div>
            </form>
        </div>
    </div>    


    <div id="localClientTunnelList" class="panel">
        <div class="header">
            
    <h4><%=intl._t("I2P Client Tunnels")%></h4>
        </div>

        
  <div class="separator"> </div>
        
        <div class="nameHeaderField rowItem">
            <label><%=intl._t("Name")%>:</label>
        </div>
        <div class="portHeaderField rowItem">
            <label><%=intl._t("Port")%>:</label>
        </div>
        <div class="typeHeaderField rowItem">
            <label><%=intl._t("Type")%>:</label>
        </div>
        <div class="interfaceHeaderField rowItem">
            <label><%=intl._t("Interface")%>:</label>
        </div>
        <div class="statusHeaderField rowItem">
            <label><%=intl._t("Status")%>:</label>
        </div>

        <div class="separator">
            <hr />
        </div>
        <%
        for (int curClient = 0; curClient < indexBean.getTunnelCount(); curClient++) {
            if (!indexBean.isClient(curClient)) continue;
      %>
        <div class="nameField rowItem">
            <label><%=intl._t("Name")%>:</label>
            <span class="text"><a href="edit?tunnel=<%=curClient%>" title="Edit Tunnel Settings for <%=indexBean.getTunnelName(curClient)%>"><%=indexBean.getTunnelName(curClient)%></a></span>
        </div>
        <div class="portField rowItem">
            <label><%=intl._t("Port")%>:</label>
            <span class="text">
         <%
               String cPort= indexBean.getClientPort2(curClient);
               out.write(cPort);
               if (indexBean.isSSLEnabled(curClient))
                   out.write(" SSL");
          %>
            </span>
        </div>
        <div class="typeField rowItem">
            <label><%=intl._t("Type")%>:</label>
            <span class="text"><%=indexBean.getTunnelType(curClient)%></span>
        </div>
        <div class="interfaceField rowItem">
            <label><%=intl._t("Interface")%>:</label>
            <span class="text">
         <%
               /* should only happen for streamr client */
               String cHost= indexBean.getClientInterface(curClient);
               if (cHost == null || "".equals(cHost)) {
                   out.write("<font color=\"red\">");
                   out.write(intl._t("Host not set"));
                   out.write("</font>");
               } else {
                   out.write(cHost);
               }
          %>
            </span>
        </div>
        <div class="statusField rowItem">
            <label><%=intl._t("Status")%>:</label>
            <%
            switch (indexBean.getTunnelStatus(curClient)) {
                case IndexBean.STARTING:
          %><div class="statusStarting text"><%=intl._t("Starting...")%></div>
            <a class="control" title="Stop this Tunnel" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._t("Stop")%></a>
        <%
                break;
                case IndexBean.STANDBY:
          %><div class="statusStarting text"><%=intl._t("Standby")%></div>
            <a class="control" title="Stop this Tunnel" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._t("Stop")%></a>
        <%
                break;
                case IndexBean.RUNNING:
          %><div class="statusRunning text"><%=intl._t("Running")%></div>
            <a class="control" title="Stop this Tunnel" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._t("Stop")%></a>
        <%
                break;
                case IndexBean.NOT_RUNNING:
          %><div class="statusNotRunning text"><%=intl._t("Stopped")%></div>
            <a class="control" title="Start this Tunnel" href="list?nonce=<%=nextNonce%>&amp;action=start&amp;tunnel=<%=curClient%>"><%=intl._t("Start")%></a>
        <%
                break;
            }
      %></div>

        <div class="destinationField rowItem">
            <label>
            <% if ("httpclient".equals(indexBean.getInternalType(curClient)) || "connectclient".equals(indexBean.getInternalType(curClient)) ||
                   "sockstunnel".equals(indexBean.getInternalType(curClient)) || "socksirctunnel".equals(indexBean.getInternalType(curClient))) { %>
                <%=intl._t("Outproxy")%>:
            <% } else { %>
                <%=intl._t("Destination")%>:
            <% } %>
            </label>
            <div class="text">
            <%
               if (indexBean.getIsUsingOutproxyPlugin(curClient)) {
                   %><%=intl._t("internal plugin")%><%
               } else {
                   String cdest = indexBean.getClientDestination(curClient);
                   if (cdest.length() > 70) { // Probably a B64 (a B32 is 60 chars) so truncate
                       %><%=cdest.substring(0, 45)%>&hellip;<%=cdest.substring(cdest.length() - 15, cdest.length())%><%
                   } else if (cdest.length() > 0) {
                       %><%=cdest%><%
                   } else {
                       %><i><%=intl._t("none")%></i><%
                   }
               } %>
            </div>
        </div>
        <% /* TODO SSL outproxy for httpclient if plugin not present */ %>

        <div class="descriptionField rowItem">
            <label><%=intl._t("Description")%>:</label>
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
                    
        <label><%=intl._t("New client tunnel")%>:</label>
                    <select name="type">
                        <option value="client"><%=intl._t("Standard")%></option>
                        <option value="httpclient">HTTP/CONNECT</option>
                        <option value="ircclient">IRC</option>
                        <option value="sockstunnel">SOCKS 4/4a/5</option>
                        <option value="socksirctunnel">SOCKS IRC</option>
                        <option value="connectclient">CONNECT</option>
                        <option value="streamrclient">Streamr</option>
                    </select>
                    <input class="control" type="submit" value="<%=intl._t("Create")%>" />
                </div>
            </form>
        </div>
    </div>
<%

  }  // isInitialized()

%>
    <div id="pageFooter">
    </div>
</body>
</html>
