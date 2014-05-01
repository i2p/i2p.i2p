// onbeforeunload() is in the servlet because it has a translated string

function cancelPopup() {
	window.onbeforeunload = null;
}
