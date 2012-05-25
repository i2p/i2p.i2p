<%@page contentType="text/html" import="net.i2p.i2ptunnel.web.IndexBean"%><?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<jsp:useBean class="net.i2p.i2ptunnel.web.IndexBean" id="indexBean" scope="request" />
<jsp:setProperty name="indexBean" property="*" />
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
<head>
    <title>I2PTunnel Webmanager - List</title>
    
    <meta htt
p-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=UTF-8" />
    
    <% if (indexBean.allowCSS()) {
  %><link href="/themes/console/images/favicon.ico" type="image/x-icon" rel="shortcut icon" />
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
            <h4>Status Messages</h4>
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
                <a class="control" href="index.jsp">Refresh</a>
            </div>
        </div>    
    </div>

    <div id="globalOperationsPanel" class="panel">
        <div class="header"></div>
        <div class="footer">
            <div class="toolbox">
                <a class="control" href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&amp;action=Stop%20all">Stop All</a> <a class="control" href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&amp;action=Start%20all">Start All</a> <a class="control" href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&amp;action=Restart%20all">Restart All</a> <a class="control" href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&amp;action=Reload%20configuration">Reload Config</a>
            </div>
        </div> 
    </div>



    <div id="localServerTunnelList" class="panel">
        <div class="header">
            
    <h4>I2P Server Tunnels</h4>
        </div>

        
  <div class="separator"> </div>

        <div class="nameHeaderField rowItem">
            <label>Name:</label>
        </div>
        <div class="previewHeaderField rowItem">
            <label>Points at:</label>
        </div>
        <div class="targetHeaderField rowItem">
            <label>Preview:</label>
        </div>
        <div class="statusHeaderField rowItem">
            <label>Status:</label>
<hr />        </div>
        
        <%
        for (int curServer = 0; curServer < indexBean.getTunnelCount(); curServer++) {
            if (indexBean.isClient(curServer)) continue;
            
      %>
        <div class="nameField rowItem">
            <label>Name:</label>
            <span class="text"><a href="edit.jsp?tunnel=<%=curServer%>" title="Edit Server Tunnel Settings for <%=indexBean.getTunnelName(curServer)%>"><%=indexBean.getTunnelName(curServer)%></a></span>
        </div>
        <div class="previewField rowItem">
            <label>Points at:</label>
            <span class="text">
        <%
            if ("httpserver".equals(indexBean.getInternalType(curServer))) {
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
            if ("httpserver".equals(indexBean.getInternalType(curServer)) && indexBean.getTunnelStatus(curServer) == IndexBean.RUNNING) {
          %><label>Preview:</label>    
            <a class="control" title="Test HTTP server through I2P" href="http://<%=indexBean.getDestHashBase32(curServer)%>.b32.i2p">Preview</a>     
            <%
            } else if (indexBean.getTunnelStatus(curServer) == IndexBean.RUNNING) {
          %><span class="text">Base32 Address:<br><%=indexBean.getDestHashBase32(curServer)%>.b32.i2p</span>
        <%
            } else {
          %><span class="comment">No Preview</span>
        <%
            }
      %></div>
        <div class="statusField rowItem">
            <label>Status:</label>
            <%
            switch (indexBean.getTunnelStatus(curServer)) {
                case IndexBean.STARTING:
          %><div class="statusStarting text">Starting...</div>    
            <a class="control" title="Stop this Tunnel" href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&amp;action=stop&amp;tunnel=<%=curServer%>">Stop</a>
        <%
                break;
                case IndexBean.RUNNING:
          %><div class="statusRunning text">Running</div>    
            <a class="control" title="Stop this Tunnel" href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&amp;action=stop&amp;tunnel=<%=curServer%>">Stop</a>
        <%
                break;
                case IndexBean.NOT_RUNNING:
          %><div class="statusNotRunning text">Stopped</div>    
            <a class="control" title="Start this Tunnel" href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&amp;action=start&amp;tunnel=<%=curServer%>">Start</a>
        <%
                break;
            }
      %></div>

        <div class="descriptionField rowItem">
            <label>Description:</label>
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
            <form id="addNewServerTunnelForm" action="edit.jsp"> 
            <div class="toolbox">
                    
        <label>New server tunnel:</label>
                    <select name="type">
                        <option value="server">Standard</option>
                        <option value="httpserver">HTTP</option>
                        <option value="ircserver">IRC</option>
                        <option value="streamrserver">Streamr</option>
                    </select>
                    <input class="control" type="submit" value="Create" />
                </div>
            </form>
        </div>
    </div>    


    <div id="localClientTunnelList" class="panel">
        <div class="header">
            
    <h4>I2P Client Tunnels</h4>
        </div>

        
  <div class="separator"> </div>
        
        <div class="nameHeaderField rowItem">
            <label>Name:</label>
        </div>
        <div class="portHeaderField rowItem">
            <label>Port:</label>
        </div>
        <div class="typeHeaderField rowItem">
            <label>Type:</label>
        </div>
        <div class="interfaceHeaderField rowItem">
            <label>Interface:</label>
        </div>
        <div class="statusHeaderField rowItem">
            <label>Status:</label>
        </div>

        <div class="separator">
            <hr />
        </div>
        <%
        for (int curClient = 0; curClient < indexBean.getTunnelCount(); curClient++) {
            if (!indexBean.isClient(curClient)) continue;
      %>
        <div class="nameField rowItem">
            <label>Name:</label>
            <span class="text"><a href="edit.jsp?tunnel=<%=curClient%>" title="Edit Tunnel Settings for <%=indexBean.getTunnelName(curClient)%>"><%=indexBean.getTunnelName(curClient)%></a></span>
        </div>
        <div class="portField rowItem">
            <label>Port:</label>
            <span class="text"><%=indexBean.getClientPort(curClient)%></span>
        </div>
        <div class="typeField rowItem">
            <label>Type:</label>
            <span class="text"><%=indexBean.getTunnelType(curClient)%></span>
        </div>
        <div class="interfaceField rowItem">
            <label>Interface:</label>
            <span class="text"><%=indexBean.getClientInterface(curClient)%></span>
        </div>
        <div class="statusField rowItem">
            <label>Status:</label>
            <%
            switch (indexBean.getTunnelStatus(curClient)) {
                case IndexBean.STARTING:
          %><div class="statusStarting text">Starting...</div>
            <a class="control" title="Stop this Tunnel" href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&amp;action=stop&amp;tunnel=<%=curClient%>">Stop</a>
        <%
                break;
                case IndexBean.STANDBY:
          %><div class="statusStarting text">Standby</div>
            <a class="control" title="Stop this Tunnel" href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&amp;action=stop&amp;tunnel=<%=curClient%>">Stop</a>
        <%
                break;
                case IndexBean.RUNNING:
          %><div class="statusRunning text">Running</div>
            <a class="control" title="Stop this Tunnel" href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&amp;action=stop&amp;tunnel=<%=curClient%>">Stop</a>
        <%
                break;
                case IndexBean.NOT_RUNNING:
          %><div class="statusNotRunning text">Stopped</div>
            <a class="control" title="Start this Tunnel" href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&amp;action=start&amp;tunnel=<%=curClient%>">Start</a>
        <%
                break;
            }
      %></div>

      <% if (!"sockstunnel".equals(indexBean.getInternalType(curClient))) { %>
        <div class="destinationField rowItem">
            <label>
            <% if ("httpclient".equals(indexBean.getInternalType(curClient)) || "connectclient".equals(indexBean.getInternalType(curClient))) { %>
                Outproxy:
            <% } else { %>
                Destination:
            <% } %>
            </label>
            <input class="freetext" size="40" readonly="readonly" value="<%=indexBean.getClientDestination(curClient)%>" />
        </div>
      <% } %>

        <div class="descriptionField rowItem">
            <label>Description:</label>
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
            <form id="addNewClientTunnelForm" action="edit.jsp">
                <div class="toolbox">
                    
        <label>New client tunnel:</label>
                    <select name="type">
                        <option value="client">Standard</option>
                        <option value="httpclient">HTTP</option>
                        <option value="ircclient">IRC</option>
                        <option value="sockstunnel">SOCKS 4/4a/5</option>
                        <option value="connectclient">CONNECT</option>
                        <option value="streamrclient">Streamr</option>
                    </select>
                    <input class="control" type="submit" value="Create" />
                </div>
            </form>
        </div>
    </div>
    <div id="pageFooter">
    </div>
</body>
</html>
