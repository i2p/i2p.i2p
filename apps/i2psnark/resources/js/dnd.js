/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

/**
 * Drop a link anywhere on the page and we will open the add torrent section
 * and put it in the add torrent form.
 *
 * Drop a .torrent file anywhere on the page and we will open the add torrent section
 * and hopefully convince you to drop it on the newFile input.
 *
 * ref: https://developer.mozilla.org/en-US/docs/Web/API/HTML_Drag_and_Drop_API/File_drag_and_drop
 *
 * @since 0.9.62
 */
function initDND()
{
	var div = document.getElementById("page");
	var add = document.getElementById("toggle_addtorrent");
	if (add != null) {
		// we are on page one
		var create = document.getElementById("toggle_createtorrent");
		var form1 = document.getElementById("nofilter_newURL");
		var form2 = document.getElementById("newFile");
		var addbutton = document.getElementById("addButton");

		div.addEventListener("drop", function(event) {
			var name = "";
			var isURL = false;
			var isDir = false;
			// chrome returning 0-length arrays for files?
			if (event.dataTransfer.items && event.dataTransfer.items.length > 0) {
				// Use DataTransferItemList interface to access the file
				// DO NOT LOG TO CONSOLE HERE IT ZEROS OUT ARRAY
				// https://howtojs.io/empty-files-in-event-datatransfer-in-drop-event-in-javascript/
				// https://stackoverflow.com/questions/11573710/event-datatransfer-files-is-empty-when-ondrop-is-fired
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
					// set name in form 1
					form2.classList.remove("highlight");
					event.preventDefault();
					// prevent inadvertent drag-from-self
					var url = new URL(name);
					var us = new URL(document.location.href);
					if (url.origin === us.origin) {
						form1.value = "";
						event.dataTransfer.dropEffect = "none";
					} else {
						form1.value = name;
					}
				} else {
					// handle name in form 2
					form1.value = "";
					form1.classList.remove("highlight");
					if (event.target.id === "newFile") {
						if (isDir) {
							event.preventDefault();
							addbutton.classList.remove("highlight");
							event.dataTransfer.dropEffect = "none";
							alert("Must be a .torrent file");
						} else {
							if (!name.endsWith('.torrent')) {
								event.preventDefault();
								addbutton.classList.remove("highlight");
								event.dataTransfer.dropEffect = "none";
								alert("Must be a .torrent file");
							} else {
								addbutton.classList.add("highlight");
							}
						}
					} else {
						event.preventDefault();
						addbutton.classList.remove("highlight");
						event.dataTransfer.dropEffect = "none";
						if (!name.endsWith('.torrent')) {
							alert("Must be a .torrent file");
						} else {
							alert("Drop a .torrent file in the torrent file box");
						}
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
					form1.classList.remove("highlight");
					form1.blur();
					form1.value = "";
					form2.classList.add("highlight");
					form2.focus();
				} else {
					form1.classList.add("highlight");
					form1.focus();
					form2.classList.remove("highlight");
					form2.blur();
				}
			} else {
				form1.classList.remove("highlight");
				form1.blur();
				form1.value = "";
				form2.classList.add("highlight");
				form2.focus();
			}
			create.checked = false;
			add.checked = true;
			add.scrollIntoView(true);
		});

		form1.addEventListener("change", function(event) {
			if (form1.value.length > 0) {
				form1.classList.remove("highlight");
				form2.classList.remove("highlight");
				addbutton.classList.add("highlight");
			} else {
				addbutton.classList.remove("highlight");
			}
		});

		form2.addEventListener("change", function(event) {
			if (form2.value.length > 0) {
				addbutton.classList.add("highlight");
			} else {
				addbutton.classList.remove("highlight");
			}
			form1.classList.remove("highlight");
			form2.classList.remove("highlight");
		});

		form1.addEventListener("blur", function(event) {
			if (form1.value.length > 0) {
				addbutton.classList.add("highlight");
			} else {
				addbutton.classList.remove("highlight");
			}
			form1.classList.remove("highlight");
			form2.classList.remove("highlight");
		});

		form2.addEventListener("blur", function(event) {
			if (form2.value.length > 0) {
				addbutton.classList.add("highlight");
			} else {
				form1.classList.add("highlight");
				addbutton.classList.remove("highlight");
			}
			form1.classList.remove("highlight");
			form2.classList.remove("highlight");
		});

	} else {
		// we are not on page one
		// TODO
		div.addEventListener("drop", function(event) {
			event.preventDefault();
			event.dataTransfer.dropEffect = "none";
			alert("Go to page 1 to drag and drop");
		});
	}
}

document.addEventListener("DOMContentLoaded", function() {
    initDND();
}, true);

/* @license-end */
