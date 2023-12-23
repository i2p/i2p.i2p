/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

/*
 * Adapted from:
 * https://stackoverflow.com/questions/454202/creating-a-textarea-with-auto-resize
 */
function initTextarea() {
	const tx = document.getElementById("new_text");
	tx.removeAttribute("rows");
	tx.style.height = 0;
	tx.setAttribute("style", "height:" + Math.max(225, tx.scrollHeight) + "px;overflow-y:hidden;");
	tx.addEventListener("input", onTextareaInput, false);
}

function onTextareaInput() {
	this.style.height = 0;
	this.style.height = Math.max(225, this.scrollHeight) + "px";
}

document.addEventListener("DOMContentLoaded", function() {
    initTextarea();
}, true);

/* @license-end */
