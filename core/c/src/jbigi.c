#include <stdio.h>
#include <gmp.h>
#include "jbigi.h"

/********/
//function prototypes

//FIXME: should these go into jbigi.h? -- ughabugha

void convert_j2mp(JNIEnv* env, jbyteArray jvalue, mpz_t* mvalue);
void convert_mp2j(JNIEnv* env, mpz_t mvalue, jbyteArray* jvalue);

/********/
/*
 * Class:     net_i2p_util_NativeBigInteger
 * Method:    nativeModPow
 * Signature: ([B[B[B)[B
 *
 * From the javadoc:
 *
 * calculate (base ^ exponent) % modulus.
 * @param curVal big endian twos complement representation of the base (but it must be positive)
 * @param exponent big endian twos complement representation of the exponent
 * @param modulus big endian twos complement representation of the modulus
 * @return big endian twos complement representation of (base ^ exponent) % modulus
 */

JNIEXPORT jbyteArray JNICALL Java_net_i2p_util_NativeBigInteger_nativeModPow
        (JNIEnv* env, jclass cls, jbyteArray jbase, jbyteArray jexp, jbyteArray jmod) {
        // convert base, exponent, modulus into the format libgmp understands
        // call libgmp's modPow
        // convert libgmp's result into a big endian twos complement number

        mpz_t mbase;
        mpz_t mexp;
        mpz_t mmod;
        //mpz_t mresult;
        jbyteArray jresult;

        convert_j2mp(env, jbase, &mbase);
        convert_j2mp(env, jexp,  &mexp);
        convert_j2mp(env, jmod,  &mmod);

        //gmp_printf("mbase  =%Zd\n", mbase);
        //gmp_printf("mexp   =%Zd\n", mexp);
        //gmp_printf("mmod   =%Zd\n", mmod);

        mpz_powm(mmod, mbase, mexp, mmod);
        //we use mod for the result because it is always at least as big

        //gmp_printf("mresult=%Zd\n", mmod);

        convert_mp2j(env, mmod, &jresult);
        //convert_j2mp(env, jresult, &mresult);

        //gmp_printf("", mpz_cmp(mmod, mresult) == 0 ? "true" : "false");

        mpz_clear(mbase);
        mpz_clear(mexp);
        mpz_clear(mmod);
        //mpz_clear(mresult);

        return jresult;
}

/********/
/*
 * Initializes the GMP value with enough preallocated size, and converts the
 * Java value into the GMP value. The value that mvalue is pointint to
 * should be uninitialized
 */

void convert_j2mp(JNIEnv* env, jbyteArray jvalue, mpz_t* mvalue)
{
        jsize size;
        jbyte* jbuffer;

        size = (*env)->GetArrayLength(env, jvalue);
        jbuffer = (*env)->GetByteArrayElements(env, jvalue, NULL);

        mpz_init2(*mvalue, sizeof(jbyte) * 8 * size); //preallocate the size

        /*
         * void mpz_import (mpz_t rop, size_t count, int order, int size, int endian, size_t nails, const void *op)
         * order = 1    - order can be 1 for most significant word first or -1 for least significant first.
         * endian = 1   - Within each word endian can be 1 for most significant byte first, -1 for least significant first
         * nails = 0    - The most significant nails bits of each word are skipped, this can be 0 to use the full words
         */
        mpz_import(*mvalue, size, 1, sizeof(jbyte), 1, 0, (void*)jbuffer);
        (*env)->ReleaseByteArrayElements(env, jvalue, jbuffer, JNI_ABORT);
}

/********/
/*
 * Converts the GMP value into the Java value; Doesn't do anything else.
 */

void convert_mp2j(JNIEnv* env, mpz_t mvalue, jbyteArray* jvalue)
{
        jsize size;
        jbyte* buffer;
        jboolean copy;

        copy = JNI_FALSE;

        size = (mpz_sizeinbase(mvalue, 2) + 7) / 8 + sizeof(jbyte); //+7 => ceil division
        *jvalue = (*env)->NewByteArray(env, size);

        buffer = (*env)->GetByteArrayElements(env, *jvalue, &copy);

        buffer[0] = 0;

        /*
         * void *mpz_export (void *rop, size_t *count, int order, int size, int endian, size_t nails, mpz_t op)
         */
        mpz_export((void*)&buffer[1], &size, 1, sizeof(jbyte), 1, 0, mvalue);

        (*env)->ReleaseByteArrayElements(env, *jvalue, buffer, 0);
        //mode has (supposedly) no effect if elems is not a copy of the elements in array
}

/********/

