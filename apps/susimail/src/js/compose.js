let beforePopup = true;
window.addEventListener('beforeunload', (e)=>{if (beforePopup) e.returnValue=true;} );
