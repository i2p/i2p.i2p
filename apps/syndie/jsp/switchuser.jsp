<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.syndie.web.*" %><% 
request.setCharacterEncoding("UTF-8"); 
%><!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 TRANSITIONAL//EN" "http://www.w3c.org/TR/1999/REC-html401-19991224/loose.dtd">
<html>
<head>
<title>Syndie</title>
<link href="style.jsp" rel="stylesheet" type="text/css" >
</head>
<body>
<form action="threads.jsp" method="GET">
Syndie login: <input type="text" name="login" /><br />
Password: <input type="password" name="password" /><br />
<input type="submit" name="action" value="Login" />
<input type="submit" name="action" value="Cancel" />
</form>
</body>