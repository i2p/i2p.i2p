:: Fix Vista permission problems
:: From http://www.nabble.com/Classpath-security-issues-on-Vista-td22456230.html
::
:: 'echo Y' to get past the 'are you sure' question...
:: cacls requires it on XP, icacls doesnt appear so, but can't hurt
:: F : full control
:: /c : continue on error
:: /q : quiet
:: /t : recursive
::
echo Y|icacls %1 /grant Users:F /c /t > %1%\fixperms.log
