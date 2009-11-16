<%@ page language="java" contentType="text/html; charset=UTF-8"
    pageEncoding="UTF-8"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">

<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="ib" uri="I2pBoteTags" %>

<jsp:include page="header.jsp">
    <jsp:param name="title" value="${param.path}"/>
</jsp:include>

This is the <b>${param.path}</b> folder.

<table>
    <tr>
        <th>Sender</th><th>Subject</th><th>Sent Date</th>
    </tr>
    <c:set var="folderName" value="Inbox"/>
    <c:forEach items="${ib:getMailFolder(folderName).elements}" var="email">
        <tr>
            <td>${email.sender}</td><td>${email.subject}</td><td>${email.sentDate}</td>
        </tr>
    </c:forEach>
</table>

<jsp:include page="footer.jsp"/>