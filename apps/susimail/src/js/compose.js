/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

var __susimail_beforePopup = true;

function initPopup() {
	window.addEventListener('beforeunload', (e)=>{if (__susimail_beforePopup) e.returnValue=true;} );

	var buttons = document.getElementsByClassName("beforePopup");
	for (var index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler5(button);
	}
}

function addClickHandler5(elem)
{
	elem.addEventListener("click", function() {
		__susimail_beforePopup = false;
	});
}

document.addEventListener("DOMContentLoaded", function() {
    initPopup();
}, true);

/* @license-end */
