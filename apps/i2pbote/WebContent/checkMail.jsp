<%@ page language="java" contentType="text/html; charset=UTF-8"
    pageEncoding="UTF-8"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">

<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="ib" uri="I2pBoteTags" %>

<c:choose>
    <c:when test="${empty ib:getIdentities().all}">
        <jsp:forward page="noIdentities.jsp"/>
    </c:when>
    <c:otherwise>
        <ib:checkForMail/>
        <c:if test="${empty param.nextPage}">
            <c:set var="param.nextPage" value="index.jsp"/>
        </c:if>
        <jsp:forward page="${param.nextPage}"/>
    </c:otherwise>
</c:choose>

<p>
TODO checkMailTag, show status at the bottom (or top?),
point the Inbox link here or just check periodically and when the Check Email button is clicked?
</p>

<jsp:include page="footer.jsp"/>