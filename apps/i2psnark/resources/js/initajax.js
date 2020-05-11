function requestAjax1() {
    var url = ".ajax/xhr1.html";
    var query = window.location.search;
    if (query)
        url += query;
    ajax(url, "mainsection", ajaxDelay);
}

function initAjax() {
    setTimeout(requestAjax1, ajaxDelay);
}

document.addEventListener("DOMContentLoaded", function() {
   initAjax();
}, true);
