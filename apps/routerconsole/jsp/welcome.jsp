<%@page contentType="text/html"%><%@page pageEncoding="UTF-8"%><jsp:useBean class="net.i2p.router.web.helpers.WizardHelper" id="wizhelper" scope="session" /><%
    // note that for the helper we use a session scope, not a request scope,
    // so that we can access the NDT test results.
    // The MLabHelper singleton will prevent multiple simultaneous tests, even across sessions.

    // page ID
    final int LAST_PAGE = 6;
    String pg = request.getParameter("page");
    int ipg;
    if (pg == null) {
        ipg = 1;
    } else {
        try {
            ipg = Integer.parseInt(pg);
            if (request.getParameter("prev") != null) {
                // previous button handling
                if (ipg == 5)
                    ipg = 2;
                else
                    ipg -= 2;
            }
            if (ipg <= 0 || ipg > LAST_PAGE) {
                ipg = 1;
            } else if (ipg == 3 && request.getParameter("skipbw") != null) {
                ipg++;  // skip bw test
            }
        } catch (NumberFormatException nfe) {
            ipg = 1;
        }
    }

    // detect completion
    boolean done = request.getParameter("done") != null || request.getParameter("skip") != null;
    if (done) {
        // tell wizard helper we're done
        String i2pcontextId = request.getParameter("i2p.contextId");
        try {
            if (i2pcontextId != null) {
                session.setAttribute("i2p.contextId", i2pcontextId);
            } else {
                i2pcontextId = (String) session.getAttribute("i2p.contextId");
            }
        } catch (IllegalStateException ise) {}
        wizhelper.setContextId(i2pcontextId);
        wizhelper.complete();

        // redirect to /home
        response.setStatus(307);
        response.setHeader("Cache-Control","no-cache");
        String req = request.getRequestURL().toString();
        int slash = req.indexOf("/welcome");
        if (slash >= 0)
            req = req.substring(0, slash) + "/home";
        else // shouldn't happen
            req = "http://127.0.0.1:7657/home";
        response.setHeader("Location", req);
        // force commitment
        response.getOutputStream().close();
        return;
    }
%><!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("New Install Wizard")%>
<%
    wizhelper.setContextId(i2pcontextId);
    if (ipg == 3) {
%>
<script src="/js/welcomeajax.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" type="text/javascript">
  var failMessage = "<b><%=intl._t("Router is down")%><\/b>";
  var progressMessage = "<b><%=intl._t("Bandwidth test in progress...")%><\/b>";
  var doneMessage = "<b><%=intl._t("Bandwidth test is complete, click Next")%><\/b>";
  function requestAjax1() { ajax("/welcomexhr1.jsp", "xhr", "1000"); }
  function initAjax() {
     document.getElementById("xhr").innerHTML = progressMessage;
     setTimeout(requestAjax1, "1000");
  }
  document.addEventListener("DOMContentLoaded", function() {
      initAjax();
  }, true);
</script>
<%
    }  // ipg == 3
%>
</head><body>
<div id="wizard" class="overlay">

<jsp:useBean class="net.i2p.router.web.helpers.WizardHandler" id="formhandler" scope="request" />

<%
    // Bind the session-scope Helper to the request-scope Handler
    formhandler.setWizardHelper(wizhelper);
