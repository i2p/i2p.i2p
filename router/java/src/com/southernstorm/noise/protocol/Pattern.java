/*
 * Copyright (C) 2016 Southern Storm Software, Pty Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.southernstorm.noise.protocol;

/**
 * Information about all supported handshake patterns.
 */
class Pattern {
	
	private Pattern() {}

	// Token codes.
	public static final short S = 1;
	public static final short E = 2;
	public static final short EE = 3;
	public static final short ES = 4;
	public static final short SE = 5;
	public static final short SS = 6;
	public static final short F = 7;
	public static final short FF = 8;
	public static final short FLIP_DIR = 255;
	
	// Pattern flag bits.
	public static final short FLAG_LOCAL_STATIC = 0x0001;
	public static final short FLAG_LOCAL_EPHEMERAL = 0x0002;
	public static final short FLAG_LOCAL_REQUIRED = 0x0004;
	public static final short FLAG_LOCAL_EPHEM_REQ = 0x0008;
	public static final short FLAG_LOCAL_HYBRID = 0x0010;
	public static final short FLAG_LOCAL_HYBRID_REQ = 0x0020;
	public static final short FLAG_REMOTE_STATIC = 0x0100;
	public static final short FLAG_REMOTE_EPHEMERAL = 0x0200;
	public static final short FLAG_REMOTE_REQUIRED = 0x0400;
	public static final short FLAG_REMOTE_EPHEM_REQ = 0x0800;
	public static final short FLAG_REMOTE_HYBRID = 0x1000;
	public static final short FLAG_REMOTE_HYBRID_REQ = 0x2000;

	private static final short[] noise_pattern_XK = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    ES,
	    FLIP_DIR,
	    E,
	    EE,
	    FLIP_DIR,
	    S,
	    SE
	};

	/**
	 * Look up the description information for a pattern.
	 * 
	 * @param name The name of the pattern.
	 * @return The pattern description or null.
	 */
	public static short[] lookup(String name)
	{
		if (name.equals("XK"))
			return noise_pattern_XK;
		return null;
	}

	/**
	 * Reverses the local and remote flags for a pattern.
	 * 
	 * @param flags The flags, assuming that the initiator is "local".
	 * @return The reversed flags, with the responder now being "local".
	 */
	public static short reverseFlags(short flags)
	{
		return (short)(((flags >> 8) & 0x00FF) | ((flags << 8) & 0xFF00));
	}
}
