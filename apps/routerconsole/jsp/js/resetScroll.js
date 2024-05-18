/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

// This component is dedicated to the public domain. It uses the CC0
// as a formal dedication to the public domain and in circumstances where
// a public domain is not usable.

function initResetScroll()
{
	var buttons = document.getElementsByClassName("resetScrollLeft");
	for (var index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addBlurHandler(button);
	}
}

function addBlurHandler(elem)
{
        elem.addEventListener("blur", function() {
            resetScrollLeft(elem);
        });
}


// resets scroll position of element
// use with onblur to clear scroll position when element loses focus


// reset scroll to left position

function resetScrollLeft(element) {
    element.scrollLeft = 0;
}

// reset scroll to top position
// unused
function resetScrollTop(element) {
    element.scrollTop = 0;
}

document.addEventListener("DOMContentLoaded", function() {
    initResetScroll();
}, true);

/* @license-end */
