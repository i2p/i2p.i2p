<%@page contentType="text/html" import="net.i2p.i2ptunnel.web.EditBean" %>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<% String tun = request.getParameter("tunnel");
   if (tun != null) {
    try {
      int curTunnel = Integer.parseInt(tun);
      if (EditBean.staticIsClient(curTunnel)) {
      %><jsp:include page="editClient.jsp" /><%
      } else {
      %><jsp:include page="editServer.jsp" /><%
      }
    } catch (NumberFormatException nfe) {
      %>Invalid tunnel parameter<%
    }
  } else {
    String type = request.getParameter("type");
    int curTunnel = -1;
    if ("client".equals(type) || "httpclient".equals(type)) {
      %><jsp:include page="editClient.jsp" /><%
    } else if ("server".equals(type) || "httpserver".equals(type)) {
      %><jsp:include page="editServer.jsp" /><%
    } else {
      %>Invalid tunnel type<%
    }
  }
%>