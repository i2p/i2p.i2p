<%@page contentType="text/css; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.util.FileUtil" %>
<% request.setCharacterEncoding("UTF-8"); %>
<%@include file="syndie.css" %>
<% 
String content = FileUtil.readTextFile("./docs/syndie_standard.css", -1, true); 
if (content != null) out.write(content);
%>