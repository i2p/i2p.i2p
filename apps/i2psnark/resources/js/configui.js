/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

// This component is dedicated to the public domain. It uses the CC0
// as a formal dedication to the public domain and in circumstances where
// a public domain is not usable.

var __i2psnark_oldTheme = "ubergine";
var __i2psnark_change = false;

function swapStyleSheet(theme) {
    // https://stackoverflow.com/questions/14292997/changing-style-sheet-javascript
    document.getElementById("pagestyle").setAttribute("href", "/i2psnark/.resources/themes/" + theme + "/snark.css");
}

function initThemeSwitcher() {
    var theme = document.getElementById("theme");
    if (theme == null) {
        return;
    }
    __i2psnark_oldtheme = theme.value;
    theme.onclick = function() {
        if (__i2psnark_change) {
            swapStyleSheet(theme.value);
        } else {
            // skip the first click to avoid the flash
            __i2psnark_change = true;
        }
    }
}

document.addEventListener("DOMContentLoaded", function() {
    initThemeSwitcher();
}, true);

/* @license-end */
