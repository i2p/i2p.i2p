/*
 * @(#)java_md.h	1.10 04/04/24
 *
 * Copyright 2004 Sun Microsystems, Inc. All rights reserved.
 * SUN PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

/* Backported from Tiger (1.5) java_md.h	1.13 04/01/12 */

#ifndef JAVA_MD_H
#define JAVA_MD_H

#include "jni.h"
#include <windows.h>
#include <io.h>

#define PATH_SEPARATOR	';'
#define FILESEP		"\\"
#define FILE_SEPARATOR	'\\'
#define MAXPATHLEN      MAX_PATH
#define MAXNAMELEN	MAX_PATH


int UnsetEnv(char *name);
int strcasecmp(const char *s1, const char *s2);
int strncasecmp(const char *s1, const char *s2, size_t n);

#endif
