/* The runner inserts production ReadKnownVMs/GrowKnownVMs unchanged. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
typedef int jboolean;
typedef int jint;
#define JNI_TRUE 1
#define JNI_FALSE 0
#define MAXPATHLEN 260
#define INIT_MAX_KNOWN_VMS 10
#define VM_UNKNOWN -1
#define VM_KNOWN 0
#define VM_ALIASED_TO 1
#define VM_WARN 2
#define VM_ERROR 3
struct vmdesc { char *name; int flag; char *alias; };
static struct vmdesc *knownVMs;
static int knownVMsCount, knownVMsLimit;
static void GrowKnownVMs(int minimum);
static int fallback, missing, errors;
static const char *config;
static size_t config_size;
static char expected_path[1024];
static void *MemAlloc(size_t size) { void *p = malloc(size); assert(p); return p; }
static int config_stat(const char *path, struct stat *value) {
    memset(value, 0, sizeof(*value));
    return fallback || missing ? -1 : 0;
}
static FILE *config_open(const char *path, const char *mode) {
    assert(!strcmp(path, expected_path) && !strcmp(mode, "r"));
    if (missing) return NULL;
    FILE *file = tmpfile(); assert(file);
    /* Match the Windows CRT's text-mode CRLF conversion on this Linux host. */
    for (size_t i = 0; i < config_size; i++) {
        if (config[i] == '\r' && i + 1 < config_size && config[i + 1] == '\n') continue;
        assert(fputc((unsigned char) config[i], file) != EOF);
    }
    rewind(file);
    return file;
}
static void ReportErrorMessage2(const char *format, char *path, jboolean always) {
    assert(!strcmp(path, expected_path));
    errors++;
}
#define stat(path, value) config_stat(path, value)
#define fopen(path, mode) config_open(path, mode)
/* INSERT_SOURCE */
int main(int argc, char **argv) {
    assert(argc == 2);
    const char *mode = argv[1];
    char *jre = "C:\\Java";
    char max_jre[MAXPATHLEN];
    char *arch = "i386";
    int expected = 2;
    jboolean speculative = JNI_FALSE;
    config = "-server KNOWN\n-client ALIASED_TO -server\n";
    if (!strcmp(mode, "crlf")) config = "-server KNOWN\r\n-client ALIASED_TO -server\r\n";
    else if (!strcmp(mode, "no-newline")) config = "-server KNOWN\n-client ALIASED_TO -server";
    else if (!strcmp(mode, "name-only")) { config = "-server"; expected = 1; }
    else if (!strcmp(mode, "blank")) { config = "\n \t\n\r\n-server KNOWN\n"; expected = 1; }
    else if (!strcmp(mode, "nul")) { config = "\0ignored\n-server KNOWN\n"; expected = 1; }
    else if (!strcmp(mode, "fallback")) fallback = 1;
    else if (!strcmp(mode, "missing") || !strcmp(mode, "missing-speculative")) {
        missing = 1; expected = -1;
        speculative = !strcmp(mode, "missing-speculative");
    } else if (!strcmp(mode, "max-jre")) {
        memset(max_jre, 'J', sizeof(max_jre) - 1); max_jre[sizeof(max_jre) - 1] = 0; jre = max_jre;
    } else assert(!strcmp(mode, "normal"));
    config_size = !strcmp(mode, "nul") ? sizeof("\0ignored\n-server KNOWN\n") - 1 : strlen(config);
    snprintf(expected_path, sizeof(expected_path), fallback || missing ? "%s\\lib\\jvm.cfg" : "%s\\lib\\%s\\jvm.cfg", jre, arch);
    assert(ReadKnownVMs(jre, arch, speculative) == expected);
    if (missing) assert(errors == !speculative);
    else {
        assert(knownVMsCount == expected && errors == 0);
        assert(!strcmp(knownVMs[0].name, "-server") && knownVMs[0].flag == VM_KNOWN);
        if (expected == 2) {
            assert(!strcmp(knownVMs[1].name, "-client") && knownVMs[1].flag == VM_ALIASED_TO);
            assert(!strcmp(knownVMs[1].alias, "-server"));
        }
    }
    for (int i = 0; i < knownVMsCount; i++) {
        free(knownVMs[i].name);
        if (knownVMs[i].flag == VM_ALIASED_TO) free(knownVMs[i].alias);
    }
    free(knownVMs);
    puts("JVM_CONFIG_BOUNDS_PASS");
    return 0;
}
