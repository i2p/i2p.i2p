/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

/**
 * Drop a link anywhere on the page and we will reject it.
 *
 * Drop a file anywhere on the page and we will
 * hopefully convince you to drop it on the newFile input.
 *
 * ref: https://developer.mozilla.org/en-US/docs/Web/API/HTML_Drag_and_Drop_API/File_drag_and_drop
 *
 * @since 0.9.62
 */
function initDND()
{
	var form2 = document.getElementById("new_filename");
	if (form2 != null) {
		var div = document.getElementById("page");
		var addbutton = document.getElementById("new_upload");

		div.addEventListener("drop", function(event) {
			var name = "";
			var isURL = false;
			var isDir = false;
                        // chrome returning 0-length arrays for files?
			if (event.dataTransfer.items && event.dataTransfer.items.length > 0) {
				// Use DataTransferItemList interface to access the file
				var item = event.dataTransfer.items[0];
				// Chrome bug
				// https://bugs.chromium.org/p/chromium/issues/detail?id=1363757
				// sometimes undefined for directories, throws uncaught TypeError
				if (((typeof item.kind) !== "undefined") && (item.kind === "file")) {
					var file = item.getAsFile();
					if (file.size == 0)
						isDir = true;
					name = file.name;
				} else {
					// If dropped items aren't files, maybe they are URLs
					// we're going here in chrome for files usually
					name = event.dataTransfer.getData("URL");
					if (name.length > 0) {
						isURL = true;
					}
					// else chrome bug
					// https://bugs.chromium.org/p/chromium/issues/detail?id=1363757
				}
			} else if (event.dataTransfer.files && event.dataTransfer.files.length > 0) {
				// Use DataTransfer interface to access the file(s)
				var file = event.dataTransfer.files[0];
				if (file.size == 0)
					isDir = true;
				name = file.name;
			} else {
				// else chrome bug
				// https://bugs.chromium.org/p/chromium/issues/detail?id=1363757
			}
			if (name.length > 0) {
				if (isURL) {
					form2.classList.remove("highlight");
					event.preventDefault();
					event.dataTransfer.dropEffect = "none";
				} else {
					// handle name in form 2
					if (event.target.id === "new_filename") {
						if (isDir) {
							event.preventDefault();
							addbutton.classList.remove("highlight");
							event.dataTransfer.dropEffect = "none";
							alert("Must be a file");
						} else {
							addbutton.classList.add("highlight");
						}
					} else {
						event.preventDefault();
						addbutton.classList.remove("highlight");
						event.dataTransfer.dropEffect = "none";
						alert("Drop a file in the add attachment box");
					}
				}
			} else {
				// Chrome bug
				// https://bugs.chromium.org/p/chromium/issues/detail?id=1363757
				event.dataTransfer.dropEffect = "none";
				alert("File drag and drop not supported on this browser");
			}
		});

		div.addEventListener("dragover", function(event) {
			event.preventDefault();
			if (event.dataTransfer.items && event.dataTransfer.items.length > 0) {
				var item = event.dataTransfer.items[0];
				// needed for Chrome
				if (((typeof item.kind) !== "undefined") && (item.kind === "file")) {
					event.dataTransfer.dropEffect = "copy";
				} else {
					event.dataTransfer.dropEffect = "link";
				}
			} else {
				event.dataTransfer.dropEffect = "copy";
			}
		});

		div.addEventListener("dragenter", function(event) {
			event.preventDefault();
			// expand the add section, scroll to view, and highlight the correct input
			if (event.dataTransfer.items && event.dataTransfer.items.length > 0) {
				var item = event.dataTransfer.items[0];
				if (((typeof item.kind) !== "undefined") && (item.kind === "file")) {
					form2.classList.add("highlight");
					form2.focus();
				} else {
					form2.classList.remove("highlight");
					form2.blur();
				}
			} else {
				form2.classList.add("highlight");
				form2.focus();
			}
			form2.scrollIntoView(true);
		});

		form2.addEventListener("change", function(event) {
			if (form2.value.length > 0) {
				addbutton.classList.add("highlight");
			} else {
				addbutton.classList.remove("highlight");
			}
			form2.classList.remove("highlight");
		});

		form2.addEventListener("blur", function(event) {
			if (form2.value.length > 0) {
				addbutton.classList.add("highlight");
			} else {
				addbutton.classList.remove("highlight");
			}
			form2.classList.remove("highlight");
		});

	}
}

document.addEventListener("DOMContentLoaded", function() {
    initDND();
}, true);

/* @license-end */
