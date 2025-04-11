/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

// This component is dedicated to the public domain. It uses the CC0
// as a formal dedication to the public domain and in circumstances where
// a public domain is not usable.

var __ajaxchanges_fails = 0;

/**
 *
 *  Add/remove elements of changeclass that are inside target.
 *  All elements of the changeclass must have unique ids.
 *  All elements with the same id are assumed unchanged.
 *
 */
function ajaxchanges(url, target, changeclass, refresh) {
  // native XMLHttpRequest object
  if (window.XMLHttpRequest) {
    var req = new XMLHttpRequest();
    req.onreadystatechange = function() {ajaxchangesDone(req, url, target, changeclass, refresh);};
    req.open("GET", url, true);
    req.setRequestHeader("If-Modified-Since","Sat, 1 Jan 2000 00:00:00 GMT");
    req.send(null);
  }
}

/**
 *
 *  Add/remove elements of changeclass that are inside target.
 *  All elements of the changeclass must have unique ids.
 *  All elements with the same id are assumed unchanged. TODO another class for changes
 *
 */
function ajaxchangesDone(req, url, target, changeclass, refresh) {
  if (req.readyState == 4) {
    if (req.status == 200) {
      __ajax_fails = 0;
      const results = req.responseXML;
      const oldtgt = document.getElementById(target);
      const newtgt = results.getElementById(target);
      const oldelements = oldtgt.getElementsByClassName(changeclass);
      const newelements = newtgt.getElementsByClassName(changeclass);
      //var added = 0;
      //var removed = 0;
      // remove old ones not in new
      for (var i = 0; i < oldelements.length; i++) {
          let e = oldelements[i];
          let id = e.id;
          let e2 = newtgt.getElementById(id);
          if (e2 == null) {
              //console.warn("Removing " + id);
              e.remove();
              //removed++;
          }
      }
      // add new ones not in old
      for (var i = 0; i < newelements.length; i++) {
          let e = newelements[i];
          let id = e.id;
          let e2 = oldtgt.getElementById(id);
          if (e2 == null) {
              //console.warn("Adding " + id);
              oldtgt.appendChild(e);
              //added++;
          }
      }
      //console.warn("Added " + added + " removed " + removed);
    } else if (__ajaxchanges_fails == 0) {
      __ajaxchanges_fails++;
    } else {
      document.getElementById(target).innerHTML = failMessage;
    }

    if (refresh > 0) {
      setTimeout(function() {ajaxchanges(url, target, changeclass, refresh);}, refresh);
    }
  }
}

/* @license-end */
