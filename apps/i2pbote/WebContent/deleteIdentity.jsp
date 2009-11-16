<%@ page language="java" contentType="text/html; charset=UTF-8"
    pageEncoding="UTF-8"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">

<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="ib" uri="I2pBoteTags" %>

<c:set var="errorMessage" value="${ib:deleteIdentity(param.key)}"/>

<c:if test="${empty errorMessage}">
    <jsp:forward page="identities.jsp">
        <jsp:param name="message" value="The email identity has been deleted."/>
    </jsp:forward>
</c:if>
<c:if test="${!empty errorMessage}">
    <jsp:include page="header.jsp"/>
    <div class="main">
        Error: ${errorMessage}
    </div>
    <jsp:include page="footer.jsp"/>
</c:if>