/* @license http://creativecommons.org/publicdomain/zero/1.0/legalcode CC0-1.0 */

// This component is dedicated to the public domain. It uses the CC0
// as a formal dedication to the public domain and in circumstances where
// a public domain is not usable.

// reload the sidebar after a POST, because the form handling happens
// while the page is rendering

function reloadSidebarOnce()
{
	ajax("/xhr1.jsp?requestURI=/configupdate", "xhr", 0);
}

document.addEventListener("DOMContentLoaded", function() {
    reloadSidebarOnce();
}, true);

/* @license-end */
