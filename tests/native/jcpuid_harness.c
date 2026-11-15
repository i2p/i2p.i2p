#include <assert.h>
#include <cpuid.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <jni.h>
#include "jcpuid.h"
static unsigned int actual[4];
static int fail_class, fail_constructor, fail_object, pending_exception;
static int constructor_calls, object_calls;
static jclass JNICALL find_class(JNIEnv *env, const char *name) {
    assert(!pending_exception);
    assert(!strcmp(name, "freenet/support/CPUInformation/CPUID$CPUIDResult"));
    if (fail_class) { pending_exception = 1; return NULL; }
    return (jclass) (size_t) 1;
}
static jmethodID JNICALL constructor(JNIEnv *env, jclass type, const char *name, const char *signature) {
    assert(type && !pending_exception);
    constructor_calls++;
    if (fail_constructor) { pending_exception = 1; return NULL; }
    assert(!strcmp(name, "<init>") && !strcmp(signature, "(IIII)V")); return (jmethodID) (size_t) 1;
}
static jobject JNICALL object(JNIEnv *env, jclass type, jmethodID method, ...) {
    assert(type && method && !pending_exception);
    object_calls++;
    if (fail_object) { pending_exception = 1; return NULL; }
    va_list args; va_start(args, method);
    for (int i = 0; i < 4; i++) actual[i] = (unsigned int) va_arg(args, jint);
    va_end(args); return (jobject) actual;
}
static const struct JNINativeInterface_ functions = { .FindClass = find_class, .GetMethodID = constructor, .NewObject = object };
int main(int argc, char **argv) {
    assert(argc == 1 || argc == 2);
    JNIEnv env = &functions;
    if (argc == 2 && strcmp(argv[1], "host-registers")) {
        fail_class = !strcmp(argv[1], "class-failure");
        fail_constructor = !strcmp(argv[1], "constructor-failure");
        fail_object = !strcmp(argv[1], "allocation-failure");
        assert(fail_class || fail_constructor || fail_object);
        assert(Java_freenet_support_CPUInformation_CPUID_doCPUID(&env, NULL, 0) == NULL);
        assert(pending_exception);
        assert(constructor_calls == (fail_class ? 0 : 1));
        assert(object_calls == (fail_object ? 1 : 0));
        puts("JCPUID_JNI_FAILURE_PASS");
        return 0;
    }
    unsigned int leaves[] = { 0, 1, 7, 0x80000000U };
    for (int i = 0; i < 4; i++) {
        unsigned int expected[4];
        __cpuid_count(leaves[i], 0, expected[0], expected[1], expected[2], expected[3]);
        Java_freenet_support_CPUInformation_CPUID_doCPUID(&env, NULL, (jint) leaves[i]);
        assert(!memcmp(expected, actual, sizeof(expected)));
    }
    puts("JCPUID_HOST_CHECKS=4; FAILURES=0");
    return 0;
}
