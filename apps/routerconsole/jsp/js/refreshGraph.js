// refresh the sidebar mini graph every 15 seconds

function refreshGraph() {
    document.getElementById('sb_graphcontainer').style.backgroundImage = 'url(/viewstat.jsp?stat=bw.combined&periodCount=20&width=220&height=50&hideLegend=true&hideGrid=true&time=' + new Date().getTime();
    setTimeout(refreshGraph, 15000);
}

refreshGraph();