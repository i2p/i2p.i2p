function init()
{
	var buttons = document.getElementsByClassName("onchange");
	for(index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addChangeHandler(button);
	}
}

function addChangeHandler(elem)
{
	elem.addEventListener("change", function() {
		location.href=event.target.value;
	});
}

document.addEventListener("DOMContentLoaded", function() {
    init();
}, true);
