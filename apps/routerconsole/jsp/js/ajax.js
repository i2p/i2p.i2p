/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

// This component is dedicated to the public domain. It uses the CC0
// as a formal dedication to the public domain and in circumstances where
// a public domain is not usable.

var __ajax_fails = 0;

/**
 *
 *  @param refresh as of 0.9.58, if less than or equal to zero, do not reschedule
 *
 */
function ajax(url, target, refresh) {
  // native XMLHttpRequest object
  if (window.XMLHttpRequest) {
    var req = new XMLHttpRequest();
    req.onreadystatechange = function() {ajaxDone(req, url, target, refresh);};
    req.open("GET", url, true);
    // IE https://www.jamesmaurer.com/ajax-refresh-problem-w-ie-not-refreshing.asp
    req.setRequestHeader("If-Modified-Since","Sat, 1 Jan 2000 00:00:00 GMT");
    req.send(null);
    // IE/Windows ActiveX version
  } else if (window.ActiveXObject) {
    var req = new ActiveXObject("Microsoft.XMLDOM");
    if (req) {
      req.onreadystatechange = function() {ajaxDone(target);};
      req.open("GET", url, true);
      // IE https://www.jamesmaurer.com/ajax-refresh-problem-w-ie-not-refreshing.asp
      req.setRequestHeader("If-Modified-Since","Sat, 1 Jan 2000 00:00:00 GMT");
      req.send(null);
    }
  }
}

/**
 *
 *  @param refresh as of 0.9.58, if less than or equal to zero, do not reschedule
 *
 */
function ajaxDone(req, url, target, refresh) {
  // only if req is "loaded"
  if (req.readyState == 4) {
    // only if "OK"
    if (req.status == 200) {
      __ajax_fails = 0;
      const results = req.responseText;
      document.getElementById(target).innerHTML = results;
      //document.getElementsbyClassName("hideifdown").style.display="block";
    } else if (__ajax_fails == 0) {
      // avoid spurious message if cancelled by user action
      __ajax_fails++;
    } else {
      document.getElementById(target).innerHTML = failMessage;
      //document.getElementByClassName("hideifdown").style.display="none";
    }

    // conditionally display graph so ajax call doesn't interfere with refreshGraph.js
    var graph = document.getElementById("sb_graphcontainer");
    if (graph) {
      graph.style.backgroundImage = "url(/viewstat.jsp?stat=bw.combined&periodCount=20&width=220&height=50&hideLegend=true&hideGrid=true&time=" + new Date().getTime();
    }

    if (refresh > 0) {
      setTimeout(function() {ajax(url, target, refresh);}, refresh);
    }
  }
}

/* @license-end */
