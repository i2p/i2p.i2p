rem before calling make sure you have msys and mingw 's "bin" path 
rem in your current searching path
rem type "set path" to check 
@echo off
set Callfrom=%cd%
sh --login %cd%\bmsg.sh