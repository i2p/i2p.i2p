#include <stdio.h>
#include <string.h>
#include <gmp.h>
#include "jbigi.h"

/******** prototypes */

void convert_j2mp(JNIEnv* env, jbyteArray jvalue, mpz_t* mvalue);
void convert_mp2j(JNIEnv* env, mpz_t mvalue, jbyteArray* jvalue);

/*
 * Versions:
 *
 * 1: Original version, with nativeModPow() and nativeDoubleValue()
 *
 * 2: (I2P 0.8.7)
 *    Removed nativeDoubleValue()
 *
 * 3: (I2P 0.9.26)
 *    Added:
 *      nativeJbigiVersion()
 *      nativeGMPMajorVersion()
 *      nativeGMPMinorVersion()
 *      nativeGMPPatchVersion()
 *      nativeModInverse()
 *      nativeModPowCT()
 *    Support negative base value in modPow()
 *    Throw ArithmeticException for bad arguments in modPow()
 *
 * 4: (I2P 0.9.27)
 *    Fix nativeGMPMajorVersion(), nativeGMPMinorVersion(), and nativeGMPPatchVersion()
 *    when built as a shared library
 *
 */
#define JBIGI_VERSION 4

/*****************************************
 *****Native method implementations*******
 *****************************************/

/* since version 3 */
JNIEXPORT jint JNICALL Java_net_i2p_util_NativeBigInteger_nativeJbigiVersion
        (JNIEnv* env, jclass cls) {
    return (jint) JBIGI_VERSION;
}

/* since version 3, fixed for dynamic builds in version 4 */
JNIEXPORT jint JNICALL Java_net_i2p_util_NativeBigInteger_nativeGMPMajorVersion
        (JNIEnv* env, jclass cls) {
    int v = gmp_version[0] - '0';
    return (jint) v;
}

/* since version 3, fixed for dynamic builds in version 4 */
JNIEXPORT jint JNICALL Java_net_i2p_util_NativeBigInteger_nativeGMPMinorVersion
        (JNIEnv* env, jclass cls) {
    int v = 0;
    if (strlen(gmp_version) > 2) {
        v = gmp_version[2] - '0';
    }
    return (jint) v;
}

/* since version 3, fixed for dynamic builds in version 4 */
JNIEXPORT jint JNICALL Java_net_i2p_util_NativeBigInteger_nativeGMPPatchVersion
        (JNIEnv* env, jclass cls) {
    int v = 0;
    if (strlen(gmp_version) > 4) {
        v = gmp_version[4] - '0';
    }
    return (jint) v;
}

/******** nativeModPow() */
/*
 * Class:     net_i2p_util_NativeBigInteger
 * Method:    nativeModPow
 * Signature: ([B[B[B)[B
 *
 * From the javadoc:
 *
 * calculate (base ^ exponent) % modulus.
 * @param jbase big endian twos complement representation of the base
 *             Negative values allowed as of version 3
 * @param jexp big endian twos complement representation of the exponent
 *             Must be greater than or equal to zero.
 *             As of version 3, throws java.lang.ArithmeticException if < 0.
 * @param jmod big endian twos complement representation of the modulus
 *             Must be greater than zero.
 *             As of version 3, throws java.lang.ArithmeticException if <= 0.
 *             Prior to version 3, crashed the JVM if <= 0.
 * @return big endian twos complement representation of (base ^ exponent) % modulus
 * @throws java.lang.ArithmeticException if jmod is <= 0
 */

JNIEXPORT jbyteArray JNICALL Java_net_i2p_util_NativeBigInteger_nativeModPow
        (JNIEnv* env, jclass cls, jbyteArray jbase, jbyteArray jexp, jbyteArray jmod) {
        /* 1) Convert base, exponent, modulus into the format libgmp understands
         * 2) Call libgmp's modPow.
         * 3) Convert libgmp's result into a big endian twos complement number.
         */

        mpz_t mbase;
        mpz_t mexp;
        mpz_t mmod;
        jbyteArray jresult;

        convert_j2mp(env, jmod,  &mmod);
        if (mpz_sgn(mmod) <= 0) {
            mpz_clear(mmod);
            jclass exc = (*env)->FindClass(env, "java/lang/ArithmeticException");
            (*env)->ThrowNew(env, exc, "Modulus must be positive");
            return 0;
        }

        // disallow negative exponents to avoid divide by zero exception if no inverse exists
        convert_j2mp(env, jexp,  &mexp);
        if (mpz_sgn(mexp) < 0) {
            mpz_clears(mmod, mexp, NULL);
            jclass exc = (*env)->FindClass(env, "java/lang/ArithmeticException");
            (*env)->ThrowNew(env, exc, "Exponent cannot be negative");
            return 0;
        }

        convert_j2mp(env, jbase, &mbase);
 
        /* Perform the actual powmod. We use mmod for the result because it is
         * always at least as big as the result.
         */
        mpz_powm(mmod, mbase, mexp, mmod);

        convert_mp2j(env, mmod, &jresult);

        mpz_clears(mbase, mexp, mmod, NULL);

        return jresult;
}

