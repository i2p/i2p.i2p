/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

// This component is dedicated to the public domain. It uses the CC0
// as a formal dedication to the public domain and in circumstances where
// a public domain is not usable.

function swapStyleSheet(theme) {
    // https://stackoverflow.com/questions/14292997/changing-style-sheet-javascript
    document.getElementById("pagestyle").setAttribute("href", "/themes/console/" + theme + "/console.css");
}

function initThemeSwitcher() {
    var dark = document.getElementById("dark");
    dark.onclick = function() {
        swapStyleSheet("dark");
    }
    var light = document.getElementById("light");
    light.onclick = function() {
        swapStyleSheet("light");
    }
    // https://stackoverflow.com/questions/56393880/how-do-i-detect-dark-mode-using-javascript
    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
        // dark mode
        swapStyleSheet("dark");
        dark.checked = true;
        light.checked = false;
    }
}

document.addEventListener("DOMContentLoaded", function() {
    initThemeSwitcher();
}, true);

/* @license-end */
