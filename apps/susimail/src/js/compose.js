let beforePopup = true;
window.addEventListener('beforeunload', (e)=>{if (beforePopup) e.returnValue=true;} );

function initPopup() {
	var buttons = document.getElementsByClassName("beforePopup");
	for(index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler5(button);
	}
}

function addClickHandler5(elem)
{
	elem.addEventListener("click", function() {
		beforePopup = false;
	});
}

document.addEventListener("DOMContentLoaded", function() {
    initPopup();
}, true);
