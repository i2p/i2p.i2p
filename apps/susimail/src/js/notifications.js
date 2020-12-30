/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

function initNotifications() {
	var buttons = document.getElementsByClassName("notifications");
	for(index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler6(button);
	}
}

function addClickHandler6(elem)
{
	elem.addEventListener("click", function() {
		elem.remove();
	});
}

document.addEventListener("DOMContentLoaded", function() {
    initNotifications();
}, true);

/* @license-end */
