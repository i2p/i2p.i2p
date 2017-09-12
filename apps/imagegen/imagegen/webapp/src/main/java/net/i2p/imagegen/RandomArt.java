package net.i2p.imagegen;

/*
 *
 * Translated to Java and modified from:
 *
 * gnutls lib/extras/randomart.c
 *
 */

/* $OpenBSD: key.c,v 1.98 2011/10/18 04:58:26 djm Exp $ */
/*
 * Copyright (c) 2000, 2001 Markus Friedl.  All rights reserved.
 * Copyright (c) 2008 Alexander von Gernler.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import net.i2p.data.DataHelper;

/**
 * Draw an ASCII-Art representing the fingerprint so human brain can
 * profit from its built-in pattern recognition ability.
 * This technique is called "random art" and can be found in some
 * scientific publications like this original paper:
 *
 * "Hash Visualization: a New Technique to improve Real-World Security",
 * Perrig A. and Song D., 1999, International Workshop on Cryptographic
 * Techniques and E-Commerce (CrypTEC '99)
 * sparrow.ece.cmu.edu/~adrian/projects/validation/validation.pdf
 *
 * The subject came up in a talk by Dan Kaminsky, too.
 *
 * If you see the picture is different, the key is different.
 * If the picture looks the same, you still know nothing.
 *
 * The algorithm used here is a worm crawling over a discrete plane,
 * leaving a trace (augmenting the field) everywhere it goes.
 * Movement is taken from dgst_raw 2bit-wise.  Bumping into walls
 * makes the respective movement vector be ignored for this turn.
 * Graphs are not unambiguous, because circles in graphs can be
 * walked in either direction.
 *
 * @since 0.9.25
 */
public class RandomArt {

    /*
     * Field sizes for the random art.  Have to be odd, so the starting point
     * can be in the exact middle of the picture, and FLDBASE should be >=8 .
     * Else pictures would be too dense, and drawing the frame would
     * fail, too, because the key type would not fit in anymore.
     */
    private static final int	FLDBASE		= 8;
    private static final int	FLDSIZE_Y	= FLDBASE + 1;
    private static final int	FLDSIZE_X	= FLDBASE * 2 + 1;
    /*
     * Chars to be used after each other every time the worm
     * intersects with itself.  Matter of taste.
     */
    private static final String A_augmentation_string = " .o+=*BOX@%&#/^SE";
    // https://en.wikipedia.org/wiki/Miscellaneous_Symbols
    private static final String U_augmentation_string = " \u2600\u2601\u2602\u2603" +
                                                         "\u2604\u2605\u2606\u2607" +
                                                         "\u2608\u2609\u260a\u260b" +
                                                         "\u260c\u260d\u260e\u260f";

    private static final char A_BOX_TOP = '-';
    private static final char A_BOX_BOTTOM = '-';
    private static final char A_BOX_LEFT = '|';
    private static final char A_BOX_RIGHT = '|';
    private static final char A_BOX_TL = '+';
    private static final char A_BOX_TR = '+';
    private static final char A_BOX_BL = '+';
    private static final char A_BOX_BR = '+';

    // https://en.wikipedia.org/wiki/Box-drawing_characters
    // these are the thin singles
    private static final char U_BOX_TOP = '\u2500';
    private static final char U_BOX_BOTTOM = '\u2500';
    private static final char U_BOX_LEFT = '\u2502';
    private static final char U_BOX_RIGHT = '\u2502';
    private static final char U_BOX_TL = '\u250c';
    private static final char U_BOX_TR = '\u2510';
    private static final char U_BOX_BL = '\u2514';
    private static final char U_BOX_BR = '\u2518';

    private static final int BASE = 0x778899;

