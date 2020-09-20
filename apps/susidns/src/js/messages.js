/* #license http://www.jclark.com/xml/copying.txt Expat */

function HideMessages() {
  var hideableMessages = document.getElementsByClassName("messages");
  if (hideableMessages.length > 0) {
    for (key in hideableMessages) {
      if (hideableMessages[key] != null) {
        hideableMessages[key].remove()
      }
    };
  }
  var hideableMessage = document.getElementById("messages");
  if (hideableMessage != null) {
    hideableMessage.remove()
  }
}

document.addEventListener("DOMContentLoaded", function() {
    document.body.addEventListener('click', HideMessages, true);
}, true);

/* @license-end */
