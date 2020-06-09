<%@page contentType="text/html"%><%@page pageEncoding="UTF-8"%><jsp:useBean class="net.i2p.router.web.helpers.WizardHelper" id="wizhelper" scope="session" /><%
    // note that for the helper we use a session scope, not a request scope,
    // so that we can access the NDT test results.
    // The MLabHelper singleton will prevent multiple simultaneous tests, even across sessions.

    // page ID
    final int LAST_PAGE = 7;
    String pg = request.getParameter("page");
    int ipg;
    if (pg == null) {
        ipg = 1;
    } else {
        try {
            ipg = Integer.parseInt(pg);
            if (request.getParameter("prev") != null) {
                // previous button handling
                if (ipg == 6)
                    ipg = 3;
                else
                    ipg -= 2;
            }
            if (ipg <= 0 || ipg > LAST_PAGE) {
                ipg = 1;
            } else if (ipg == 4 && request.getParameter("skipbw") != null) {
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
    if (ipg == 4) {
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
    }  // ipg == 4
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
<img class="wizard progress" src="/themes/console/images/wizard/logogrey1.png">
<h3 id="wizardheading" class="wizard"><%=uihelper._t("Router Console Language")%></h3>
<img class="wizardimg" src="/themes/console/images/wizard/undraw_globe.png">
<div id="wizlangsettings" class="wizard">
<jsp:getProperty name="uihelper" property="langSettings" />
</div>
</div>
<%

    } else if (ipg == 2) {
        // I2P Philosophy
        // license info?
%>
<img class="wizard progress" src="/themes/console/images/wizard/logogrey2.png">
<h3 id="wizardheading" class="wizard"><%=intl._t("Why I2P?")%></h3>
<img class="wizardimg" src="/themes/console/images/wizard/undraw_connected_world.png">
<div class="wizardtext">
<p>
<%=intl._t("I2P is a communication tool for people who value privacy and wish to remain anonymous online.")%>
<%=intl._t("I2P is a peer to peer network where you can browse and create hidden services.")%>
</p>
</div>
<%

    } else if (ipg == 3) {
        // Overview of bandwidth test
%>
<img class="wizard progress" src="/themes/console/images/wizard/logogrey3.png">
<h3 id="wizardheading" class="wizard"><%=intl._t("Bandwidth Test")%></h3>
<img class="wizardimg" src="/themes/console/images/wizard/undraw_speed_test.png">
<div class="wizardtext">
<p>
<%=intl._t("I2P will now test your internet connection to identify the optimal speed settings.")%>
<%=intl._t("Bandwidth participation improves the anonymity level of all users on the network and maximizes your download speed.")%>
<%=intl._t("This is done using the third-party M-Lab service.")%>
</p><p>
<%=intl._t("Please review the M-Lab privacy policies linked below.")%>
<%=intl._t("If you do not wish to run the M-Lab bandwidth test, you may skip it by clicking the button below.")%>
</p><p>
<a href="https://www.measurementlab.net/privacy/" target="_blank"><%=intl._t("M-Lab Privacy Policy")%></a>
<br><a href="https://github.com/m-lab/mlab-ns/blob/master/MLAB-NS_PRIVACY_POLICY.md" target="_blank"><%=intl._t("M-Lab Name Server Privacy Policy")%></a>
</p>
</div>
<%

    } else if (ipg == 4) {
        // Bandwidth test in progress (w/ AJAX)
%>
<img class="wizard progress" src="/themes/console/images/wizard/logogrey4.png">
<h3 id="wizardheading" class="wizard"><%=intl._t("Bandwidth Test in Progress")%></h3>
<img class="wizardimg" src="themes/console/images/wizard/undraw_in_progress.png">
<div id="xhr" class="notifcation">
<!-- for non-script -->
<%=intl._t("Javascript is disabled - wait 60 seconds for the bandwidth test to complete and then click Next")%>
</div>
<div id="xhr2" class="notification">
</div>
<%

    } else if (ipg == 5) {
        // Bandwidth test results
        // and/or manual bw entry?
%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=i2pcontextId%>" />
<%
        if (request.getParameter("skipbw") == null) {
            // don't display this if we skipped the test
%>
<img class="wizard progress bwtest" src="/themes/console/images/wizard/logogrey5.png">
<h3 id="wizardheading" class="wizard bwtest"><%=intl._t("Bandwidth Test Results")%></h3>
<table class="mlabtable">
<tr><td><%=intl._t("Test running?")%></td><td><%=wizhelper.isNDTRunning()%></td></tr>
<tr><td><%=intl._t("Test complete?")%></td><td><%=wizhelper.isNDTComplete()%></td></tr>
<tr><td><%=intl._t("Test server location")%></td><td><%=wizhelper.getServerLocation()%></td></tr>
<tr><td><%=intl._t("Completion status")%></td><td><%=wizhelper.getCompletionStatus()%></td></tr>
<tr><td><%=intl._t("Details")%></td><td><%=wizhelper.getDetailStatus()%></td></tr>
<%
            if (wizhelper.isNDTSuccessful()) {
                // don't display this if test failed
%>
<tr><td><%=intl._t("Downstream Bandwidth")%></td><td><%=net.i2p.data.DataHelper.formatSize2Decimal(wizhelper.getDownBandwidth())%>Bps</td></tr>
<tr><td><%=intl._t("Upstream Bandwidth")%></td><td><%=net.i2p.data.DataHelper.formatSize2Decimal(wizhelper.getUpBandwidth())%>Bps</td></tr>
<tr><td><%=intl._t("Share of Bandwidth for I2P")%></td><td><%=Math.round(net.i2p.router.web.helpers.WizardHelper.BW_SCALE * 100)%>%</td></tr>
<%
            } // sucessful
%>
</table>
<%
        } // skipbw
%>
<img class="wizard progress" src="/themes/console/images/wizard/logogrey6.png">
<h3 id="wizardheading" class="wizard"><%=intl._t("Bandwidth Configuration")%></h3>
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
        out.print("\n");

        out.print(intl._t("I2P requires at least 12KBps to enable sharing. "));
        out.print(intl._t("Please enable sharing (participating in tunnels) by configuring more bandwidth. "));
        out.print(intl._t("It improves your anonymity by creating cover traffic, and helps the network."));
    } else {
        out.print(intl._t("You have configured I2P to share {0} KBps.", share));
        out.print("\n");

        out.print(intl._t("The higher the share bandwidth the more you improve your anonymity and help the network."));
    }
%>
</div>
<%

    } else if (ipg == 6) {
        // Browser setup
%>
<img class="wizard progress" src="/themes/console/images/wizard/logogrey7.png">
<h3 id="wizardheading" class="wizard"><%=intl._t("Browser Setup")%></h3>
<img class="wizardimg" src="/themes/console/images/wizard/undraw_startup_life.png">
<div class="wizardtext"><p>
<%=intl._t("Your browser needs to be configured to work with I2P.")%>
<%=intl._t("We have instructions for configuring both Firefox and Chromium based browser with I2P.")%>
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
</p></div>
<%

    } else if (ipg == LAST_PAGE) {
        // Done
%>
<h3 id="wizardheading" class="wizard"><%=intl._t("Welcome to I2P!")%></h3>
<img class="wizardimg" src="/themes/console/images/wizard/undraw_High_five.png">
<div class="wizardtext">
<p>
<%=intl._t("When you start I2P, it may take a few minutes to bootstrap (integrate) your router into the network and find additional peers, so please be patient.")%>
</p><p>
<%=intl._t("When I2P starts up, and during normal operation, I2P's tunnel build readiness indicator in the side panel may indicate that I2P is \"Rejecting Tunnels\"; this is normal behavior.")%>
<%=intl._t("Once green stars are indicated next to your Local Tunnels, there is a wide variety of things you can do with I2P.")%>
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
        if (ipg == 3) {
%>
<input type="submit" class="cancel wizardbutton" name="skipbw" value="<%=intl._t("Skip Bandwidth Test")%>" >
<%
        } else if (ipg == 4) {
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
