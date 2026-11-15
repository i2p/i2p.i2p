/* The runner inserts the actual main/readOptions definitions before this file's main. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define ERROR_COULDNT_FIND_JVM 1
#define ERROR_COULDNT_INITIALIZE_JVM 2
#define ERROR_COULDNT_LOAD_JVM 3
#define ERROR_COULDNT_PARSE_ARGUMENTS 4
#define ERROR_STARTING_PROGRAM 5
#define MAX_PATH 260
#define MB_ICONWARNING 0
#define MB_OKCANCEL 0
#define IDOK 1
#define MB_OK 0
#define SW_SHOWNORMAL 0
static int expected_argc;
static const char *expected_last;
static void SetWorkingDirectory(char *path) { strcpy(path, "C:\\test"); }
static int MessageBox(void *a, const char *b, const char *c, int d) { return 0; }
static int ShellExecute(void *a, void *b, const char *c, const char *d, const char *e, int f) { abort(); }
static void *MemAlloc(size_t size) { void *p = malloc(size); assert(p); return p; }
static int launchJVM(int argc, char **argv) {
    assert(argc == expected_argc);
    assert(argv[argc] == NULL);
    assert(strcmp(argv[0], "I2P.exe") == 0);
    if (expected_last) assert(strcmp(argv[argc - 1], expected_last) == 0);
    return 0;
}
void readOptions(char***, int*);
/* INSERT_SOURCE */
int main(int argc, char **argv) {
    assert(argc == 2);
    char *arguments[] = { "I2P.exe", "-Xmx128m", "net.i2p.Router", NULL };
    int supplied = 1;
    if (strcmp(argv[1], "arguments") == 0) {
        expected_argc = 3; supplied = 3; expected_last = "net.i2p.Router";
    } else if (strcmp(argv[1], "default") == 0) {
        remove("launch.properties"); expected_argc = 10; expected_last = "net.i2p.router.RouterLaunch";
    } else {
        int count = atoi(argv[1]);
        assert(count >= 0 && count <= 100);
        FILE *file = fopen("launch.properties", "w"); assert(file);
        for (int i = 0; i < count; i++) fprintf(file, "option-%d\n", i);
        assert(fclose(file) == 0);
        expected_argc = count + 1;
        static char last[32];
        if (count) { snprintf(last, sizeof(last), "option-%d", count - 1); expected_last = last; }
    }
    assert(launcher_main(supplied, arguments) == 0);
    puts("LAUNCHER_BOUNDS_PASS");
    return 0;
}
