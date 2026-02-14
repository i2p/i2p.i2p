/*  */

function initMap() {
  let svg = document.getElementById("mapoverlaysvg");
  if (svg != null) {
      drawTunnels();
      setTimeout(updateMap, 60 * 1000);
  }
}

function updateMap() {
  var url = "/viewmap.jsp";
  const urlParams = new URLSearchParams(window.location.search);
  const f = urlParams.get('f');
  if (f != null)
      url += "?f=" + f;
  ajaxchanges(url, "mapoverlaysvg", "dynamic", 60*1000);
}

function drawTunnels() {
  var paths = document.getElementsByClassName("mapoverlaytunnel");
  for (var i = 0; i < paths.length; i++) {
      drawTunnel(paths[i]);
  }
}

function drawTunnel(path) {
  // https://jakearchibald.com/2013/animated-line-drawing-svg/
  var len = path.getTotalLength();
  path.style.strokeDasharray = len + ' ' + len;
  path.style.strokeDashoffset = len;
  path.getBoundingClientRect();
  path.style.transition = 'stroke-dashoffset 5s ease-in-out';
  path.style.strokeDashoffset = '0';
}

function beginCircleAnim(circleid) {
  // move onscreen
  let circle = document.getElementById(circleid);
  if (circle != null) {
      circle.setAttribute('cx', '0');
  }
}

function endCircleAnim(circleid) {
  // remove it
  let circle = document.getElementById(circleid);
  if (circle != null) {
      // move them offscreen, the first ajaxchanges will remove them
      //circle.remove();
      circle.setAttribute('cx', '-5');
  }
}

document.addEventListener("DOMContentLoaded", function() {
  initMap();
});
