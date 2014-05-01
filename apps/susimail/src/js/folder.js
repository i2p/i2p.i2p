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
