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

#include "../resource.h"
#include "../head.h"

int main(int argc, char* argv[])
{
    setConsoleFlag();
	HMODULE hLibrary = NULL;
	char cmdLine[BIG_STR] = "";
    int i; // causes error: 'for' loop initial declaration used outside C99 mode.
	for (i = 1; i < argc; i++) {
		strcat(cmdLine, argv[i]);
		if (i < argc - 1) {
			strcat(cmdLine, " ");
		}
	}
	if (!prepare(hLibrary, cmdLine)) {
		if (hLibrary != NULL) {
			FreeLibrary(hLibrary);
		}
		return 1;
	}
	FreeLibrary(hLibrary);

	int result = (int) execute(TRUE);
	if (result == -1) {
		char errMsg[BIG_STR] = "could not start the application: ";
		strcat(errMsg, strerror(errno));
		msgBox(errMsg);
		return 1;
	} else {
		return result;
	}
}
