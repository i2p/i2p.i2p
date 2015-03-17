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
:: Note: We should not use the group name "Users" since this group will not
:: exist on non-English versions of Windows.
::
:: S-1-5-32-545 = Users (en). Benutzer (de), etc.
::
:: Specifying the SID will work on ALL versions of Windows.
:: List of well-known SIDs at http://support.microsoft.com/kb/243330/en-us
::
echo Y|icacls %1 /grant  *S-1-5-32-545:F /c /t > %1%\fixperms.log
