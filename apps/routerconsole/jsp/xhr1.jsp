<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<%
   // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
   if (request.getCharacterEncoding() == null)
       request.setCharacterEncoding("UTF-8");

   if (request.getParameter("i2p.contextId") != null) {
       session.setAttribute("i2p.contextId", request.getParameter("i2p.contextId"));
   }
%>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="intl" scope="request" />
<jsp:setProperty name="intl" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<%@include file="xhr1.jsi" %>
