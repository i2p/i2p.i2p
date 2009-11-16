<%@ page language="java" contentType="text/html; charset=UTF-8"
    pageEncoding="UTF-8"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">

<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="ib" uri="I2pBoteTags" %>


<c:if test="${param.action == 'Cancel'}">
    <jsp:forward page="identities.jsp"/>
</c:if>

<c:choose>
    <c:when test="${empty param.publicName}">
        <c:set var="errorMessage" value="Please fill in the Public Name field."/>
    </c:when>
    <c:otherwise>
        <c:set var="errorMessage" value="${ib:saveIdentity(param.key, param.publicName, param.description, param.emailAddress)}"/>
    </c:otherwise>
</c:choose>

<c:if test="${empty errorMessage}">
    <jsp:forward page="identities.jsp">
        <jsp:param name="infoMessage" value="The email identity has been saved."/>
    </jsp:forward>
</c:if>
<c:if test="${!empty errorMessage}">
    <jsp:forward page="editIdentity.jsp">
        <jsp:param name="errorMessage" value="${errorMessage}"/>
    </jsp:forward>
</c:if>