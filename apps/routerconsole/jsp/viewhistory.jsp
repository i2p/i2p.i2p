<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Change Log")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<h1>I2P <%=intl._t("Change Log")%></h1>
<div class="main" id="changelog">
<%
    java.io.File base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir();
    java.io.File file = new java.io.File(base, "history.txt");
    if (file.canRead()) {
        java.io.FileInputStream fis = null;
        java.io.BufferedReader in = null;
        try {
            fis = new java.io.FileInputStream(file);
            in = new java.io.BufferedReader(new java.io.InputStreamReader(fis, "UTF-8"));
            String line = null;
            while ( (line = in.readLine()) != null) {
                if (line.length() == 0)
                    continue;
                if (line.endsWith(" released")) {
                    out.write("<h2>");
                    if (line.startsWith(" * "))   // some do, some don't
                        line = line.substring(3);
                    out.write(line.replace("released", intl._t("Released")));
                    out.println("</h2>");
                } else if (line.startsWith("20")) {
                    out.write("<h3>");
                    out.write(line);
                    out.println("</h3>");
                } else if (line.startsWith(" * ")) {
                    out.write("<p><span class=\"star\">⭐</span> ");
                    out.write(line, 3, line.length() - 3);
                    out.println();
                } else if (line.startsWith("   - ")) {
                    out.write("<br><span class=\"bullet\">🔹</span> ");
                    out.write(line, 5, line.length() - 5);
                    out.println();
                } else if (line.startsWith("---------------")) {
                    out.println("<hr>");
                } else if (line.startsWith("EARLIER HISTORY IS AVAILABLE IN THE SOURCE PACKAGE")) {
                    out.print("<a href=\"https://git.idk.i2p/I2P_Developers/i2p.i2p/raw/branch/master/history.txt\">");
                    out.print(intl._t("Earlier history is available in the source package"));
                    out.println("</a>");
                } else {
                    out.write("<br><span class=\"bullet\"></span>");  // indent same as bullet
                    out.println(line);
                }
            }
        } finally {
            if (in != null) in.close();
        }
    } else {
        %>Changelog not available<%
    }
%>
</div></body></html>
