<%@ page language="java" contentType="text/html; charset=UTF-8"
    pageEncoding="UTF-8"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">

<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="ib" uri="I2pBoteTags" %>

<c:choose>
    <c:when test="${param.new}">
        <c:set var="title" value="New Email Identity"/>
        <c:set var="commitAction" value="Create"/>
    </c:when>
    <c:otherwise>
        <c:set var="title" value="Edit Email Identity"/>
        <c:set var="commitAction" value="Save"/>
    </c:otherwise>
</c:choose>

<jsp:include page="header.jsp">
    <jsp:param name="title" value="${title}"/>
</jsp:include>

<div class="errorMessage">
    ${param.errorMessage}
</div>

<div class="main">
    <form name="form" action="saveIdentity.jsp">
        <table>
            <tr>
                <td>
                    <div style="font-weight: bold;">Public Name:</div>
                    <div style="font-size: 0.8em;">(required field, shown to recipients)</div>
                </td>
                <td>
                    <input type="text" size="25" name="publicName" value="${param.publicName}"/>
                </td>
            </tr>
            <tr>
                <td>
                    <div style="font-weight: bold;">Description:</div>
                    <div style="font-size: 0.8em;">(optional, kept private)</div>
                </td>
                <td>
                    <input type="text" size="25" name="description" value="${param.description}"/>
                </td>
            </tr>
            <tr>
                <td>
                    <div style="font-weight: bold;">Email Address:</div>
                    <div style="font-size: 0.8em;">(optional)</div>
                </td>
                <td>
                    <input type="text" size="50" name="emailAddress" value="${param.emailAddress}"/>
                </td>
            </tr>
            <c:if test="${!empty param.key}">
            <tr>
                <td style="font-weight: bold; vertical-align: top;">
                    Email Destination:
                </td>
                <td style="font-family: monospace; padding-left: 5px">
                    <c:set var="key" value="${param.key}"/>
                    <c:forEach var="i" begin="0" end="${fn:length(key)}" step="64">
                        ${fn:substring(key, i, i+64)}<br/>
                    </c:forEach>
                    <input type="hidden" name="key" value="${param.key}"/>
                </td>
            </tr>
            </c:if>
        </table>
        <input type="submit" name="action" value="${commitAction}"/>
        <input type="submit" name="action" value="Cancel"/>
    </form>

    <script type="text/javascript" language="JavaScript">
        document.forms['form'].elements['name'].focus();
    </script>
</div>

<jsp:include page="footer.jsp"/>