/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

// This component is dedicated to the public domain. It uses the CC0
// as a formal dedication to the public domain and in circumstances where
// a public domain is not usable.

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

/* #license-end */
