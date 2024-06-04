/* I2P+ graphs.js by dr|z3d */
/* Ajax graph refresh and configuration toggle */
/* Adapted from I2P+, licensed to I2P under our license */

var __graphs_counter = 0;

function initGraphs() {
  if (graphRefreshInterval > 0) {
      setTimeout(updateGraphs, graphRefreshInterval * 1000);
  }
}

function updateGraphs() {
  const param = '&g=' + (++__graphs_counter);
  var images = document.getElementsByClassName("statimage");
  for (var i = 0; i < images.length; i++) {
    let image = images[i];
    // https://stackoverflow.com/questions/1077041/refresh-image-with-a-new-one-at-the-same-url
    let idx = image.src.indexOf('&g=');
    if (idx > 0) {
        image.src = image.src.substring(0, idx) + param;
    } else {
        image.src = image.src + param;
    }
  }
  setTimeout(updateGraphs, graphRefreshInterval * 1000);
}

document.addEventListener("DOMContentLoaded", function() {
  initGraphs();
});
