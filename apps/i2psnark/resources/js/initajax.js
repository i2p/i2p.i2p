/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

// This component is dedicated to the public domain. It uses the CC0
// as a formal dedication to the public domain and in circumstances where
// a public domain is not usable.

/**
 *  Use the refresh delay set in head
 */
function requestAjax1() {
    requestAjax2(ajaxDelay);
}

/**
 *  Use the refresh delay specified
 *
 *  @param refreshtime do not refresh if less than or equal to 0
 *  @since 0.9.58
 */
function requestAjax2(refreshtime) {
    var url = ".ajax/xhr1.html";
    var query = window.location.search;
    var box = document.getElementById("searchbox");
    if (box != null) {
        // put in, remove, or replace value from search form
        var search = box.value;
        if (search.length > 0) {
            if (query == null) {
                query = "";
            }
            var q = new URLSearchParams(query);
            q.set("nf_s", encodeURIComponent(search));
            query = "?" + q.toString();
        } else {
            if (query != null) {
                var q = new URLSearchParams(query);
                q.delete("nf_s");
                var newq = q.toString();
                if (newq != null && newq.length > 0) {
                    query = "?" + newq;
                } else {
                    query = null;
                }
            }
        }
    }
    if (query)
        url += query;
    // tell ajax not to setTimeout(), and do our own
    // setTimeout() so we update the URL every time
    ajax(url, "mainsection", -1);
    if (refreshtime > 0) {
        setTimeout(requestAjax1, refreshtime);
    }
}

function initAjax() {
    setTimeout(requestAjax1, ajaxDelay);
}

document.addEventListener("DOMContentLoaded", function() {
   initAjax();
}, true);

/* #license-end */
