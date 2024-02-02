/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

// called from iframed.js

function setupFrame() {
	var frames = document.getElementsByClassName("iframedsusi");
	for(index = 0; index < frames.length; index++)
	{
		var frame = frames[index];
		frame.addEventListener("load", function() {
			// old way, iframed.js. we use this as a backup in case
			// the js injection didn't work

			resizeFrame(frame);

			// new way, iframe-resizer

			// By default the height of the iFrame is calculated by converting the margin of the body to px and then adding the top and bottom figures to the offsetHeight of the body tag.
			// In cases where CSS styles causes the content to flow outside the body you may need to change this setting to one of the following options.
			// If the default option doesn't work then the best solutions is to use either taggedElement, or lowestElement.
			// The **lowestElement** option is the most reliable way of determining the page height.
			// However, it does have a performance impact, as it requires checking the position of every element on the page.
			iFrameResize({ log: false, heightCalculationMethod: 'lowestElement' }, frame)
		}, true);
	}
}

/* @license-end */
