#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
typedef int jboolean;
typedef int jint;
#define JNI_TRUE 1
#define JNI_FALSE 0
#define JVM_DLL "jvm.dll"
#define JAVA_DLL "java.dll"
#define MAXPATHLEN 260
#define _snprintf snprintf
static char *environment_path;
static int environment_updated;
static void *MemAlloc(size_t size) { void *p = malloc(size); assert(p); return p; }
static char *mock_getenv(const char *name) { assert(!strcmp(name, "PATH")); return environment_path; }
static int mock_putenv(const char *value) {
    assert(!strcmp(value, environment_path ? "PATH=C:\\Java\\bin;C:\\Windows" : "PATH=C:\\Java\\bin"));
    environment_updated = 1; return 0;
}
static jboolean GetPublicJREHome(char *path, jint size) { assert(size > 8); strcpy(path, "C:\\Java"); return JNI_TRUE; }
#define getenv(name) mock_getenv(name)
#define _putenv(value) mock_putenv(value)
static int mock_stat(const char *path, struct stat *value) { memset(value, 0, sizeof(*value)); return 0; }
#define stat(path, value) mock_stat(path, value)
/* INSERT_SOURCE */
int main(int argc, char **argv) {
    assert(argc == 2);
    assert(!strcmp(argv[1], "env-absent") || !strcmp(argv[1], "env-present"));
    environment_path = !strcmp(argv[1], "env-present") ? "C:\\Windows" : NULL;
    char home[128];
    assert(GetJREPath(home, sizeof(home)) && !strcmp(home, "C:\\Java") && environment_updated);
    puts("JRE_ENVIRONMENT_BOUNDS_PASS");
    return 0;
}
