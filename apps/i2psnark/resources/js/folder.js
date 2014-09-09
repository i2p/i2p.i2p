function setupbuttons() {
	updatesetallbuttons();
	var form = document.forms[0];
	form.savepri.disabled = true;
	form.savepri.className = 'foo';
}

function priorityclicked() {
	updatesetallbuttons();
	var form = document.forms[0];
	form.savepri.disabled = false;
	form.savepri.className = 'accept';
}

function updatesetallbuttons() {
	var notNorm = false;
	var notHigh = false;
	var notSkip = false;
	var form = document.forms[0];
	for(i = 0; i < form.elements.length; i++) {
		var elem = form.elements[i];
		if (elem.type == 'radio') {
			if (!elem.checked) {
				if (elem.className == 'prinorm')
					notNorm = true;
				else if (elem.className == 'prihigh')
					notHigh = true;
				else
					notSkip = true;
			}
		}
	}
	if (notNorm)
	    document.getElementById('setallnorm').className = 'control';
	else
	    document.getElementById('setallnorm').className = 'controld';
	if (notHigh)
	    document.getElementById('setallhigh').className = 'control';
	else
	    document.getElementById('setallhigh').className = 'controld';
	if (notSkip)
	    document.getElementById('setallskip').className = 'control';
	else
	    document.getElementById('setallskip').className = 'controld';
}

function setallnorm() {
	var form = document.forms[0];
	for(i = 0; i < form.elements.length; i++) {
		var elem = form.elements[i];
		if (elem.type == 'radio') {
			if (elem.className === 'prinorm')
				elem.checked = true;
		}
	}
	document.getElementById('setallnorm').className = 'controld';
	document.getElementById('setallhigh').className = 'control';
	document.getElementById('setallskip').className = 'control';
	form.savepri.disabled = false;
	form.savepri.className = 'accept';
}

function setallhigh() {
	var form = document.forms[0];
	for(i = 0; i < form.elements.length; i++) {
		var elem = form.elements[i];
		if (elem.type == 'radio') {
			if (elem.className === 'prihigh')
				elem.checked = true;
		}
	}
	document.getElementById('setallnorm').className = 'control';
	document.getElementById('setallhigh').className = 'controld';
	document.getElementById('setallskip').className = 'control';
	form.savepri.disabled = false;
	form.savepri.className = 'accept';
}

function setallskip() {
	var form = document.forms[0];
	for(i = 0; i < form.elements.length; i++) {
		var elem = form.elements[i];
		if (elem.type == 'radio') {
			if (elem.className === 'priskip')
				elem.checked = true;
		}
	}
	document.getElementById('setallnorm').className = 'control';
	document.getElementById('setallhigh').className = 'control';
	document.getElementById('setallskip').className = 'controld';
	form.savepri.disabled = false;
	form.savepri.className = 'accept';
}
