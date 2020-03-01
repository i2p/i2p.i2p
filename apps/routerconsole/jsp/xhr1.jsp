<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<%
   // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
   if (request.getCharacterEncoding() == null)
       request.setCharacterEncoding("UTF-8");

   String i2pcontextId = request.getParameter("i2p.contextId");
   try {
       if (i2pcontextId != null) {
           session.setAttribute("i2p.contextId", i2pcontextId);
       } else {
           i2pcontextId = (String) session.getAttribute("i2p.contextId");
       }
   } catch (IllegalStateException ise) {}
   // Browser should not load this directly
   response.setHeader("X-Frame-Options", "DENY");
   response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'none'; script-src 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'; media-src 'none'");
   response.setHeader("X-XSS-Protection", "1; mode=block");
   response.setHeader("X-Content-Type-Options", "nosniff");
%>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="intl" scope="request" />
<jsp:setProperty name="intl" property="contextId" value="<%=i2pcontextId%>" />
<jsp:useBean class="net.i2p.router.web.NewsHelper" id="newshelper" scope="request" />
<jsp:setProperty name="newshelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getRouterDir(), "docs/news.xml");
%>
 <jsp:setProperty name="newshelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="newshelper" property="maxLines" value="300" />
<%@include file="xhr1.jsi" %>