/******** nativeModPowCT() */
/*
 * Class:     net_i2p_util_NativeBigInteger
 * Method:    nativeModPowCT
 * Signature: ([B[B[B)[B
 *
 * Constant time version of nativeModPow()
 *
 * From the javadoc:
 *
 * calculate (base ^ exponent) % modulus.
 * @param jbase big endian twos complement representation of the base
 *             Negative values allowed.
 * @param jexp big endian twos complement representation of the exponent
 *             Must be positive.
 * @param jmod big endian twos complement representation of the modulus
 *             Must be positive and odd.
 * @return big endian twos complement representation of (base ^ exponent) % modulus
 * @throws java.lang.ArithmeticException if jmod or jexp is <= 0, or jmod is even.
 * @since version 3
 */

JNIEXPORT jbyteArray JNICALL Java_net_i2p_util_NativeBigInteger_nativeModPowCT
        (JNIEnv* env, jclass cls, jbyteArray jbase, jbyteArray jexp, jbyteArray jmod) {

        mpz_t mbase;
        mpz_t mexp;
        mpz_t mmod;
        jbyteArray jresult;

        convert_j2mp(env, jmod,  &mmod);
        if (mpz_sgn(mmod) <= 0) {
            mpz_clear(mmod);
            jclass exc = (*env)->FindClass(env, "java/lang/ArithmeticException");
            (*env)->ThrowNew(env, exc, "Modulus must be positive");
            return 0;
        }
        // disallow even modulus as specified in the GMP docs
        if (mpz_odd_p(mmod) == 0) {
            mpz_clear(mmod);
            jclass exc = (*env)->FindClass(env, "java/lang/ArithmeticException");
            (*env)->ThrowNew(env, exc, "Modulus must be odd");
            return 0;
        }

        // disallow negative or zero exponents as specified in the GMP docs
        convert_j2mp(env, jexp,  &mexp);
        if (mpz_sgn(mexp) <= 0) {
            mpz_clears(mmod, mexp, NULL);
            jclass exc = (*env)->FindClass(env, "java/lang/ArithmeticException");
            (*env)->ThrowNew(env, exc, "Exponent must be positive");
            return 0;
        }

        convert_j2mp(env, jbase, &mbase);
 
        mpz_powm_sec(mmod, mbase, mexp, mmod);

        convert_mp2j(env, mmod, &jresult);

        mpz_clears(mbase, mexp, mmod, NULL);

        return jresult;
}

/******** nativeModInverse() */
/*
 * Class:     net_i2p_util_NativeBigInteger
 * Method:    nativeModInverse
 * Signature: ([B[B)[B
 *
 * From the javadoc:
 *
 * calculate (base ^ -1) % modulus.
 * @param jbase big endian twos complement representation of the base
 *             Negative values allowed
 * @param jmod big endian twos complement representation of the modulus
 *             Zero or Negative values will throw a java.lang.ArithmeticException
 * @return big endian twos complement representation of (base ^ exponent) % modulus
 * @throws java.lang.ArithmeticException if jbase and jmod are not coprime or jmod is <= 0
 * @since version 3
 */

JNIEXPORT jbyteArray JNICALL Java_net_i2p_util_NativeBigInteger_nativeModInverse
        (JNIEnv* env, jclass cls, jbyteArray jbase, jbyteArray jmod) {

        mpz_t mbase;
        mpz_t mexp;
        mpz_t mmod;
        mpz_t mgcd;
        jbyteArray jresult;

        convert_j2mp(env, jmod,  &mmod);

        if (mpz_sgn(mmod) <= 0) {
            mpz_clear(mmod);
            jclass exc = (*env)->FindClass(env, "java/lang/ArithmeticException");
            (*env)->ThrowNew(env, exc, "Modulus must be positive");
            return 0;
        }

        convert_j2mp(env, jbase, &mbase);
        mpz_init_set_si(mexp, -1);
 
        /* We must protect the jvm by doing a gcd test first.
         * If the arguments are not coprime, GMP will throw a divide by zero
         * and crash the JVM.
         * We could test in Java using BigInteger.gcd() but it is almost as slow
         * as the Java modInverse() itself, thus defeating the point.
         * Unfortunately, this almost doubles our time here too.
         */
        mpz_init(mgcd);
        mpz_gcd(mgcd, mbase, mmod);
        if (mpz_cmp_si(mgcd, 1) != 0) {
            mpz_clears(mbase, mexp, mmod, mgcd, NULL);
            jclass exc = (*env)->FindClass(env, "java/lang/ArithmeticException");
            (*env)->ThrowNew(env, exc, "Not coprime in nativeModInverse()");
            return 0;
        }

        /* Perform the actual powmod. We use mmod for the result because it is
         * always at least as big as the result.
         */
        mpz_powm(mmod, mbase, mexp, mmod);

        convert_mp2j(env, mmod, &jresult);

        mpz_clears(mbase, mexp, mmod, mgcd, NULL);

        return jresult;
}

