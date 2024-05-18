/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

const setupbuttons=()=>{
	let sp = document.forms[0].savepri;
	if ( sp ) updatesetallbuttons(), sp.disabled = true, sp.className = 'disabled';

	var buttons = document.getElementsByClassName("prihigh");
	for (var index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
                if (!button.disabled)
			addClickHandler(button);
	}
	buttons = document.getElementsByClassName("prinorm");
	for (var index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
                if (!button.disabled)
	 		addClickHandler(button);
	}
	buttons = document.getElementsByClassName("priskip");
	for (var index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
                if (!button.disabled)
			addClickHandler(button);
	}
	var button = document.getElementById('setallhigh');
	if (!button.disabled) {
		button.addEventListener("click", function() {
			setallhigh();
			event.preventDefault();
	        });
	}
	button = document.getElementById('setallnorm');
	if (!button.disabled) {
		button.addEventListener("click", function() {
			setallnorm();
			event.preventDefault();
	        });
	}
	button = document.getElementById('setallskip');
	if (!button.disabled) {
		button.addEventListener("click", function() {
			setallskip();
			event.preventDefault();
	        });
	}
}

const priorityclicked=()=>{
	let sp = document.forms[0].savepri;
	if ( sp ) updatesetallbuttons(), sp.disabled = false, sp.className = 'accept';
}

const updatesetallbuttons=()=>{
	let notNorm = true, notHigh = true, notSkip = true, i = 0, elems = document.forms[0].elements;	
	for (var len = elems.length ; i < len && (notNorm || notHigh || notSkip) ; ) {
		var ele = elems[i++];
		if (ele.type == 'radio' && !ele.checked) {
			if (ele.className == 'prinorm') notNorm = false; 
			else if (ele.className == 'prihigh') notHigh = false;
			else notSkip = false;
		}
	}
	document.getElementById('setallnorm').className = notNorm ? 'controld' : 'control';
	document.getElementById('setallhigh').className = notHigh ? 'controld' : 'control';
	document.getElementById('setallskip').className = notSkip ? 'controld' : 'control';
}

const setallnorm=()=>{
	let i = 0, form = document.forms[0];
	for (var elems = form.elements, len = elems.length ; i < len ; ) {
		var ele = elems[i++];
		if (ele.type == 'radio' && ele.className === 'prinorm') ele.checked = true;
	}
	document.getElementById('setallnorm').className = 'controld';
	document.getElementById('setallhigh').className = 'control';
	document.getElementById('setallskip').className = 'control';
	form.savepri.disabled = false;
	form.savepri.className = 'accept';
}

const setallhigh=()=>{
	let i = 0, form = document.forms[0];
	for (var elems = form.elements, len = elems.length ; i < len ; ) {
		var ele = elems[i++];
		if (ele.type == 'radio' && ele.className === 'prihigh') ele.checked = true;
	}
	document.getElementById('setallnorm').className = 'control';
	document.getElementById('setallhigh').className = 'controld';
	document.getElementById('setallskip').className = 'control';
	form.savepri.disabled = false;
	form.savepri.className = 'accept';
}

const setallskip=()=>{
	let i = 0, form = document.forms[0];
	for (var elems = form.elements, len = elems.length ; i < len ; ) {
		var ele = elems[i++];
		if (ele.type == 'radio' && ele.className === 'priskip') ele.checked = true;
	}
	document.getElementById('setallnorm').className = 'control';
	document.getElementById('setallhigh').className = 'control';
	document.getElementById('setallskip').className = 'controld';
	form.savepri.disabled = false;
	form.savepri.className = 'accept';
}

function addClickHandler(elem)
{
	elem.addEventListener("click", function() {
		priorityclicked();
        });
}

document.addEventListener("DOMContentLoaded", function() {
    setupbuttons();
}, true);

/* @license-end */
