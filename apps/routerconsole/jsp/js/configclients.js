function init()
{
	var buttons = document.getElementsByClassName("delete");
	for(index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler(button);
	}
}

function addClickHandler(elem)
{
	elem.addEventListener("click", function() {
              if (!confirm(deleteMessage.replace("{0}", elem.getAttribute("client")))) {
                  event.preventDefault();
                  return false;
              }
        });
}

document.addEventListener("DOMContentLoaded", function() {
    init();
}, true);
