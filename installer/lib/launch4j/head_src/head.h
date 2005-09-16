/*

 	launch4j :: Cross-platform Java application wrapper for creating Windows native executables
	Copyright (C) 2004-2005 Grzegorz Kowal

	Compiled with Mingw port of GCC, Bloodshed Dev-C++ IDE (http://www.bloodshed.net/devcpp.html)

	This library is free software; you can redistribute it and/or
	modify it under the terms of the GNU Lesser General Public
	License as published by the Free Software Foundation; either
	version 2.1 of the License, or (at your option) any later version.

	This library is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
	Lesser General Public License for more details.

	You should have received a copy of the GNU Lesser General Public
	License along with this library; if not, write to the Free Software
	Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

*/

#ifndef _LAUNCH4J_HEAD__INCLUDED_
#define _LAUNCH4J_HEAD__INCLUDED_

#define WIN32_LEAN_AND_MEAN		// VC - Exclude rarely-used stuff from Windows headers

// Windows Header Files:
#include <windows.h>

// C RunTime Header Files
#include <stdlib.h>
#include <malloc.h>
#include <memory.h>
#include <tchar.h>
#include <shellapi.h>
#include <direct.h>
#include <stdio.h>
#include <sys/stat.h>
#include <io.h>
#include <process.h>

#define NO_JAVA_FOUND 0
#define FOUND_JRE 1
#define FOUND_SDK 2

#define LAUNCH4J_TMP_DIR "\\launch4j-tmp\\"

#define STR 128
#define BIG_STR 1024

#define TRUE_STR "true"
#define FALSE_STR "false"

void msgBox(const char* text);
void showJavaWebPage();
BOOL loadString(HMODULE hLibrary, int resID, char* buffer);
BOOL loadBoolString(HMODULE hLibrary, int resID);
void regSearch(HKEY hKey, char* keyName, int searchType);
BOOL findJavaHome(char* path);
int getExePath(char* exePath);
void catJavaw(char* jrePath);
BOOL isJrePathOk(char* path);
BOOL prepare(HMODULE hLibrary, char *lpCmdLine);
void closeHandles();
DWORD execute(BOOL wait);

#endif // _LAUNCH4J_HEAD__INCLUDED_
