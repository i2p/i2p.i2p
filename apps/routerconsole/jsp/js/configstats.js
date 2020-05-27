function initConfigStats()
{
	checkAll = false;
	var buttons = document.getElementsByClassName("script");
	for(index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		// toggle-foo
		var group = button.id.substring(7);
		addCSClickHandler(button, group);
	}
}
function addCSClickHandler(elem, category)
{
	elem.addEventListener("click", function(){toggleAll(category); event.preventDefault(); return false;});
}
function toggleAll(category)
{
	var inputs = document.getElementsByTagName("input");
	for(index = 0; index < inputs.length; index++)
	{
		var classes = inputs[index].className.split(' ');
		for (var idx = 0; idx < classes.length; idx++)
		{
			if(classes[idx] == category)
			{
				if(inputs[index].checked == 0)
				{
					inputs[index].checked = 1;
				}
				else if(inputs[index].checked == 1)
				{
					inputs[index].checked = 0;
				}
			}
		}
		if(category == '*')
		{
			if (inputs[index].id == 'enableFull') {
				// don't toggle this one
				continue;
			}
			if (checkAll == false)
			{
				inputs[index].checked = 1;
			}
			else if (checkAll == true)
			{
				inputs[index].checked = 0;
			}
		}
	}
	if(category == '*')
	{
		if (checkAll == false)
		{
			checkAll = true;
		}
		else if (checkAll == true)
		{
			checkAll = false;
		}
	}
}

document.addEventListener("DOMContentLoaded", function() {
    initConfigStats();
}, true);
