/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

function initButtons() {
	var buttons = document.getElementsByClassName("delete1");
	for (var index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler1(button);
	}
	buttons = document.getElementsByClassName("markall");
	for (var index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler2(button);
	}
	buttons = document.getElementsByClassName("clearselection");
	for (var index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler3(button);
	}
	// TODO delete button, to show really-delete section or popup

	buttons = document.getElementsByClassName("tdclick");
	for (var index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler4(button);
	}
}

function addClickHandler1(elem)
{
	elem.addEventListener("click", function() {
		deleteboxclicked();
	});
}

function addClickHandler2(elem)
{
	elem.addEventListener("click", function() {
		var form = document.forms[3];
		form.delete.disabled = false;
		form.markall.disabled = true;
		form.clearselection.disabled = false;
		var buttons = document.getElementsByClassName("delete1");
		for (var index = 0; index < buttons.length; index++)
		{
			var button = buttons[index];
			button.checked = true;
		}
		event.preventDefault();
	});
}

function addClickHandler3(elem)
{
	elem.addEventListener("click", function() {
		var form = document.forms[3];
		form.delete.disabled = true;
		form.markall.disabled = false;
		form.clearselection.disabled = true;
		var buttons = document.getElementsByClassName("delete1");
		for (var index = 0; index < buttons.length; index++)
		{
			var button = buttons[index];
			button.checked = false;
		}
		event.preventDefault();
	});
}

function addClickHandler4(elem)
{
	elem.addEventListener("click", function() {
		document.location = elem.getAttribute("onclickloc");
	});
}

function deleteboxclicked() {
	var hasOne = false;
	var hasAll = true;
	var hasNone = true;
	var form = document.forms[3];
	for (var i = 0; i < form.elements.length; i++) {
		var elem = form.elements[i];
		if (elem.type == 'checkbox') {
			if (elem.checked) {
				hasOne = true;
				hasNone = false;
			} else {
				hasAll = false;
			}
		}
	}
	form.delete.disabled = !hasOne;
	form.markall.disabled = hasAll;
	form.clearselection.disabled = hasNone;
}

document.addEventListener("DOMContentLoaded", function() {
    initButtons();
    deleteboxclicked();
}, true);

/* @license-end */
