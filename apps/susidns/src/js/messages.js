/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */
/* see also licenses/LICENSE-GPLv2.txt */

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

function ToggleStoragePathView() {
  var storagePathView = document.getElementById("storagepath");
  if (storagePathView != null) {
    storagePathView.classList.toggle("invisible");
  }
  clickableHeadline = document.getElementById("addrtitle");
  if (clickableHeadline != null) {
    clickableHeadline.classList.toggle("expanded");
  }

}

function ToggleAddFormTableView() {
  var buttonView = document.getElementById("addnewaddrbutton");
  if (buttonView != null) {
    buttonView.classList.toggle("invisible");
  }
  var tableView = document.getElementById("addnewaddrtable");
  if (tableView != null) {
    tableView.classList.toggle("invisible");
  }
  clickableForm = document.getElementById("addnewaddr");
  if (clickableForm != null) {
    clickableForm.classList.toggle("expanded");
  }
}

function ToggleImportFormTableView() {
  var buttonView = document.getElementById("importhostsform");
  if (buttonView != null) {
    buttonView.classList.toggle("invisible");
  }
  var tableView = document.getElementById("importhostsbuttons");
  if (tableView != null) {
    tableView.classList.toggle("invisible");
  }
  clickableForm2 = document.getElementById("importhosts");
  if (clickableForm2 != null) {
    clickableForm2.classList.toggle("expanded");
  }
}

function ToggleHowView(){
  var pHow = document.getElementsByClassName("howitworks");
  var i;
  for (i = 0; i < pHow.length; i++) {
    pHow[i].classList.toggle("invisible");
  }
  var idHow = document.getElementById("howitworks");
  if (idHow != null) {
    idHow.classList.toggle("expanded");
  }
}

function ToggleWhatView(){
  var pWhat = document.getElementsByClassName("whatitis");
  var i;
  for (i = 0; i < pWhat.length; i++) {
    pWhat[i].classList.toggle("invisible");
  }
  var idWhat = document.getElementById("whatitis");
  if (idWhat != null) {
    idWhat.classList.toggle("expanded");
  }
}

function initClickables() {

  /*Hide the storage path by default, show it if someone clicks on the header*/
  var storagePathView = document.getElementById("storagepath");
  if (storagePathView != null) {
    storagePathView.classList.add("invisible");
  }
  clickableHeadline = document.getElementById("addrtitle");
  if (clickableHeadline != null) {
    clickableHeadline.addEventListener('click', ToggleStoragePathView, true);
  }

  /*If the hostname field is empty, hide the add host form and show it when
  the user clicks on the form header.*/
  var d = document.getElementById("emptybook");
  if (d == null) {
    var x = document.getElementsByName("hostname");
    var i;
    for (i = 0; i < x.length; i++) {
      if (x[i].value == "") {
        var buttonView = document.getElementById("addnewaddrbutton");
        if (buttonView != null) {
          buttonView.classList.add("invisible");
        }
        var tableView = document.getElementById("addnewaddrtable");
        if (tableView != null) {
          tableView.classList.add("invisible");
        }
      }
    }
    clickableForm = document.getElementById("addnewaddr");
    if (clickableForm != null) {
      clickableForm.addEventListener('click', ToggleAddFormTableView, true);
    }
  }else{
    clickableForm = document.getElementById("addnewaddr");
    if (clickableForm != null) {
      clickableForm.classList.toggle("expanded");
    }
  }

  /* Set up the Import Hosts form to be collapsible.*/
  var ibuttonView = document.getElementById("importhostsform");
  if (ibuttonView != null) {
    ibuttonView.classList.add("invisible");
  }
  var itableView = document.getElementById("importhostsbuttons");
  if (itableView != null) {
    itableView.classList.add("invisible");
  }
  clickableForm2 = document.getElementById("importhosts");
  if (clickableForm2 != null) {
    clickableForm2.addEventListener('click', ToggleImportFormTableView, true);
  }
}

document.addEventListener("DOMContentLoaded", function() {
    document.body.addEventListener('click', HideMessages, true);
    initClickables();
}, true);

/* @license-end */
