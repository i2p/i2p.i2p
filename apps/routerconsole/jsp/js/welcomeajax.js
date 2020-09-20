/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

// This component is dedicated to the public domain. It uses the CC0
// as a formal dedication to the public domain and in circumstances where
// a public domain is not usable.

var fails = 0;

function ajax(url, target, refresh) {
  // native XMLHttpRequest object
  if (window.XMLHttpRequest) {
    req = new XMLHttpRequest();
    req.onreadystatechange = function() {ajaxDone(url, target, refresh);};
    req.open("GET", url, true);
    // IE https://www.jamesmaurer.com/ajax-refresh-problem-w-ie-not-refreshing.asp
    req.setRequestHeader("If-Modified-Since","Sat, 1 Jan 2000 00:00:00 GMT");
    req.send(null);
    // IE/Windows ActiveX version
  } else if (window.ActiveXObject) {
    req = new ActiveXObject("Microsoft.XMLDOM");
    if (req) {
      req.onreadystatechange = function() {ajaxDone(target);};
      req.open("GET", url, true);
      // IE https://www.jamesmaurer.com/ajax-refresh-problem-w-ie-not-refreshing.asp
      req.setRequestHeader("If-Modified-Since","Sat, 1 Jan 2000 00:00:00 GMT");
      req.send(null);
    }
  }
}

function ajaxDone(url, target, refresh) {
  // only if req is "loaded"
  if (req.readyState == 4) {
    var fail = false;
    var running = true;
    var done = false;
    // only if "OK"
    if (req.status == 200) {
      // output 1 for complete, 0 + status string for in progress
      fails = 0;
      var status;
      // IE doesn't support startsWith()
      if (req.responseText.indexOf("1") == 0) {
          results = doneMessage;
          running = false;
          done = true;
          status = "";
      } else {
          results = progressMessage;
          status = req.responseText.substring("1")
      }
      document.getElementById("xhr2").innerHTML = status;
      document.getElementById(target).innerHTML = results;
    } else if (fails == 0) {
      // avoid spurious message if cancelled by user action
      fails++;
    } else {
      document.getElementById(target).innerHTML = failMessage;
      running = false;
      fail = true;
    }
    var form = document.forms[0];
    form.prev.disabled = fail || !done;
    form.skip.disabled = !done;
    form.cancelbw.disabled = !running;
    form.next.disabled = fail || !done;

    setTimeout(function() {ajax(url, target, refresh);}, refresh);
  }
}

/* @license-end */
