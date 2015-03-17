@echo off
path %path%;c:\program Files\Java\jre6\bin;
echo ==========  Unpacking .jar libs, plz wait ...  ============
for %%i in (lib\*.pack) do unpack200 -r %%i lib\%%~ni
for %%i in (webapps\*.pack) do unpack200 -r %%i webapps\%%~ni
ren batch1.na StartI2P.bat
ren batch2.na EepGet.bat
ren i2psvc.ex_ i2psvc.exe
del ClickMe2UnPack.bat
