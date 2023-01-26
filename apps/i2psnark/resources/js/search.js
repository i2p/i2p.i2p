/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

/**
 * Search form helpers
 *
 * @since 0.9.58
 */
function initSearch()
{
	var sch = document.getElementById("search");
	if (sch != null) {
		var box = document.getElementById("searchbox");
		var cxl = document.getElementById("searchcancel");
		cxl.addEventListener("click", function(event) {
			if (box.value !== "") {
				box.value = "";
				requestAjax2(-1);
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
			requestAjax2(-1);
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

document.addEventListener("DOMContentLoaded", function() {
    initSearch();
}, true);

/* @license-end */
