:: Files required:
:: ResourceHacker.exe from http://www.angusj.com/resourcehacker/
:: VersionInfo_template.rc copied from installer/lib/izpack
:: console.ico copied from installer/resources
:: the unsigned i2pinstall_0.9.xx_windows.exe
::
:: the original file will be renamed to i2pinstall_0.9.xx_windows_unsigned.exe
:: the output will be the same as the original file name
:: on success, sign it.
::
set VER=0
set FULLVER=2.%VER%.0
set INFILE=i2pinstall_%FULLVER%_windows.exe
set UNSIGNED=i2pinstall_%FULLVER%_windows_unsigned.exe
set TEMPFILE=i2pinstall_%FULLVER%_windows_temp.exe
set RCFILE=VersionInfo_%FULLVER%.rc
set RESFILE=VersionInfo_%FULLVER%.res
rename %INFILE% %UNSIGNED%
powershell -Command "(gc VersionInfo_template.rc) -replace 'I2PVER', '%VER%' | Out-File %RCFILE%"
ResourceHacker.exe -open %RCFILE% -save %RESFILE% -action compile
ResourceHacker.exe -open %UNSIGNED% -save %TEMPFILE% -action addoverwrite -res %RESFILE% -mask VERSIONINFO,,
ResourceHacker.exe -open %TEMPFILE% -save %INFILE% -action addoverwrite -res console.ico -mask ICONGROUP,159,
del %TEMPFILE% %RCFILE% %RESFILE%
