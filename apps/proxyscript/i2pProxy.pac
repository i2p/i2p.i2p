/*******************************************************
** Proxy Auto Configure Script with I2P Host Detection.
**
** Author:  Cervantes
** License: Public Domain
** Date:    11 May 2004
**
** Revised: 07 Sep 2004
** Changes:
**       Proxy recursion disabled by default (strict)
**       Password Authentication for session commands
**       Support for http://path?i2paddresshelper=BASE64
**       Support for http://i2p/BASE64/path syntax
** Revised: 17 May 2004
** Changes:
**       Ability for the user to control the proxy
**       status on a per browser-session basis.
********************************************************/


/* C O N F I G U R A T I O N
*/ 

/* Replace normal with "PROXY yourproxy:port" if you run
** behind a Proxy Server by default.
*/ 
var normal = "DIRECT";

/* Specify the I2P proxy connection details here.
*/ 

var i2pProxy = "PROXY 127.0.0.1:4444";

/*   Set the default status of proxy detection here:
**
**
**   [1] "auto"	=> Proxy will be used automatically if
**                 '->  an ".i2p" url is detected.
**                 '->  You will only be anonymous for ".i2p" domains.
**
**   [2] "on"	=> Proxy is enabled all the time. (safest)
**                 '->  NB. Normal web available only via
**                 '->  i2p outproxies.
**                 '->  You can be fairly confident of anonymity.
**
**   [3] "off"	=> Completely Bypass the proxy and therefore i2p.
**                 '->  no i2p sites will be accessible...
**                 '->  ...your browsing habits can potentially
**                 '->  be traced back to you!
**
*/

var proxyStatus = "auto";

/*   By setting statusKeyword to "all" you can set these options at runtime
**   for the duration of the browser session by entering special commands
**   into your browser address bar.
**   
**   Due to limitations in the way proxy scripting works, a malicious site
**   could change your status mode by linking to command keywords...
**   eg. <img src="i2p.off" ...
**   This is why the default setting for statusKeyword is "limited", which only
**   allows you to set the proxy status to "on". See also keywordAuthPassword.
**
**   [1] "all"	=> All proxy status urls are available.
**               '->  i2p.on, i2p.off, i2p.auto (respective to proxyStatus settings)
**               '->  WARNING: Setting "all" is a big risk to your anonymity!
**               '->  In this mode it is highly recommended you set an AuthPassword too!
**
**   [2] "limited"  => Only i2p.on is available..
**                      '->  This setting lasts for the duration of the browser setting.
**                      '->  You have to close your browser in order to revert to
**                      '->  your default proxyStatus configuration.
**
**   [3] "off"  => No command urls available.
**               '->  The status mode can only be altered by editing the above
**               '->  proxyStatus setting. (safest)
**
*/

var statusKeyword = "all";

/*
**   By default if proxyStatus is set to "auto" the config script
**   will fall back to your normal connection settings if the
**   i2p proxy is offline. This is handy for browsing your locally
**   hosted eepsites when your router is not running (for instance).
**   However this can mean that requests to external eepsites could
**   be forwarded to the outweb and potentially compromise some of
**   your rights to anonymity.
**   Setting "true" here enables strict mode where all requests to ".i2p"
**   sites will be rejected if the i2p proxy is offline. (safest)
*/

var strict = true;

/*
**   By setting an authentication password, all activated session keywords
**   will require the addition of a password to prevent malicious sites from
**   hijacking your proxy settings. ie. <img src="i2p.off" ...
**   Users should append whatever they set here to any command keywords
**   they use. 
**   eg. i2p.on.passw0rd
**   If left blank, authentication is ignored - it is recommended that
**   you use "limited" statusKeyword mode if you choose not to require a password.
**   If you do require this feature then you should replace the default "passw0rd" with
**   one of your own (recommend at least 8 letters with a case-sensitive alpha-numeric
**   mix of characters).
**
*/

var keywordAuthPassword = "passw0rd";


/* E N D   C O N F I G U R A T I O N
*/


/* Allows the proxy to fallback on "normal" settings
** '-> if the i2p router is offline.
*/

if (strict == false) {
     i2pProxy = i2pProxy + "; " + normal;
}

/*Check for User Authentication Password.
*/
if (keywordAuthPassword != "") {
     keywordAuthPassword = "." + keywordAuthPassword;
}

/* This function gets called every time a url is submitted
*/

function FindProxyForURL(url, host) {
    /* checks for a special command url that
    ** '-> changes the status of the proxy script.
    */

   if (statusKeyword != "off") {
      if (host == "i2p.off" + keywordAuthPassword && statusKeyword == "all") {
         /*Proxy is bypassed - outweb available only
         */
         proxyStatus = "off";
      } else if (host == "i2p.auto" + keywordAuthPassword && statusKeyword == "all") {
          /* Proxy is used only for .i2p hosts otherwise
          ** '-> browse as normal.
          */
          proxyStatus = "auto";
      } else if (host == "i2p.on" + keywordAuthPassword && (statusKeyword == "limited" ||
                                                            statusKeyword == "all"        )) {
          /* Only I2P traffic is accepted.
          */
          proxyStatus = "on";
      } 
   }

   if (proxyStatus == "off") {
       /* Proxy is completely bypassed.
       */
       return normal;
   } else if (proxyStatus == "on") {
       /* All requests are forward to the proxy.
       */
       return i2pProxy;
   }
   
   host = host.toLowerCase();
   /* check tld for "i2p" or oOo's new "i2paddresshelper" syntax - if found then redirect
   ** '-> request to the i2p proxy
   */
   
   if (url.match(/^http:\/\/i2p\/[a-zA-Z0-9\-\~]{516}|i2paddresshelper=/i) ||
       shExpMatch(host, "*.i2p")) {                       // seems more reliable than:
       return i2pProxy;                                     // dnsDomainIs(host, ".i2p") || 
   } else {                                                   // i2pRegex.test(host)
       return normal;
   }
}