/******** nativeNeg() */
/* since version 3 */
/*
 * Class:     net_i2p_util_NativeBigInteger
 * Method:    nativeNeg
 * Signature: ([B)[B
 *
 * For testing of the conversion functions only!
 *
 * calculate n mod d
 * @param n big endian twos complement representation
 * @return big endian twos complement representation of -n
 */

/****
JNIEXPORT jbyteArray JNICALL Java_net_i2p_util_NativeBigInteger_nativeNeg
        (JNIEnv* env, jclass cls, jbyteArray jn) {

        mpz_t mn;
        jbyteArray jresult;

        convert_j2mp(env, jn,  &mn);
 
        // result to mn
        mpz_neg(mn, mn);

        convert_mp2j(env, mn, &jresult);

        mpz_clear(mn);

        return jresult;
}
****/

/******************************
 *****Conversion methods*******
 ******************************/

/*Luckily we can use GMP's mpz_import() and mpz_export() functions to convert from/to
 *BigInteger.toByteArray() representation.
 */

/******** convert_j2mp() */
/*
 * Initializes the GMP value with enough preallocated size, and converts the
 * Java value into the GMP value. The value that mvalue points to should be
 * uninitialized
 *
 * As of version 3, negative values are correctly converted.
 */

void convert_j2mp(JNIEnv* env, jbyteArray jvalue, mpz_t* mvalue)
{
        jsize size;
        jbyte* jbuffer;
        mpz_t mask;

        size = (*env)->GetArrayLength(env, jvalue);
        jbuffer = (*env)->GetByteArrayElements(env, jvalue, NULL);

        mpz_init2(*mvalue, sizeof(jbyte) * 8 * size); //preallocate the size

        /* void mpz_import(
         *   mpz_t rop, size_t count, int order, int size, int endian,
         *   size_t nails, const void *op);
         *
         * order = 1
         *   order can be 1 for most significant word first or -1 for least
         *   significant first.
         * endian = 1
         *   Within each word endian can be 1 for most significant byte first,
         *   -1 for least significant first.
         * nails = 0
         *   The most significant nails bits of each word are skipped, this can
         *   be 0 to use the full words.
         */
        mpz_import(*mvalue, size, 1, sizeof(jbyte), 1, 0, (void*)jbuffer);
        if (jbuffer[0] < 0) {
            // ones complement, making a negative number
            mpz_com(*mvalue, *mvalue);
            // construct the mask needed to get rid of the new high bit
            mpz_init_set_ui(mask, 1);
            mpz_mul_2exp(mask, mask, size * 8);
            mpz_sub_ui(mask, mask, 1);
            // mask off the high bits, making a postive number (the magnitude, off by one)
            mpz_and(*mvalue, *mvalue, mask);
            // release the mask
            mpz_clear(mask);
            // add one to get the correct magnitude
            mpz_add_ui(*mvalue, *mvalue, 1);
            // invert to a negative number
            mpz_neg(*mvalue, *mvalue);
        }
        (*env)->ReleaseByteArrayElements(env, jvalue, jbuffer, JNI_ABORT);
}

/******** convert_mp2j() */
/*
 * Converts the GMP value into the Java value; Doesn't do anything else.
 * Pads the resulting jbyte array with 0, so the twos complement value is always
 * positive.
 *
 * As of version 3, negative values are correctly converted.
 */

void convert_mp2j(JNIEnv* env, mpz_t mvalue, jbyteArray* jvalue)
{
        // size_t not jsize to work with 64bit CPUs (do we need to update this
        // elsewhere, and/or adjust memory alloc sizes?)
        size_t size; 
        jbyte* buffer;
        jboolean copy;
        int i;
        int neg;

        copy = JNI_FALSE;

        neg = mpz_sgn(mvalue) < 0;
        if (neg) {
            // add 1...
            // have to do this before we calculate the size!
            mpz_add_ui(mvalue, mvalue, 1);
        }

        /* sizeinbase() + 7 => Ceil division */
        size = (mpz_sizeinbase(mvalue, 2) + 7) / 8 + sizeof(jbyte);
        *jvalue = (*env)->NewByteArray(env, size);

        buffer = (*env)->GetByteArrayElements(env, *jvalue, &copy);
        buffer[0] = 0x00;

        if (!neg) {
            mpz_export((void*)&buffer[1], NULL, 1, sizeof(jbyte), 1, 0, mvalue);
        } else {
            mpz_export((void*)&buffer[1], NULL, 1, sizeof(jbyte), 1, 0, mvalue);
            // ... and invert the bits
            // This could be done all in mpz, the reverse of the above
            for (i = 0; i <= size; i++) {
                buffer[i] = ~buffer[i];
            }
        }

        /* mode has (supposedly) no effect if elems is not a copy of the
         * elements in array
         */
        (*env)->ReleaseByteArrayElements(env, *jvalue, buffer, 0);
        //mode has (supposedly) no effect if elems is not a copy of the elements in array
}
