/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

// This component is dedicated to the public domain. It uses the CC0
// as a formal dedication to the public domain and in circumstances where
// a public domain is not usable.

function initConfigClients()
{
	var buttons = document.getElementsByClassName("delete");
	for (var index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addCCClickHandler(button);
	}
}

function addCCClickHandler(elem)
{
	elem.addEventListener("click", function() {
              if (!confirm(deleteMessage.replace("{0}", elem.getAttribute("client")))) {
                  event.preventDefault();
                  return false;
              }
        });
}

document.addEventListener("DOMContentLoaded", function() {
    initConfigClients();
}, true);

/* @license-end */
