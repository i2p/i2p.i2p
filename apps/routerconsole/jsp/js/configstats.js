/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

// This component is dedicated to the public domain. It uses the CC0
// as a formal dedication to the public domain and in circumstances where
// a public domain is not usable.

var __configstats_checkAll = false;

function initConfigStats()
{
	var buttons = document.getElementsByClassName("script");
	for (var index = 0; index < buttons.length; index++)
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
	for (var index = 0; index < inputs.length; index++)
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
			if (__configstats_checkAll == false)
			{
				inputs[index].checked = 1;
			}
			else if (__configstats_checkAll == true)
			{
				inputs[index].checked = 0;
			}
		}
	}
	if(category == '*')
	{
		if (__configstats_checkAll == false)
		{
			__configstats_checkAll = true;
		}
		else if (__configstats_checkAll == true)
		{
			__configstats_checkAll = false;
		}
	}
}

document.addEventListener("DOMContentLoaded", function() {
    initConfigStats();
}, true);

/* @license-end */