    /**
     *  @param dgst_raw the data to be visualized, recommend 64 bytes or less
     *  @param key_type output in the first line, recommend 6 chars or less
     *  @param key_size output in the first line
     *  @param prefix if non-null, prepend to every line
     */
    public static String gnutls_key_fingerprint_randomart(final byte[] dgst_raw,
					final String key_type,
					final int key_size,
					final String prefix,
					final boolean unicode,
					final boolean html)
    {
        final String augmentation_string = unicode ? U_augmentation_string : A_augmentation_string;
        final char BOX_TOP = unicode ? U_BOX_TOP : A_BOX_TOP;
        final char BOX_BOTTOM = unicode ? U_BOX_BOTTOM : A_BOX_BOTTOM;
        final char BOX_LEFT = unicode ? U_BOX_LEFT : A_BOX_LEFT;
        final char BOX_RIGHT = unicode ? U_BOX_RIGHT : A_BOX_RIGHT;
        final char BOX_TL = unicode ? U_BOX_TL : A_BOX_TL;
        final char BOX_TR = unicode ? U_BOX_TR : A_BOX_TR;
        final char BOX_BL = unicode ? U_BOX_BL : A_BOX_BL;
        final char BOX_BR = unicode ? U_BOX_BR : A_BOX_BR;
        final String NL = System.getProperty("line.separator");

	final int dgst_raw_len = dgst_raw.length;
	final byte[][] field = new byte[FLDSIZE_X][FLDSIZE_Y];
	final byte[][] color = new byte[FLDSIZE_X][FLDSIZE_Y];
	final int len = augmentation_string.length() - 1;
	int prefix_len = 0;

	if (prefix != null)
		prefix_len = prefix.length();

	int x = FLDSIZE_X / 2;
	int y = FLDSIZE_Y / 2;

	/* process raw key */
	for (int i = 0; i < dgst_raw_len; i++) {
		int input;
		/* each byte conveys four 2-bit move commands */
		input = dgst_raw[i];
		for (int b = 0; b < 4; b++) {
			/* evaluate 2 bit, rest is shifted later */
			x += ((input & 0x1) != 0) ? 1 : -1;
			y += ((input & 0x2) != 0) ? 1 : -1;

			/* assure we are still in bounds */
			x = Math.max(x, 0);
			y = Math.max(y, 0);
			x = Math.min(x, FLDSIZE_X - 1);
			y = Math.min(y, FLDSIZE_Y - 1);

			/* augment the field */
			if ((field[x][y] & 0xff) < len - 2)
				field[x][y]++;
			color[x][y] = (byte) i;
			input = input >> 2;
		}
	}

	/* mark starting point and end point */
	field[FLDSIZE_X / 2][FLDSIZE_Y / 2] = (byte) (len - 1);
	field[x][y] = (byte) len;

	final String size_txt;
	if (key_size > 0)
		size_txt = String.format(" %4d", key_size);
	else
		size_txt = "";

	/* fill in retval */
	StringBuilder retval = new StringBuilder(1024);
        long base = 0;
        if (html) {
		// Pick a color base. We use the first 3 bytes of the data,
		// but normalize by 75% since we're designed for a white background.
		int clen = Math.min(3, dgst_raw_len);
		byte[] cbase = new byte[clen];
		for (int i = 0; i < clen; i++) {
			cbase[i] = (byte) ((dgst_raw[i] & 0xff) * 5 / 8);
		}
		base = DataHelper.fromLong(cbase, 0, clen);
		retval.append("<font color=\"#")
		      .append(getColor(base, 0))
		      .append("\"><pre>\n");
	}
	if (prefix_len > 0)
		retval.append(String.format("%s" + BOX_TL + BOX_TOP + BOX_TOP + "[%4s%s]",
			 prefix, key_type, size_txt));
	else
		retval.append(String.format("" + BOX_TL + BOX_TOP + BOX_TOP + "[%4s%s]", key_type,
			 size_txt));

	/* output upper border */
	for (int i = 0; i < FLDSIZE_X - Math.max(key_type.length(), 4) - 9; i++)
		retval.append(BOX_TOP);
	retval.append(BOX_TR);
	retval.append(NL);

	if (prefix_len > 0) {
		retval.append(prefix);
	}

	/* output content */
	for (y = 0; y < FLDSIZE_Y; y++) {
		retval.append(BOX_LEFT);
		for (x = 0; x < FLDSIZE_X; x++) {
			int idx = Math.min(field[x][y], len);
			if (html && idx != 0)
				retval.append("<span style=\"color: #")
				      .append(getColor(base, color[x][y] & 0xff))
				      .append("\">");
			retval.append(augmentation_string.charAt(idx));
			if (html && idx != 0)
				retval.append("</span>");
                }
		retval.append(BOX_RIGHT);
		retval.append(NL);

		if (prefix_len > 0) {
			retval.append(prefix);
		}
	}

	/* output lower border */
	retval.append(BOX_BL);
	for (int i = 0; i < FLDSIZE_X; i++)
		retval.append(BOX_BOTTOM);
	retval.append(BOX_BR);
	retval.append(NL);
        if (html)
		retval.append("</pre></font>\n");

	return retval.toString();
    }

    private static String getColor(long base, int mod) {
	if (mod != 0) {
		//base += mod * 16;
		//base += mod * 16 * 256;
		base += mod * (5 * 256 * 256L);
	}
	if (base > 0xffffff || base < 0)
		base &= 0xffffff;
	return String.format("%06x", base);
    }

    public static void main(String[] args) {
        try {
            boolean uni = true;
            boolean html = true;
            if (html)
                System.out.println("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"></head><body>");
            byte[] b = new byte[16];
            net.i2p.util.RandomSource.getInstance().nextBytes(b);
            String art = gnutls_key_fingerprint_randomart(b, "SHA", 128, null, uni, html);
            System.out.println(art);
            System.out.println("");
            b = new byte[32];
            for (int i = 0; i < 5; i++) {
                net.i2p.util.RandomSource.getInstance().nextBytes(b);
                art = gnutls_key_fingerprint_randomart(b, "XSHA", 256, null, uni, html);
                System.out.println(art);
                System.out.println("");
            }
            b = new byte[48];
            net.i2p.util.RandomSource.getInstance().nextBytes(b);
            art = gnutls_key_fingerprint_randomart(b, "XXSHA", 384, null, uni, html);
            System.out.println(art);
            System.out.println("");
            b = new byte[64];
            net.i2p.util.RandomSource.getInstance().nextBytes(b);
            art = gnutls_key_fingerprint_randomart(b, "XXXSHA", 512, null, uni, html);
            System.out.println(art);
            if (html)
                System.out.println("</body></html>");
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }
}
