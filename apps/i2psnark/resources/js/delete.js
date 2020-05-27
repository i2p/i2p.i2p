function initDelete()
{
	var buttons = document.getElementsByClassName("delete1");
	for(index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler1(button);
	}
	buttons = document.getElementsByClassName("delete2");
	for(index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler2(button);
	}
}

function addClickHandler1(elem)
{
	elem.addEventListener("click", function() {
		if (!confirm(deleteMessage1.replace("{0}", elem.getAttribute("client")))) {
			event.preventDefault();
			return false;
		}
	});
}

function addClickHandler2(elem)
{
	elem.addEventListener("click", function() {
		if (!confirm(deleteMessage2.replace("{0}", elem.getAttribute("client")))) {
			event.preventDefault();
			return false;
		}
	});
}

document.addEventListener("DOMContentLoaded", function() {
    initDelete();
}, true);
