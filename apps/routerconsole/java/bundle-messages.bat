@echo off
set Callfrom=%cd%
set Paras=%1

rem before calling make sure you have msys and mingw 's "bin" path 
rem in your current searching path
rem type "set path" to check 
if not exist ..\locale\*.only goto updateALL

rem put a messages_xx.only(eg messages_zh.only) into locale folder
rem this script will only touch the po file(eg zh) you specified, leaving other po files untact.
echo [tip] Puting a messages_{LangCode}.only (eg messages_zh.only) 
echo   into locale folder will restrict the PO file update
echo   process to your choosen language ONLY.
for %%i in (..\locale\*.only) do set PO=%%~ni
echo [info] %PO%.only found, will ONLY update file : %PO%.po
for %%i in (..\locale\*.po) do if not %%~ni==%PO% ren %%i %%~ni.po-

call sh --login %cd%\bmsg.sh

for %%i in (..\locale\*.po-) do if not %%~ni==%PO% ren %%i %%~ni.po
goto end

:updateALL
call sh --login %cd%\bmsg.sh

:end
echo End of Message Bundling