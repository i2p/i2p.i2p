/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

// This component is dedicated to the public domain. It uses the CC0
// as a formal dedication to the public domain and in circumstances where
// a public domain is not usable.

function initStats()
{
	var buttons = document.getElementsByClassName("onchange");
	for (var index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addSChangeHandler(button);
	}
}

function addSChangeHandler(elem)
{
	elem.addEventListener("change", function() {
		location.href=event.target.value;
	});
}

document.addEventListener("DOMContentLoaded", function() {
    initStats();
}, true);

/* @license-end */
