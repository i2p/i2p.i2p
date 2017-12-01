<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<%
   // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
   if (request.getCharacterEncoding() == null)
       request.setCharacterEncoding("UTF-8");
%>
<jsp:useBean class="net.i2p.router.web.helpers.SearchHelper" id="searchhelper" scope="request" />
<jsp:setProperty name="searchhelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<jsp:setProperty name="searchhelper" property="engine" value="<%=request.getParameter(\"engine\")%>" />
<jsp:setProperty name="searchhelper" property="query" value="<%=request.getParameter(\"query\")%>" />
<html><head></head><body><b>
<%
    String url = searchhelper.getURL();
    if (url != null) {
        response.setStatus(303);
        response.setHeader("Location", url);
%>
Searching...
<%
    } else {
        response.setStatus(403);
        String query = request.getParameter("query");
        if (query == null || query.trim().length() <= 0) {
%>
No search string specified!
<%
        } else if (request.getParameter("engine") == null) {
%>
No search engine specified!
<%
        } else {
%>
No search engines found!
<%
        }
    }
%>
</b></body></html>
