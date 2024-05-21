/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

/**
 * Search form helpers
 *
 * @since 0.9.63 from i2psnark
 */
function initSearch() {
	var sch = document.getElementById("search");
	if (sch != null) {
		var box = document.getElementById("searchbox");
		var cxl = document.getElementById("searchcancel");
		cxl.addEventListener("click", function(event) {
			if (box.value !== "") {
				box.value = "";
				requestAjax1();
				//setTimeout(requestAjax2, 1000);
				requestAjax2();
			}
			cxl.classList.add("disabled");
			event.preventDefault();
		});

		box.addEventListener("input", function(event) {
			if (box.value !== "") {
				cxl.classList.remove("disabled");
			} else {
				cxl.classList.add("disabled");
			}
			requestAjax1();
			//setTimeout(requestAjax2, 1000);
			requestAjax2();
		});

		if (box.value !== "") {
			cxl.classList.remove("disabled");
		} else {
			cxl.classList.add("disabled");
		}
		// so we don't get the link popup
		cxl.removeAttribute("href");
	}
}

function requestAjax1() {
    var url = "/susimail/";
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
            q.set("xhr1", "1");
            q.set("nf_s", encodeURIComponent(search));
            query = "?" + q.toString();
        } else {
            if (query != null) {
                var q = new URLSearchParams(query);
                q.set("xhr1", "1");
                q.delete("nf_s");
                var newq = q.toString();
                query = "?" + newq;
            }
        }
    }
    if (query)
        url += query;
    ajax(url, "mailboxdiv", -1);
}

function requestAjax2() {
    var url = "/susimail/";
    var query = window.location.search;
    var box = document.getElementById("searchbox");
    var search = "";
    if (box != null) {
        // put in, remove, or replace value from search form
        search = box.value;
        if (search.length > 0) {
            if (query == null) {
                query = "";
            }
            var q = new URLSearchParams(query);
            q.set("xhr2", "1");
            q.set("nf_s", encodeURIComponent(search));
            query = "?" + q.toString();
        } else {
            if (query != null) {
                var q = new URLSearchParams(query);
                q.set("xhr2", "1");
                q.delete("nf_s");
                query = "?" + q.toString();;
            }
        }
    }
    if (query)
        url += query;
    ajax(url, "pagenavdiv1", -1);
    var div2 = document.getElementById("pagenavdiv2");
    if (div2 != null) {
        var q = new URLSearchParams(query);
        q.set("xhr2", "2");
        url = "/susimail/?" + q.toString();
        ajax(url, "pagenavdiv2", -1);
    }
    // fixup pagenav hidden form params
    var inputs = document.getElementsByClassName("searchparam");
    for (var index = 0; index < inputs.length; index++) {
        var input = inputs[index];
        input.setAttribute("value", search);
    }
}

document.addEventListener("DOMContentLoaded", function() {
    initSearch();
}, true);

/* @license-end */
