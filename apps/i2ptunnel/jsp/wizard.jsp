<%
    // NOTE: Do the header carefully so there is no whitespace before the <?xml... line

%><%@page pageEncoding="UTF-8"
%><%@page contentType="text/html" import="net.i2p.i2ptunnel.web.WizardBean"
%><?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<jsp:useBean class="net.i2p.i2ptunnel.web.WizardBean" id="wizardBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.web.Messages" id="intl" scope="request" />
<% String pageStr = request.getParameter("page");
   int curPage = 1;
   if (pageStr != null) {
     try {
       curPage = Integer.parseInt(pageStr);
       if (curPage > 7 || curPage <= 0) {
         curPage = 1;
       }
     } catch (NumberFormatException nfe) {
       curPage = 1;
     }
   }
   boolean tunnelIsClient = wizardBean.getIsClient();
%>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
<head>
    <title><%=intl._("I2P Tunnel Manager - Tunnel Creation Wizard")%></title>
    
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=UTF-8" />
    <link href="/themes/console/images/favicon.ico" type="image/x-icon" rel="shortcut icon" />
    
    <% if (wizardBean.allowCSS()) {
  %><link href="<%=wizardBean.getTheme()%>default.css" rel="stylesheet" type="text/css" /> 
    <link href="<%=wizardBean.getTheme()%>i2ptunnel.css" rel="stylesheet" type="text/css" />
    <% }
  %>
</head>
<body id="tunnelWizardPage">
    <div id="pageHeader">
    </div>

    <form method="post" action="<%=(curPage == 7 ? "list" : "wizard") %>">

        <div id="wizardPanel" class="panel">
            <div class="header">
                <%
                if (curPage == 1) {
                  %><h4><%=intl._("Server or client tunnel?")%></h4><%
                } else if (curPage == 2) {
                  %><h4><%=intl._("Tunnel type")%></h4><%
                } else if (curPage == 3) {
                  %><h4><%=intl._("Tunnel name and description")%></h4><%
                } else if (curPage == 4 && tunnelIsClient) {
                  %><h4><%=intl._("Tunnel destination")%></h4><%
                } else if (curPage == 5) {
                  %><h4><%=intl._("Binding address and port")%></h4><%
                } else if (curPage == 6) {
                  %><h4><%=intl._("Tunnel auto-start")%></h4><%
                } else if (curPage == 7) {
                  %><h4><%=intl._("Wizard completed")%></h4><%
                } %>
                <input type="hidden" name="page" value="<%=request.getParameter("page")%>" />
                <input type="hidden" name="nonce" value="<%=wizardBean.getNextNonce()%>" />
            </div>

            <div class="separator">
                <hr />
            </div>

            <%
            if (curPage == 1) {
            %><div id="typeField" class="rowItem">
                <label><%=intl._("Server Tunnel")%></label>
                <input value="0" type="radio" id="baseType" name="isClient" class="tickbox" />
                <label><%=intl._("Client Tunnel")%></label>
                <input value="1" type="radio" id="baseType" name="isClient" class="tickbox" />
            </div><%
            } else {
            %><input type="hidden" name="isClient" value="<%=tunnelIsClient%>" /><%
            } %>

            <% if (curPage == 2) {
            %><div id="typeField" class="rowItem">
                <% if (tunnelIsClient) {
                %><select name="type">
                    <option value="client"><%=intl._("Standard")%></option>
                    <option value="httpclient">HTTP</option>
                    <option value="ircclient">IRC</option>
                    <option value="sockstunnel">SOCKS 4/4a/5</option>
                    <option value="socksirctunnel">SOCKS IRC</option>
                    <option value="connectclient">CONNECT</option>
                    <option value="streamrclient">Streamr</option>
                </select><%
                } else {
                %><select name="type">
                    <option value="server"><%=intl._("Standard")%></option>
                    <option value="httpserver">HTTP</option>
                    <option value="httpbidirserver">HTTP bidir</option>
                    <option value="ircserver">IRC</option>
                    <option value="streamrserver">Streamr</option>
                </select><%
                } %>
            </div><%
            } else {
            %><input type="hidden" name="type" value="<%=wizardBean.getType()%>" /><%
            } %>
        </div>

        <div id="globalOperationsPanel" class="panel">
            <div class="header"></div>
            <div class="footer">
                <div class=toolbox">
                    <button id="controlCancel" class="control" type="submit" name="action" value="" title="Cancel"><%=intl._("Cancel")%></button>
                    <% if (curPage == 7) {
                    %><button id="controlNext" accesskey="N" class="control" type="submit" name="action" value="Next page" title="Next Page"><%=intl._("Next")%>(<span class="accessKey">N</span>)</button><%
                    } else {
                    %><button id="controlFinish" accesskey="F" class="control" type="submit" name="action" value="Finish wizard" title="Finish Wizard"><%=intl._("Finish")%>(<span class="accessKey">F</span>)</button><%
                    } %>
                </div>
            </div>
        </div>

    </form>

    <div id="pageFooter">
    </div>
</body>
</html>
