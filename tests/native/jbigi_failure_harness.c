/* Actual jbigi.c and GMP, with deterministic JNI allocation/pinning failure injection. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <jni.h>
#include <gmp.h>
#include "jbigi.h"
JNIEXPORT jbyteArray JNICALL Java_net_i2p_util_NativeBigInteger_nativeModPowCT(JNIEnv *, jclass, jbyteArray, jbyteArray, jbyteArray);
JNIEXPORT jbyteArray JNICALL Java_net_i2p_util_NativeBigInteger_nativeModInverse(JNIEnv *, jclass, jbyteArray, jbyteArray);
typedef struct { jsize length; jbyte *data; } Array;
static Array *objects[8];
static int object_count, exception, pins, pin_call, fail_pin, fail_new, active_gmp;
static void *gmp_allocate(size_t n) { active_gmp++; void *p = malloc(n); assert(p); return p; }
static void *gmp_reallocate(void *p, size_t old_size, size_t new_size) { if (!p) active_gmp++; void *q = realloc(p, new_size); assert(q); return q; }
static void gmp_free(void *p, size_t n) { active_gmp--; free(p); }
static jbyteArray array(jsize n, int value) {
    Array *a = malloc(sizeof(*a)); assert(a);
    a->length = n; a->data = malloc((size_t) n); assert(a->data);
    if (n) memset(a->data, value, (size_t) n);
    objects[object_count++] = a;
    return (jbyteArray) a;
}
static jsize JNICALL length(JNIEnv *env, jarray value) { assert(value); return ((Array *) value)->length; }
static jbyte * JNICALL elements(JNIEnv *env, jbyteArray value, jboolean *copy) {
    assert(value);
    if (++pin_call == fail_pin) { exception = 1; return NULL; }
    pins++; return ((Array *) value)->data;
}
static void JNICALL release(JNIEnv *env, jbyteArray value, jbyte *p, jint mode) { assert(value && p && pins > 0); pins--; }
static jbyteArray JNICALL new_array(JNIEnv *env, jsize n) {
    if (fail_new) { exception = 1; return NULL; }
    return array(n, 0);
}
static jclass JNICALL find_class(JNIEnv *env, const char *name) { return (jclass) (size_t) 1; }
static jint JNICALL throw_new(JNIEnv *env, jclass type, const char *message) { exception = 1; return 0; }
static const struct JNINativeInterface_ functions = {
    .FindClass = find_class, .ThrowNew = throw_new, .GetArrayLength = length,
    .GetByteArrayElements = elements, .ReleaseByteArrayElements = release, .NewByteArray = new_array
};
int main(int argc, char **argv) {
    assert(argc == 3);
    int inverse = !strcmp(argv[1], "inverse");
    jbyteArray base = array(1, 2), exp = array(1, 3), mod = array(1, 5);
    const char *mode = argv[2];
    if (!strcmp(mode, "null-mod")) mod = NULL;
    if (!strcmp(mode, "null-base")) base = NULL;
    if (!strcmp(mode, "empty-mod")) mod = array(0, 0);
    if (!strcmp(mode, "empty-base")) base = array(0, 0);
    if (!strcmp(mode, "pin-mod")) fail_pin = 1;
    if (!strcmp(mode, "pin-exp")) { assert(!inverse); fail_pin = 2; }
    if (!strcmp(mode, "pin-base")) fail_pin = inverse ? 2 : 3;
    if (!strcmp(mode, "alloc-output")) fail_new = 1;
    if (!strcmp(mode, "pin-output")) fail_pin = inverse ? 3 : 4;
    mp_set_memory_functions(gmp_allocate, gmp_reallocate, gmp_free);
    JNIEnv env = &functions;
    jbyteArray result;
    if (inverse) result = Java_net_i2p_util_NativeBigInteger_nativeModInverse(&env, NULL, base, mod);
    else if (!strcmp(argv[1], "ct")) result = Java_net_i2p_util_NativeBigInteger_nativeModPowCT(&env, NULL, base, exp, mod);
    else result = Java_net_i2p_util_NativeBigInteger_nativeModPow(&env, NULL, base, exp, mod);
    if (!strcmp(mode, "ok")) {
        assert(result && !exception);
        Array *a = (Array *) result;
        assert(a->length > 0 && a->data[a->length - 1] == 3);
    } else assert(!result && exception);
    assert(active_gmp == 0 && pins == 0);
    for (int i = 0; i < object_count; i++) { free(objects[i]->data); free(objects[i]); }
    puts("JBIGI_FAILURE_BOUNDARY_PASS");
    return 0;
}
