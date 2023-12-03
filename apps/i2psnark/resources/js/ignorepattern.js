const validateNewTorrentForm=(evt)=>{
  var regexStr = document.getElementById('nofilter_ignorePattern').value;
  try { new RegExp(regexStr); return true; }
  catch(err) { alert(err.message); evt.preventDefault(); }
}

document.addEventListener("DOMContentLoaded", function() {
  document.getElementById('newTorrentForm').addEventListener("submit", validateNewTorrentForm);
}, true);
