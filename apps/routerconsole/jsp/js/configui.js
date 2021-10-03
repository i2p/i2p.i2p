/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

// This component is dedicated to the public domain. It uses the CC0
// as a formal dedication to the public domain and in circumstances where
// a public domain is not usable.

var oldTheme = "light";

function swapStyleSheet(theme) {
    // https://stackoverflow.com/questions/14292997/changing-style-sheet-javascript
    document.getElementById("pagestyle").setAttribute("href", "/themes/console/" + theme + "/console.css");
    document.getElementById("i2plogo").setAttribute("src", "/themes/console/" + theme + "/images/i2plogo.png");
}

function disableButtons(disabled) {
    document.getElementById("themeApply").disabled = disabled;
    document.getElementById("themeCancel").disabled = disabled;
}

function resetStyleSheet() {
    swapStyleSheet(oldTheme);
    document.getElementById("themeForm").reset();
    disableButtons(true);
}

function initThemeSwitcher() {
    var dark = document.getElementById("dark");
    if (dark == null) {
        return;
    }
    dark.onclick = function() {
        swapStyleSheet("dark");
        disableButtons(false);
    }
    if (dark.checked) {
        oldTheme = "dark";
    }
    var light = document.getElementById("light");
    light.onclick = function() {
        swapStyleSheet("light");
        disableButtons(false);
    }
    var apply = document.getElementById("themeApply");
    apply.setAttribute("disabled", true);
    var cancel = document.getElementById("themeCancel");
    cancel.setAttribute("disabled", true);
    cancel.onclick = function() {
        resetStyleSheet();
    }
    document.getElementById("themebox1").onclick = function() { disableButtons(false); }
    document.getElementById("themebox2").onclick = function() { disableButtons(false); }
    document.getElementById("themebox3").onclick = function() { disableButtons(false); }
}

document.addEventListener("DOMContentLoaded", function() {
    initThemeSwitcher();
}, true);

/* @license-end */
