function initStats()
{
	var buttons = document.getElementsByClassName("onchange");
	for(index = 0; index < buttons.length; index++)
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
