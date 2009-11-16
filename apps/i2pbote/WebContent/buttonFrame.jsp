<%@ page language="java" contentType="text/html; charset=UTF-8"
    pageEncoding="UTF-8"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">

<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="ib" uri="I2pBoteTags" %>

<c:if test="${ib:isCheckingForMail()}">
    <c:set var="checkingForMail" value="true"/>
</c:if>

<html>
<head>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
    <link rel="stylesheet" href="i2pbote.css" />
    <c:if test="${checkingForMail}">
        <meta http-equiv="refresh" content="20" />
    </c:if>
</head>

<body style="background-color: transparent; margin: 0px;">

<table><tr>
    <td>
        <c:if test="${checkingForMail}">
            <div class="checkmail">
                <img src="images/wait.gif"/>Checking for mail...
            </div>
        </c:if>
        <c:if test="${!checkingForMail}">
            <div class="checkmail">
                <form action="checkMail.jsp" target="_top" method="GET">
                    <input type="hidden" name="path" value="Inbox"/>
                    <button type="submit" value="Check Mail">Check Mail</button>
                </form>
            </div>
        </c:if>
    </td>
    <td>
        <form action="newEmail.jsp" target="_top" method="GET">
            <button type="submit" value="New">New</button>
        </form>
    </td>
</tr></table>

</body>
</html>