%>
<div class="wizardnotice">
<%@include file="formhandler.jsi" %>
</div>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>">
<input type="hidden" name="action" value="blah" >
<input type="hidden" name="page" value="<%=(ipg + 1)%>" >
<%
    if (ipg == 1) {
        // language selection
%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHelper" id="uihelper" scope="request" />
<jsp:setProperty name="uihelper" property="contextId" value="<%=i2pcontextId%>" />
<%-- needed for CSS: --%><div id="config_ui">
<%-- needed for lang setting in css.jsi: --%><input type="hidden" name="consoleNonce" value="<%=net.i2p.router.web.CSSHelper.getNonce()%>" >
<img class="wizard progress" src="/themes/console/images/wizard/wizardlogo.png">
<h3 id="wizardheading" class="wizard"><%=uihelper._t("Select Language")%></h3>
<img class="wizardimg" src="/themes/console/images/wizard/step-0.png">
<div class="clickableProgression">
<span class="currentProgression">&#x2B24;</span>
<span class="unvisitedProgression">&#x25EF;</span>
<span class="unvisitedProgression">&#x25EF;</span>
<span class="unvisitedProgression">&#x25EF;</span>
<span class="unvisitedProgression">&#x25EF;</span>
<span class="unvisitedProgression">&#x25EF;</span>
<span class="unvisitedProgression">&#x25EF;</span>
</div>
<div id="wizlangsettings" class="wizard">
<jsp:getProperty name="uihelper" property="langSettings" />
</div>
<div class="wizardtext">
<p>
<%=intl._t("Please select your preferred language")%>
</p>
<p>
<%=intl._t("This project is supported by Transifex and OTF through the Localization Lab.")%>
<%=intl._t("Thank you to all of the volunteers all over the world who help keep our project")%>
<%=intl._t("accessible. I2P is always looking for support to keep its documentation up to date,")%>
<a href="https://www.transifex.com/projects/p/I2P/" target="_blank"><%=intl._t("please consider supporting our translation efforts.")%></a>
</p>
</div>
</div>
<%

    } else if (ipg == 2) {
        // Overview of bandwidth test
%>
<img class="wizard progress" src="/themes/console/images/wizard/wizardlogo.png">
<h3 id="wizardheading" class="wizard"><%=intl._t("Bandwidth Test")%></h3>
<img class="wizardimg" src="/themes/console/images/wizard/step-2.png">
<div class="clickableProgression">
<span class="visitedProgression">&#x25EF;</span>
<span class="currentProgression">&#x2B24;</span>
<span class="unvisitedProgression">&#x25EF;</span>
<span class="unvisitedProgression">&#x25EF;</span>
<span class="unvisitedProgression">&#x25EF;</span>
<span class="unvisitedProgression">&#x25EF;</span>
</div>
<div class="wizardtext">
<p>
<%=intl._t("I2P will now test your internet connection to identify the optimal speed settings.")%>
<%=intl._t("Bandwidth participation improves the anonymity level of all users on the network and maximizes your download speed.")%>
<%=intl._t("This is done using the third-party M-Lab service.")%>
<%=intl._t("During this time you will be connected directly to M-Lab's service, with your real IP address.")%>
</p><p>
<%=intl._t("Please review the M-Lab privacy policies linked below.")%>
<%=intl._t("If you do not wish to run the M-Lab bandwidth test, you may skip it by clicking the button below.")%>
</p><p>
<a href="https://www.measurementlab.net/privacy/" target="_blank"><%=intl._t("M-Lab Privacy Policy")%></a>
<br><a href="https://github.com/m-lab/mlab-ns/blob/master/MLAB-NS_PRIVACY_POLICY.md" target="_blank"><%=intl._t("M-Lab Name Server Privacy Policy")%></a>
</p>
</div>
<%

    } else if (ipg == 3) {
        // Bandwidth test in progress (w/ AJAX)
%>
<img class="wizard progress" src="/themes/console/images/wizard/wizardlogo.png">
<h3 id="wizardheading" class="wizard"><%=intl._t("Bandwidth Test in Progress")%></h3>
<img class="wizardimg" src="themes/console/images/wizard/step-3.png">
<div class="clickableProgression">
<span class="visitedProgression">&#x25EF;</span>
<span class="visitedProgression">&#x25EF;</span>
<span class="currentProgression">&#x2B24;</span>
<span class="unvisitedProgression">&#x25EF;</span>
<span class="unvisitedProgression">&#x25EF;</span>
<span class="unvisitedProgression">&#x25EF;</span>
</div>
<div id="xhr" class="notifcation">
<!-- for non-script -->
<%=intl._t("Javascript is disabled - wait 60 seconds for the bandwidth test to complete and then click Next")%>
</div>
<div id="xhr2" class="notification">
</div>
<div class="wizardtext">
<p>
<%=intl._t("While the I2P router software is getting ready to go, it's testing your bandwidth, obtaining an initial")%>
<%=intl._t("set of peers from a reseed server, making it's first few exploratory peer connections, and getting.")%>
<%=intl._t("integrated with the rest of the network.")%>
</p>
<p>
<%=intl._t("To learn more about these topics and what functions they perform for I2P, you can visit our")%>
<a href="http://geti2p.net" target="_blank"><%=intl._t("web site")%></a><%=intl._t(", our")%>
<a href="http://wiki.i2p-projekt.i2p" target="_blank"><%=intl._t("wiki")%></a><%=intl._t(", or contact us on our")%>
<a href="http://i2pforum.net" target="_blank"><%=intl._t("forums")%></a>
<%=intl._t("to get help from the community.")%>
</p>
</div>
<%

    } else if (ipg == 4) {
        // Bandwidth test results
        // and/or manual bw entry?
%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=i2pcontextId%>" />
<%
        if (request.getParameter("skipbw") == null) {
            // don't display this if we skipped the test
%>
<img class="wizard progress" src="/themes/console/images/wizard/wizardlogo.png">
<h3 id="wizardheading" class="wizard bwtest"><%=intl._t("Bandwidth Test Results")%></h3>
<div class="clickableProgression">
<span class="visitedProgression">&#x25EF;</span>
<span class="visitedProgression">&#x25EF;</span>
<span class="visitedProgression">&#x25EF;</span>
<span class="currentProgression">&#x2B24;</span>
<span class="unvisitedProgression">&#x25EF;</span>
<span class="unvisitedProgression">&#x25EF;</span>
</div>
<table class="mlabtable">
<tr><td><%=intl._t("Test server location")%></td><td colspan="3"><%=wizhelper.getServerLocation()%></td></tr>
<tr><td><%=intl._t("Completion status")%></td><td colspan="3"><%=wizhelper.getCompletionStatus()%></td></tr>
<%
            if (wizhelper.isNDTSuccessful()) {
                // don't display this if test failed
%>
<tr><td><%=intl._t("Details")%></td><td colspan="3"><%=wizhelper.getDetailStatus()%></td></tr>
<tr><td><%=intl._t("Downstream Bandwidth")%></td><td><%=net.i2p.data.DataHelper.formatSize2Decimal(wizhelper.getDownBandwidth())%>Bps</td>
<td><%=intl._t("Upstream Bandwidth")%></td><td><%=net.i2p.data.DataHelper.formatSize2Decimal(wizhelper.getUpBandwidth())%>Bps</td></tr>
<tr><td><%=intl._t("Share of Bandwidth for I2P")%></td><td><%=Math.round(net.i2p.router.web.helpers.WizardHelper.BW_SCALE * 100)%>%</td></tr>
<%
            } // sucessful
%>
</table>
<%
        } // skipbw
%>
<img class="wizard progress" src="/themes/console/images/wizard/wizardlogo.png">
<h3 id="wizardheading" class="wizard"><%=intl._t("Bandwidth Configuration")%></h3>
<img class="wizardimg" src="/themes/console/images/wizard/step-4.png">
<style>
.bwtest {
    display: none;
}
</style>
<table id="bandwidthconfig" class="configtable wizardtable">
<tr><td class="infohelp infodiv" colspan="2">
<%=intl._t("I2P will work best if you configure your rates to match the speed of your internet connection.")%>
</td></tr>
<tr><td><input style="text-align: right; width: 5em;" name="inboundrate" type="text" size="5" maxlength="5" value="<jsp:getProperty name="wizhelper" property="inboundBurstRate" />" >
<%=intl._t("KBps In")%>
</td><td>(<jsp:getProperty name="wizhelper" property="inboundBurstRateBits" />)</td>
</tr>
<tr>
<%-- display burst, set standard, handler will fix up --%>
<td><input style="text-align: right; width: 5em;" name="outboundrate" type="text" size="5" maxlength="5" value="<jsp:getProperty name="wizhelper" property="outboundBurstRate" />" >
<%=intl._t("KBps Out")%>
</td><td>(<jsp:getProperty name="wizhelper" property="outboundBurstRateBits" />)</td>
</tr>
<tr>
<td><jsp:getProperty name="nethelper" property="sharePercentageBox" /> <%=intl._t("Share")%></td>
<td>(<jsp:getProperty name="wizhelper" property="shareRateBits" />)
</td></tr>
</table>
<div id="infodiv">
<% int share = Math.round(wizhelper.getShareBandwidth() * 1.024f);
    if (share < 12) {
        out.print("<b>");
        out.print(intl._t("NOTE"));
        out.print("</b>: ");
        out.print(intl._t("You have configured I2P to share only {0} KBps.", share));
        out.print("</br>");

        out.print(intl._t("I2P requires at least 12KBps to enable sharing. "));
        out.print(intl._t("By donating your bandwidth to participating traffic, you not only help others, you improve "));
        out.print(intl._t("your own anonymity by creating cover traffic."));
        out.print("</br>");
        out.print(intl._t("We recommend sharing 75% or more for I2P, but you can adjust based on your needs."));
    } else {
        out.print(intl._t("You have configured I2P to share {0} KBps.", share));
        out.print("</br>");

        out.print(intl._t("By donating your bandwidth to participating traffic, you not only help others, you improve "));
        out.print(intl._t("your own anonymity by creating cover traffic."));
        out.print("</br>");
        out.print(intl._t("We recommend sharing 75% or more for I2P, but you can adjust based on your needs."));
    }
%>
</div>
<%

    } else if (ipg == 5) {
        // Browser setup
%>
<img class="wizard progress" src="/themes/console/images/wizard/wizardlogo.png">
<h3 id="wizardheading" class="wizard"><%=intl._t("Browser and Application Setup")%></h3>
<img class="wizardimg" src="/themes/console/images/wizard/step-5.png">
<div class="clickableProgression">
<span class="visitedProgression">&#x25EF;</span>
<span class="visitedProgression">&#x25EF;</span>
<span class="visitedProgression">&#x25EF;</span>
<span class="visitedProgression">&#x25EF;</span>
<span class="currentProgression">&#x2B24;</span>
<span class="unvisitedProgression">&#x25EF;</span>
</div>
<div class="wizardtext"><p>
<%=intl._t("Your browser needs to be configured to work with I2P.")%>
<%=intl._t("We have instructions for configuring both Firefox and Chromium based browsers with I2P.")%>
<a href="https://geti2p.net/htproxyports" target="_blank"><%=intl._t("You can find these instructions on our website")%></a>.
<%
        if (net.i2p.util.SystemVersion.isWindows()) {
%>
</p><p>
<%=intl._t("Otherwise, the recommended way to browse I2P websites is with a separate profile in the Firefox browser.")%>
<ol><li><a href="https://www.mozilla.org/firefox/" target="_blank"><%=intl._t("Install Firefox")%></a>
</li><li><a href="https://geti2p.net/firefox" target="_blank"><%=intl._t("Install the I2P Firefox profile")%></a>
</li></ol>
<%
        } //isWindows()
%>
</p>
<p>
<%=intl._t("The I2P router also comes with it's own versions of common, useful internet applications like")%>
<a href="/torrents" target="_blank"><%=intl._t("bittorrent")%></a>
<%=intl._t("and")%>
<a href="/webmail" target="_blank"><%=intl._t("e-mail")%></a>
<%=intl._t("so you can keep doing the things you need to do.")%>
<%=intl._t("These applications work with I2P automatically and require no additional configuration.")%>
</p>
<p>
<%=intl._t("Normally, I2P addresses are long and random. To help make I2P addresses easier to read and remember,")%>
<%=intl._t("the I2P router also includes it's own built-in")%>
<a href="/dns" target="_blank"><%=intl._t("Address Book")%></a>
<%=intl._t("so you can")%>
<%=intl._t("privately assign human-readable names to the sites you like to visit.")%>
<%=intl._t("The Address Book can also be used to subscribe to lists of URL's shared by other I2P users who choose")%>
<%=intl._t("to distribute their own lists of hostnames and addresses.")%>
</p>
</div>
<%

    } else if (ipg == LAST_PAGE) {
        // Done
%>
<img class="wizard progress" src="/themes/console/images/wizard/wizardlogo.png">
<h3 id="wizardheading" class="wizard"><%=intl._t("Welcome to I2P!")%></h3>
<img class="wizardimg" src="/themes/console/images/wizard/step-6.png">
<div class="clickableProgression">
<span class="visitedProgression">&#x25EF;</span>
<span class="visitedProgression">&#x25EF;</span>
<span class="visitedProgression">&#x25EF;</span>
<span class="visitedProgression">&#x25EF;</span>
<span class="visitedProgression">&#x25EF;</span>
<span class="currentProgression">&#x2B24;</span>
</div>
<div class="wizardtext">
<p>
<%=intl._t("When you start I2P for the first time, it needs need to integrate with the I2P network. It may also")%>
<%=intl._t("indicate that it is temporarily \"Rejecting Tunnels.\" This is a normal part of the process. Please be patient.")%>
</p><p>
<%=intl._t("Green circles in the sidebar area of the I2P Router Console indicate that you are ready to use I2P")%>
<%=intl._t("for browsing or applications.")%>
</p>
</div>
<%

    } else {
%>
<table class="configtable wizard"><tr><td>unknown wizard page</td></tr></table>
<%
    }
%>
<div class="wizardbuttons wizard">
<table class="configtable wizard"><tr class="wizard"><td class="optionsave wizard">
<%
    if (ipg != 1) {
%>
<input type="submit" class="back wizardbutton" name="prev" value="<%=intl._t("Previous")%>" >
<%
    }
    if (ipg != LAST_PAGE) {
%>
<input type="submit" class="cancel wizardbutton" name="skip" value="<%=intl._t("Skip Setup")%>" >
<%
        if (ipg == 2) {
%>
<input type="submit" class="cancel wizardbutton" name="skipbw" value="<%=intl._t("Skip Bandwidth Test")%>" >
<%
        } else if (ipg == 3) {
%>
<input type="submit" class="cancel wizardbutton" name="cancelbw" value="<%=intl._t("Cancel Bandwidth Test")%>" >
<%
        }
%>
<input type="submit" class="go wizardbutton" name="next" value="<%=intl._t("Next")%>" >
<%
    } else {
%>
<input type="submit" class="accept wizardbutton" name="done" value="<%=intl._t("Finished")%>" >
<%
    }
%>
</td></tr></table></div>
</form>
</div></body></html>
