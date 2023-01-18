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
		sch.addEventListener("reset", function(event) {
			if (box.value !== "") {
				box.value = "";
				requestAjax2(-1);
			}
			event.preventDefault();
		});

		box.addEventListener("input", function(event) {
			requestAjax2(-1);
		});
	}
}

document.addEventListener("DOMContentLoaded", function() {
    initSearch();
}, true);

/* @license-end */
