@echo off
rem Builds nuusage.dll into src\main\resources\natives\win-x64\.
rem Requires Visual Studio (vcvars64) + the Windows SDK.
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
cl /nologo /EHsc /O2 /MT /W3 nuusage.cpp /link /DLL /out:..\resources\natives\win-x64\nuusage.dll windowsapp.lib
