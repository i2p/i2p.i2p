<%@page contentType="text/html" import="net.i2p.i2ptunnel.web.EditBean" %><% 
String tun = request.getParameter("tunnel");
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
  if (EditBean.isClient(type)) {
    %><jsp:include page="editClient.jsp" /><%
  } else {
    %><jsp:include page="editServer.jsp" /><%
  }
}
%>
