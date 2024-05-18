/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

function initCopyLink() {
        var buttons = document.getElementsByClassName("tunnelHostnameCopy");
        for (var index = 0; index < buttons.length; index++) {
                var button = buttons[index];
                addClickHandler(button);
        }
}

function addClickHandler(elem) {
        elem.addEventListener("click", function() {
              let prevElem = getPreviousHelper(elem).firstElementChild;
              prevElem.select();
              document.execCommand("copy");
              alert("Copied the helper to the clipboard", prevElem.value);
        });
}

document.addEventListener("DOMContentLoaded", function() {
    initCopyLink();
}, true);

var getPreviousHelper = function (elem) {
        var selector = ".tunnelPreview";
        var parent = elem.parentElement
        var sibling = parent.previousElementSibling;
        while (sibling) {
                if (sibling.matches(selector)) return sibling;
                sibling = sibling.previousElementSibling;
        }
        return sibling
};


/* @license-end */
