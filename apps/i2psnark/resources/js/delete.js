/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */

function initDelete()
{
	var main = document.getElementById("mainsection");
	main.addEventListener("click", function() {
		if (!event.target.matches('input')) return;
		var clname = event.target.className;
		if (clname == 'delete1') {
			if (!confirm(deleteMessage1.replace("{0}", event.target.getAttribute("client")))) {
				event.preventDefault();
				return false;
			}
		} else if (clname == 'delete2') {
			if (!confirm(deleteMessage2.replace("{0}", event.target.getAttribute("client")))) {
				event.preventDefault();
				return false;
			}
		}
	});
}

document.addEventListener("DOMContentLoaded", function() {
    initDelete();
}, true);

/* @license-end */
