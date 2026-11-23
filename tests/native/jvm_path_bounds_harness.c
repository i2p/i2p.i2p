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
static int mock_stat(const char *path, struct stat *value) { memset(value, 0, sizeof(*value)); return 0; }
#define stat(path, value) mock_stat(path, value)
/* INSERT_SOURCE */
int main(int argc, char **argv) {
    assert(argc == 2);
    const char *jre = "C:\\Java";
    const char *type = "server";
    const char *expected = "C:\\Java\\bin\\server\\jvm.dll";
    char large[2048]; memset(large, 'A', sizeof(large) - 1); large[sizeof(large) - 1] = 0;
    int size = 128, valid = 1;
    if (!strcmp(argv[1], "custom")) { type = "D:\\custom"; expected = "D:\\custom\\jvm.dll"; }
    else if (!strcmp(argv[1], "exact")) size = (int) strlen(expected) + 1;
    else if (!strcmp(argv[1], "small")) { size = 8; valid = 0; }
    else if (!strcmp(argv[1], "zero")) { size = 0; valid = 0; }
    else if (!strcmp(argv[1], "negative")) { size = -1; valid = 0; }
    else if (!strcmp(argv[1], "large-jre")) { jre = large; size = 32; valid = 0; }
    else if (!strcmp(argv[1], "large-custom")) { large[0] = '/'; type = large; size = 32; valid = 0; }
    char *out = size > 0 ? malloc((size_t) size) : NULL;
    jboolean result = GetJVMPath(jre, type, out, size);
    assert(result == valid);
    if (valid) assert(!strcmp(out, expected));
    else if (size > 0) assert(out[0] == 0);
    free(out);
    puts("JVM_PATH_BOUNDS_PASS");
    return 0;
}
