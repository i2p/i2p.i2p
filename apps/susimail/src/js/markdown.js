/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

function initMarkdown() {
	var mailbodies = document.getElementsByClassName("mailbody");
	for (var index = 0; index < mailbodies.length; index++)
	{
		var mailbody = mailbodies[index];
		if (mailbody.nodeName === "P") {
			var converter = new Markdown.Converter();
			mailbody.innerHTML = converter.makeHtml(mailbody.innerHTML);
		}
	}
}

document.addEventListener("DOMContentLoaded", function() {
    initMarkdown();
}, true);

/* @license-end */
