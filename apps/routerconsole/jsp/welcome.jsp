<%@page contentType="text/html"%><%@page pageEncoding="UTF-8"%><jsp:useBean class="net.i2p.router.web.helpers.WizardHelper" id="wizhelper" scope="session" /><%
    // note that for the helper we use a session scope, not a request scope,
    // so that we can access the NDT test results.
    // The MLabHelper singleton will prevent multiple simultaneous tests, even across sessions.

    // page ID
    // 1: Select language
    // 2: Check internet connection
    // 3: Bandwidth test in progress
    // 4: Bandwidth test results
    // 5: Browser setup
    // 6: Done
    final int PAGE_LANG = net.i2p.router.web.helpers.WizardHelper.PAGE_LANG;
    final int PAGE_THEME = net.i2p.router.web.helpers.WizardHelper.PAGE_THEME;
    final int PAGE_CHECK = net.i2p.router.web.helpers.WizardHelper.PAGE_CHECK;
    final int PAGE_TEST = net.i2p.router.web.helpers.WizardHelper.PAGE_TEST;
    final int PAGE_RESULTS = net.i2p.router.web.helpers.WizardHelper.PAGE_RESULTS;
    final int PAGE_BROWSER = net.i2p.router.web.helpers.WizardHelper.PAGE_BROWSER;
    final int PAGE_DONE = net.i2p.router.web.helpers.WizardHelper.PAGE_DONE;

    final int FIRST_PAGE = PAGE_LANG;
    final int LAST_PAGE = PAGE_DONE;
    String pg = request.getParameter("page");
    int ipg;
    if (pg == null) {
        ipg = 1;
    } else {
        try {
            ipg = Integer.parseInt(pg);
            if (request.getParameter("prev") != null) {
                // previous button handling
                if (ipg == PAGE_BROWSER)
                    ipg = PAGE_CHECK;
                else
                    ipg -= 2;
            }
            if (ipg < FIRST_PAGE || ipg > LAST_PAGE) {
                ipg = FIRST_PAGE;
            } else if (ipg == PAGE_TEST && request.getParameter("skipbw") != null) {
                ipg++;  // skip bw test
            }
        } catch (NumberFormatException nfe) {
            ipg = FIRST_PAGE;
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
%><!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("New Install Wizard")%>
<%
    wizhelper.setContextId(i2pcontextId);
    if (ipg == PAGE_TEST) {
%>
<script src="/js/welcomeajax.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" type="text/javascript">
/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

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

/* @license-end */
</script>
<%
    } else if (ipg == PAGE_THEME) {
%>
<script src="/js/welcomeui.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<%
    }  // ipg == PAGE_TEST
%>
</head><body>
<div id="wizard" class="overlay">

<jsp:useBean class="net.i2p.router.web.helpers.WizardHandler" id="formhandler" scope="request" />

<%
    // Bind the session-scope Helper to the request-scope Handler
    formhandler.setWizardHelper(wizhelper);
%>
<img class="wizard progress" src="/themes/console/images/wizard/wizardlogo.png">
<div class="wizardnotice">
<%@include file="formhandler.jsi" %>
</div>
<div class="clickableProgression">
<%
    for (int i = FIRST_PAGE; i <= LAST_PAGE; i++) {
        if (i < ipg) {
            %><span class="visitedProgression">&#x2B24;</span><%
        } else if (i == ipg) {
            %><span class="currentProgression">&#x2B24;</span><%
        } else {
            %><span class="unvisitedProgression">&#x25EF;</span><%
        }
    }
%>
</div>
<form action="/welcome" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>">
<input type="hidden" name="action" value="blah" >
<input type="hidden" name="page" value="<%=(ipg + 1)%>" >
<%
    if (ipg == PAGE_LANG || ipg == PAGE_THEME) {
%>
<%-- needed for CSS: --%><div id="config_ui">
<%-- needed for lang setting in css.jsi: --%><input type="hidden" name="consoleNonce" value="<%=net.i2p.router.web.CSSHelper.getNonce()%>" >
<%
    }
    if (ipg == PAGE_LANG) {
        // language selection
%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHelper" id="uihelper" scope="request" />
<jsp:setProperty name="uihelper" property="contextId" value="<%=i2pcontextId%>" />
<h3 id="wizardheading" class="wizard"><%=uihelper._t("Select Language")%></h3>
<div class="wizardtext">
<p>

<%=intl._t("This project is supported by volunteer translators on Transifex.")%>
<%=intl._t("This helps keep I2P accessible to everyone all over the world.")%>
<%=intl._t("Thank you to all of the volunteers all over the world who help make I2P accessible.")%>
<%=intl._t("If you would like to get involved, {0}please consider the I2P translation efforts{1}.", "<a href=\"https://www.transifex.com/projects/p/I2P/\" target=\"_blank\">", "</a>")%>
</p>
<p>
<%=intl._t("Please select your preferred language:")%>
</p>
<div id="wizlangsettings" class="wizard">
<jsp:getProperty name="uihelper" property="langSettings" />
</div>
</div>
</div>
<%

    } else if (ipg == PAGE_THEME) {
        // Theme picker
%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHelper" id="uihelper2" scope="request" />
<jsp:setProperty name="uihelper2" property="contextId" value="<%=i2pcontextId%>" />
<h3 id="wizardheading" class="wizard"><%=uihelper2._t("Select Theme")%></h3>
<div class="wizardtext">
<p>
<%=intl._t("Please select your preferred theme:")%>
<div id="wizlangsettings" class="wizard">
<jsp:getProperty name="uihelper2" property="settings" />
</div>
</p>
</div>
</div>
<%

    } else if (ipg == PAGE_CHECK) {
        // Overview of bandwidth test
%>
<h3 id="wizardheading" class="wizard"><%=intl._t("Bandwidth Test")%></h3>
<div class="wizardtext">
<p>
<%=intl._t("Let's check your internet connection!")%>
</p>
<p>
<%=intl._t("Bandwidth participation not only makes your speed and connection to the I2P network better, but also helps everyone using the network.")%>
<%=intl._t("When everyone is sharing as much bandwidth as possible, everyone gets to have better performance and privacy by sharing participating traffic.")%>
</p>
<p>
<%=intl._t("I2P uses M-Lab, a third party service, to help you test your internet connection and find the optimal speed settings.")%>
<%=intl._t("During this time you will be connected directly to M-Lab's service with your real IP address.")%>
</p><p>
<%=intl._t("Please review the M-Lab privacy policies linked below.")%>
<%=intl._t("If you do not wish to run the M-Lab bandwidth test, you can skip it and configure your bandwidth later.")%>
</p><p>
<%=intl._t("{0}M-Lab Privacy Policy{1}", "<a href=\"https://www.measurementlab.net/privacy/\" target=\"_blank\">", "</a>")%>
<br>
<%=intl._t("{0}M-Lab Name Server Privacy Policy{1}", "<a href=\"https://github.com/m-lab/mlab-ns/blob/master/MLAB-NS_PRIVACY_POLICY.md\" target=\"_blank\">", "</a>")%>
</p>
</div>
<%

    } else if (ipg == PAGE_TEST) {
        // Bandwidth test in progress (w/ AJAX)
%>
<h3 id="wizardheading" class="wizard"><%=intl._t("Bandwidth Test in Progress")%></h3>
<div id="xhr" class="notification">
<!-- for non-script -->
<%=intl._t("Javascript is disabled - wait 60 seconds for the bandwidth test to complete and then click Next")%>
</div>
<div id="xhr2" class="notification">
</div>
<div class="wizardtext">
<p>
<%=intl._t("While the I2P router software is getting ready to go, it's testing your bandwidth, obtaining an initial set of peers from a reseed server, making its first few connections, and getting integrated with the rest of the network.")%>
</p>
<p>
<%=intl._t("To learn more about how important I2P Reseed servers are, or explore other topics and what functions they perform for I2P, you can visit the {0}I2P Wiki{1}.", "<a href=\"http://wiki.i2p-projekt.i2p\" target=\"_blank\">", "</a>")%>
<%=intl._t("If you would like to discuss I2P topics or get help from the community, you can visit the {0}I2P Forums{1}.", "<a href=\"http://i2pforum.net\" target=\"_blank\">", "</a>")%>
<%=intl._t("If you want to know more about the I2P Project itself, or the Invisible Internet in general, you can visit our {0}Project Website{1}.",
"<a href=\"http://geti2p.net\" target=\"_blank\">", "</a>")%>
</p>
</div>
<%

    } else if (ipg == PAGE_RESULTS) {
        // Bandwidth test results
        // and/or manual bw entry?
%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=i2pcontextId%>" />
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
<tr><td><input style="text-align: right; width: 5em;" name="inboundrate" type="text" size="5" maxlength="5" value="<jsp:getProperty name="wizhelper" property="inboundBurstRate" />" required>
<%=intl._t("KBps In")%>
</td><td>(<jsp:getProperty name="wizhelper" property="inboundBurstRateBits" />)</td>
</tr>
<tr>
<%-- display burst, set standard, handler will fix up --%>
<td><input style="text-align: right; width: 5em;" name="outboundrate" type="text" size="5" maxlength="5" value="<jsp:getProperty name="wizhelper" property="outboundBurstRate" />" required>
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
        out.print("<br>");

        out.print(intl._t("I2P requires at least 12KBps to enable sharing. "));
        out.print(intl._t("By donating your bandwidth to participating traffic, you not only help others, you improve your own anonymity and performance."));
        out.print("<br>");
        out.print(intl._t("We recommend sharing 75% or more for I2P, but you can adjust based on your needs."));
    } else {
        out.print(intl._t("You have configured I2P to share {0} KBps.", share));
        out.print("<br>");

        out.print(intl._t("By donating your bandwidth to participating traffic, you not only help others, you improve your own anonymity and performance."));
        out.print("<br>");
        out.print(intl._t("We recommend sharing 75% or more for I2P, but you can adjust based on your needs."));
    }
%>
</div>
<%

    } else if (ipg == PAGE_BROWSER) {
        // Browser setup
%>
<h3 id="wizardheading" class="wizard"><%=intl._t("Browser and Application Setup")%></h3>
<div class="wizardtext"><p>
<%=intl._t("Your browser needs to be configured to work with I2P.")%>
<%=intl._t("We have instructions for configuring both Firefox and Chromium based browsers with I2P.")%>
<%=intl._t("You can find these instructions on our {0}website{1}.", "<a href=\"https://geti2p.net/htproxyports\" target=\"_blank\">", "</a>")%>
<%
        if (net.i2p.util.SystemVersion.isWindows()) {
%>
</p><p>
<%=intl._t("Otherwise, the recommended way to browse I2P websites is with a separate profile in the Firefox browser.")%>
<ol><li><%=intl._t("{0}Install Firefox{1}", "<a href=\"https://www.mozilla.org/firefox/\" target=\"_blank\">", "</a>")%>
</li><li><%=intl._t("{0}Install the I2P Firefox profile{1}", "<a href=\"https://geti2p.net/firefox\" target=\"_blank\">", "</a>")%>
</li></ol>
<%
        } //isWindows()
%>
</p>
<p>
<%=intl._t("The I2P router also comes with its own versions of common, useful internet applications.")%>
<%=intl._t("You can download files with {0}bittorrent{1}.", "<a href=\"/torrents\" target=\"_blank\">", "</a>")%>
<%=intl._t("You can also send and receive {0}email{1}.", "<a href=\"/webmail\" target=\"_blank\">", "</a>")%>
<%=intl._t("Besides that, you can use the built-in {0}web server{1} so you can create, communicate, and share your content.", "<a href=\"/i2ptunnel\" target=\"_blank\">", "</a>")%>
<%=intl._t("These applications work with I2P automatically and require no additional configuration.")%>
</p>
<p>
<%=intl._t("To help make I2P addresses easier to read and remember, the I2P router also includes its built-in {0}Address Book{1}.", "<a href=\"/dns\" target=\"_blank\">", "</a>")%>
<%=intl._t("The Address Book is where you keep track of all your I2P \"Contacts\" by giving them human-readable names.")%>
<%=intl._t("This can be used for the sites you like to visit, messaging contacts, or potentially any other service on I2P.")%>
<%=intl._t("To help you get started, the address book can be used to subscribe to lists of addresses distributed by other I2P users.")%>
</p>
</div>
<%

    } else if (ipg == LAST_PAGE) {
        // Done
%>
<h3 id="wizardheading" class="wizard"><%=intl._t("Welcome to the Invisible Internet!")%></h3>
<div class="wizardtext">
<p>
<%=intl._t("It will take some time for your peers to integrate your router into the network, but while that is happening you can still explore I2P applications and get to know your way around the router console.")%>
<%=intl._t("There is a quick guide, news about the latest release, an FAQ, and troubleshooting guide available on the {0}console{1} page.", "<a href=\"/console\" target=\"_blank\">", "</a>")%>
</p><p>
<%=intl._t("You may notice a message in the sidebar that I2P is rejecting tunnels.")%>
<%=intl._t("This is normal behavior as part of the startup process, to make sure that your router is ready to help others with participating traffic.")%>
<%=intl._t("When the connection indicators in the sidebar turn green, you are ready to explore the Invisible Internet.")%>
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
    if (ipg != FIRST_PAGE) {
%>
<input type="submit" class="back wizardbutton" name="prev" value="<%=intl._t("Previous")%>" formnovalidate>
<%
    }
    if (ipg != LAST_PAGE) {
%>
<input type="submit" class="cancel wizardbutton" name="skip" value="<%=intl._t("Skip Setup")%>" formnovalidate>
<%
        if (ipg == PAGE_CHECK) {
%>
<input type="submit" class="cancel wizardbutton" name="skipbw" value="<%=intl._t("Skip Bandwidth Test")%>" formnovalidate>
<%
        } else if (ipg == PAGE_TEST) {
%>
<input type="submit" class="cancel wizardbutton" name="cancelbw" value="<%=intl._t("Cancel Bandwidth Test")%>" formnovalidate>
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
