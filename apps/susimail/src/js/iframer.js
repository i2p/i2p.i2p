/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

// called from iframed.js

function setupFrame() {
	const frames = document.getElementsByClassName("iframedsusi");
	for (let index = 0; index < frames.length; index++) {
		const frame = frames[index];
		// Mail is sandboxed without scripts. Resize it from the trusted parent.
		function resizeMailFrame() {
			try {
				if (frame.contentDocument && frame.contentDocument.body)
					resizeFrame(frame);
			} catch (e) {
				// A link may navigate the frame to another origin.
			}
		}
		frame.addEventListener("load", function() {
			resizeMailFrame();
			try {
				if (frame.contentDocument)
					frame.contentDocument.addEventListener("load", resizeMailFrame, true);
			} catch (e) {}
		}, true);
		window.addEventListener("resize", resizeMailFrame);
		resizeMailFrame();
	}
}

/* @license-end */
