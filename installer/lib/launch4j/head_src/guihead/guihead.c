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
#include "guihead.h"

extern PROCESS_INFORMATION pi;

HWND hWnd;
DWORD dwExitCode = 0;
BOOL stayAlive = FALSE;
BOOL splash = FALSE;
BOOL splashTimeoutErr;
BOOL waitForWindow;
int splashTimeout;		// tick count (s)

int APIENTRY WinMain(HINSTANCE hInstance,
                     HINSTANCE hPrevInstance,
                     LPSTR     lpCmdLine,
                     int       nCmdShow) {
	HMODULE hLibrary = NULL;
	if (!prepare(hLibrary, lpCmdLine)) {
		if (hLibrary != NULL) {
			FreeLibrary(hLibrary);
		}
		return 1;
	}

	splash = loadBoolString(hLibrary, SHOW_SPLASH);
	stayAlive = loadBoolString(hLibrary, GUI_HEADER_STAYS_ALIVE);
	if (splash || stayAlive) {
		hWnd = CreateWindowEx(WS_EX_TOOLWINDOW, "STATIC", "",
				WS_POPUP | SS_BITMAP,
				0, 0, CW_USEDEFAULT, CW_USEDEFAULT, NULL, NULL, hInstance, NULL);
		if (splash) {
			char timeout[10] = "";
			if (!loadString(hLibrary, SPLASH_TIMEOUT, timeout)) {
				msgBox("Cannot load splash timeout property.");
				return 1;
			}
			splashTimeout = atoi(timeout);
			if (splashTimeout <= 0 || splashTimeout > 60 * 15 /* 15 minutes */) {
				msgBox("Invalid splash timeout property.");
				return 1;
			}
			splashTimeoutErr = loadBoolString(hLibrary, SPLASH_TIMEOUT_ERR);
			waitForWindow = loadBoolString(hLibrary, SPLASH_WAITS_FOR_WINDOW);
			HANDLE hImage = LoadImage(hInstance,	// handle of the instance containing the image
					MAKEINTRESOURCE(SPLASH_BITMAP),	// name or identifier of image
					IMAGE_BITMAP,					// type of image
					0,								// desired width
					0,								// desired height
					LR_DEFAULTSIZE);
			if (hImage == NULL) {
				msgBox("Cannot load splash screen.");
				return 1;
			}
			SendMessage(hWnd, STM_SETIMAGE, IMAGE_BITMAP, (LPARAM) hImage);
			RECT rect;
			GetWindowRect(hWnd, &rect);
			int x = (GetSystemMetrics(SM_CXSCREEN) - (rect.right - rect.left)) / 2;
			int y = (GetSystemMetrics(SM_CYSCREEN) - (rect.bottom - rect.top)) / 2;
			SetWindowPos(hWnd, HWND_TOP, x, y, 0, 0, SWP_NOSIZE);
			ShowWindow(hWnd, nCmdShow);
			UpdateWindow (hWnd);
		}
		if (!SetTimer (hWnd, ID_TIMER, 1000 /* 1s */, TimerProc)) {
			return 1;
		}
	}
	FreeLibrary(hLibrary);
	if (execute(FALSE) == -1) {
		char errMsg[BIG_STR] = "Error occured while starting the application...\n";
		strcat(errMsg, strerror(errno));
		msgBox(errMsg);
		return 1;
	}
	if (!(splash || stayAlive)) {
		closeHandles();
		return 0;
	}
	MSG msg;
	while (GetMessage(&msg, NULL, 0, 0)) {
		TranslateMessage(&msg);
		DispatchMessage(&msg);
	}
	closeHandles();
	return dwExitCode;
}

BOOL CALLBACK enumwndfn(HWND hwnd, LPARAM lParam) {
	DWORD processId;
	GetWindowThreadProcessId(hwnd, &processId);
	if (pi.dwProcessId == processId) {
		LONG styles = GetWindowLong(hwnd, GWL_STYLE);
		if ((styles & WS_VISIBLE) != 0) {
			splash = FALSE;
			ShowWindow(hWnd, SW_HIDE);
			return FALSE;
		}
	}
	return TRUE;
}

VOID CALLBACK TimerProc(
	HWND hwnd,			// handle of window for timer messages
	UINT uMsg,			// WM_TIMER message
	UINT idEvent,		// timer identifier
	DWORD dwTime) {		// current system time
	
	if (splash) {
		if (splashTimeout == 0) {
			splash = FALSE;
			ShowWindow(hWnd, SW_HIDE);
			if (waitForWindow && splashTimeoutErr) {
				KillTimer(hwnd, ID_TIMER);
				msgBox("Could not start the application, operation timed out.");
				PostQuitMessage(0);
			}
		} else {
			splashTimeout--;
			if (waitForWindow) {
				EnumWindows(enumwndfn, 0);
			}
		}
	}
	GetExitCodeProcess(pi.hProcess, &dwExitCode);
	if (dwExitCode != STILL_ACTIVE
			|| !(splash || stayAlive)) {
		KillTimer(hWnd, ID_TIMER);
		PostQuitMessage(0);	
		return;
	}
}
