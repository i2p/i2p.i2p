#include <stdio.h>
#include <gmp.h>
#include "jbigi.h"

/******** prototypes */

void convert_j2mp(JNIEnv* env, jbyteArray jvalue, mpz_t* mvalue);
void convert_mp2j(JNIEnv* env, mpz_t mvalue, jbyteArray* jvalue);


/*****************************************
 *****Native method implementations*******
 *****************************************/

/******** nativeModPow() */
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
        /* 1) Convert base, exponent, modulus into the format libgmp understands
         * 2) Call libgmp's modPow.
         * 3) Convert libgmp's result into a big endian twos complement number.
         */

        mpz_t mbase;
        mpz_t mexp;
        mpz_t mmod;
        jbyteArray jresult;

        convert_j2mp(env, jbase, &mbase);
        convert_j2mp(env, jexp,  &mexp);
        convert_j2mp(env, jmod,  &mmod);
 
		/* Perform the actual powmod. We use mmod for the result because it is
         * always at least as big as the result.
         */
        mpz_powm(mmod, mbase, mexp, mmod);

		convert_mp2j(env, mmod, &jresult);

        mpz_clear(mbase);
        mpz_clear(mexp);
        mpz_clear(mmod);

        return jresult;
}

/******** nativeDoubleValue() */
/*
 * Class:     net_i2p_util_NativeBigInteger
 * Method:    nativeDoubleValue
 * Signature: ([B)D
 *
 * From the Javadoc:
 *
 * Converts a BigInteger byte-array to a 'double'
 * @param ba Big endian twos complement representation of the BigInteger to convert to a double
 * @return The plain double-value represented by 'ba'
 */
JNIEXPORT jdouble JNICALL Java_net_i2p_util_NativeBigInteger_nativeDoubleValue
(JNIEnv * env, jclass cls, jbyteArray jba){
	    /* 1) Convert the bytearray BigInteger value into the format libgmp understands
         * 2) Call libgmp's mpz_get_d.
         * 3) Convert libgmp's result into a big endian twos complement number.
         */
        mpz_t mval;
		jdouble retval;
        convert_j2mp(env, jba, &mval);

		retval = mpz_get_d(mval);
		mpz_clear(mval);
		return retval;
}

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
 */

void convert_j2mp(JNIEnv* env, jbyteArray jvalue, mpz_t* mvalue)
{
        jsize size;
        jbyte* jbuffer;
		//int sign;

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
		/*Uncomment this to support negative integer values,
		not tested though..
		sign = jbuffer[0] < 0?-1:1;
		if(sign == -1)
			mpz_neg(*mvalue,*mvalue);
		*/
        (*env)->ReleaseByteArrayElements(env, jvalue, jbuffer, JNI_ABORT);
}

/******** convert_mp2j() */
/*
 * Converts the GMP value into the Java value; Doesn't do anything else.
 * Pads the resulting jbyte array with 0, so the twos complement value is always
 * positive.
 */

void convert_mp2j(JNIEnv* env, mpz_t mvalue, jbyteArray* jvalue)
{
        jsize size;
        jbyte* buffer;
        jboolean copy;
		//int i;

        copy = JNI_FALSE;

        /* sizeinbase() + 7 => Ceil division */
        size = (mpz_sizeinbase(mvalue, 2) + 7) / 8 + sizeof(jbyte);
        *jvalue = (*env)->NewByteArray(env, size);

        buffer = (*env)->GetByteArrayElements(env, *jvalue, &copy);
        buffer[0] = 0x00;
		//Uncomment the comments below to support negative integer values,
		//not very well-tested though..
		//if(mpz_sgn(mvalue) >=0){
		mpz_export((void*)&buffer[1], &size, 1, sizeof(jbyte), 1, 0, mvalue);
		//}else{
		//	mpz_add_ui(mvalue,mvalue,1);
		//	mpz_export((void*)&buffer[1], &size, 1, sizeof(jbyte), 1, 0, mvalue);
		//	for(i =0;i<=size;i++){ //This could be done more effectively
		//		buffer[i]=~buffer[i];
		//	}
		//}

		/* mode has (supposedly) no effect if elems is not a copy of the
         * elements in array
         */
        (*env)->ReleaseByteArrayElements(env, *jvalue, buffer, 0);
        //mode has (supposedly) no effect if elems is not a copy of the elements in array
}

/******** eof */
