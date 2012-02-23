//var page = "home";
function ajax(url,target) {
  // native XMLHttpRequest object
  if (window.XMLHttpRequest) {
    req = new XMLHttpRequest();
    req.onreadystatechange = function() {ajaxDone(target);};
    req.open("GET", url, true);
    req.send(null);
    // IE/Windows ActiveX version
  } else if (window.ActiveXObject) {
    req = new ActiveXObject("Microsoft.XMLDOM");
    if (req) {
      req.onreadystatechange = function() {ajaxDone(target);};
      req.open("GET", url, true);
      req.send(null);
    }
  }
  //setTimeout("ajax(page,'scriptoutput')", 5000);
}

function ajaxDone(target) {
  // only if req is "loaded"
  if (req.readyState == 4) {
    // only if "OK"
    if (req.status == 200) {
      results = req.responseText;
      document.getElementById(target).innerHTML = results;
      document.getElementById("lowersection").style.display="block";
    } else {
      document.getElementById(target).innerHTML="<b>Router is down</b>";
      document.getElementById("lowersection").style.display="none";
    }
  }
}

function requestAjax1() { ajax("/i2psnark/.ajax/xhr1.html", "mainsection"); }
function initAjax(delayMs) { setInterval(requestAjax1, delayMs);  }
