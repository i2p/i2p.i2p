function initButtons() {
	var buttons = document.getElementsByClassName("delete1");
	for(index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler1(button);
	}
	buttons = document.getElementsByClassName("markall");
	for(index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler2(button);
	}
	buttons = document.getElementsByClassName("clearselection");
	for(index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler3(button);
	}
	// TODO delete button, to show really-delete section or popup
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
		var form = document.forms[0];
		form.delete.disabled = false;
		form.markall.disabled = true;
		form.clearselection.disabled = true;
		var buttons = document.getElementsByClassName("delete1");
		for(index = 0; index < buttons.length; index++)
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
		var form = document.forms[0];
		form.delete.disabled = true;
		form.markall.disabled = false;
		form.clearselection.disabled = false;
		var buttons = document.getElementsByClassName("delete1");
		for(index = 0; index < buttons.length; index++)
		{
			var button = buttons[index];
			button.checked = false;
		}
		event.preventDefault();
	});
}

function deleteboxclicked() {
	var hasOne = false;
	var hasAll = true;
	var hasNone = true;
	var form = document.forms[0];
	for(i = 0; i < form.elements.length; i++) {
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
