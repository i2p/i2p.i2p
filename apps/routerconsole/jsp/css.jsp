<%
   /*
    * This should be included inside <head>...</head>,
    * as it sets the stylesheet.
    */

   response.setHeader("Pragma", "no-cache");
   response.setHeader("Cache-Control","no-cache");
   response.setDateHeader("Expires", 0);
   // the above will b0rk if the servlet engine has already flushed
   // the response prior to including this file, so it should be 
   // near the top
   
   if (request.getParameter("i2p.contextId") != null) {
       session.setAttribute("i2p.contextId", request.getParameter("i2p.contextId")); 
   }
%>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="cssHelper" scope="request" />
<jsp:setProperty name="cssHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<link href="<%=cssHelper.getTheme(request.getHeader("User-Agent"))%>console.css" rel="stylesheet" type="text/css" /> 
<!--[if IE]><link href="/themes/console/classic/ieshim.css" rel="stylesheet" type="text/css" /><![endif]--> 