/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

// This component is dedicated to the public domain. It uses the CC0
// as a formal dedication to the public domain and in circumstances where
// a public domain is not usable.

// refresh the sidebar mini graph every 15 seconds

function refreshGraph() {
    document.getElementById('sb_graphcontainer').style.backgroundImage = 'url(/viewstat.jsp?stat=bw.combined&periodCount=20&width=220&height=50&hideLegend=true&hideGrid=true&time=' + new Date().getTime();
    setTimeout(refreshGraph, 15000);
}

refreshGraph();

/* @license-end */
