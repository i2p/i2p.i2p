/*******************************************************************************
 * Copyright (c) 2001-2005 Sasa Markovic and Ciaran Treanor.
 * Copyright (c) 2011 The OpenNMS Group, Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *******************************************************************************/

///////////////////////////////////////////////////////////////////
// GifEncoder from J.M.G. Elliott
// http://jmge.net/java/gifenc/
///////////////////////////////////////////////////////////////////

package org.jrobin.graph;

import java.awt.*;
import java.awt.image.PixelGrabber;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Vector;

class GifEncoder {
	private Dimension dispDim = new Dimension(0, 0);
	private GifColorTable colorTable;
	private int bgIndex = 0;
	private int loopCount = 1;
	private String theComments;
	private Vector<Gif89Frame> vFrames = new Vector<Gif89Frame>();

	GifEncoder() {
		colorTable = new GifColorTable();
	}

	GifEncoder(Image static_image) throws IOException {
		this();
		addFrame(static_image);
	}

	GifEncoder(Color[] colors) {
		colorTable = new GifColorTable(colors);
	}

	GifEncoder(Color[] colors, int width, int height, byte ci_pixels[])
			throws IOException {
		this(colors);
		addFrame(width, height, ci_pixels);
	}

	int getFrameCount() {
		return vFrames.size();
	}

	Gif89Frame getFrameAt(int index) {
		return isOk(index) ? vFrames.elementAt(index) : null;
	}

	void addFrame(Gif89Frame gf) throws IOException {
		accommodateFrame(gf);
		vFrames.addElement(gf);
	}

	void addFrame(Image image) throws IOException {
		addFrame(new DirectGif89Frame(image));
	}

	void addFrame(int width, int height, byte ci_pixels[])
			throws IOException {
		addFrame(new IndexGif89Frame(width, height, ci_pixels));
	}

	void insertFrame(int index, Gif89Frame gf) throws IOException {
		accommodateFrame(gf);
		vFrames.insertElementAt(gf, index);
	}

	void setTransparentIndex(int index) {
		colorTable.setTransparent(index);
	}

	void setLogicalDisplay(Dimension dim, int background) {
		dispDim = new Dimension(dim);
		bgIndex = background;
	}

	void setLoopCount(int count) {
		loopCount = count;
	}

	void setComments(String comments) {
		theComments = comments;
	}

	void setUniformDelay(int interval) {
		for (int i = 0; i < vFrames.size(); ++i) {
			vFrames.elementAt(i).setDelay(interval);
		}
	}

	void encode(OutputStream out) throws IOException {
		int nframes = getFrameCount();
		boolean is_sequence = nframes > 1;
		colorTable.closePixelProcessing();
		Put.ascii("GIF89a", out);
		writeLogicalScreenDescriptor(out);
		colorTable.encode(out);
		if (is_sequence && loopCount != 1) {
			writeNetscapeExtension(out);
		}
		if (theComments != null && theComments.length() > 0) {
			writeCommentExtension(out);
		}
		for (int i = 0; i < nframes; ++i) {
			vFrames.elementAt(i).encode(
					out, is_sequence, colorTable.getDepth(), colorTable.getTransparent()
			);
		}
		out.write((int) ';');
		out.flush();
	}

	private void accommodateFrame(Gif89Frame gf) throws IOException {
		dispDim.width = Math.max(dispDim.width, gf.getWidth());
		dispDim.height = Math.max(dispDim.height, gf.getHeight());
		colorTable.processPixels(gf);
	}

	private void writeLogicalScreenDescriptor(OutputStream os) throws IOException {
		Put.leShort(dispDim.width, os);
		Put.leShort(dispDim.height, os);
		os.write(0xf0 | colorTable.getDepth() - 1);
		os.write(bgIndex);
		os.write(0);
	}


	private void writeNetscapeExtension(OutputStream os) throws IOException {
		os.write((int) '!');
		os.write(0xff);
		os.write(11);
		Put.ascii("NETSCAPE2.0", os);
		os.write(3);
		os.write(1);
		Put.leShort(loopCount > 1 ? loopCount - 1 : 0, os);
		os.write(0);
	}


	private void writeCommentExtension(OutputStream os) throws IOException {
		os.write((int) '!');
		os.write(0xfe);
		int remainder = theComments.length() % 255;
		int nsubblocks_full = theComments.length() / 255;
		int nsubblocks = nsubblocks_full + (remainder > 0 ? 1 : 0);
		int ibyte = 0;
		for (int isb = 0; isb < nsubblocks; ++isb) {
			int size = isb < nsubblocks_full ? 255 : remainder;
			os.write(size);
			Put.ascii(theComments.substring(ibyte, ibyte + size), os);
			ibyte += size;
		}
		os.write(0);
	}


	private boolean isOk(int frame_index) {
		return frame_index >= 0 && frame_index < vFrames.size();
	}
}

class DirectGif89Frame extends Gif89Frame {
	private int[] argbPixels;

	DirectGif89Frame(Image img) throws IOException {
		PixelGrabber pg = new PixelGrabber(img, 0, 0, -1, -1, true);
		String errmsg = null;
		try {
			if (!pg.grabPixels()) {
				errmsg = "can't grab pixels from image";
			}
		}
		catch (InterruptedException e) {
			errmsg = "interrupted grabbing pixels from image";
		}
		if (errmsg != null) {
			throw new IOException(errmsg + " (" + getClass().getName() + ")");
		}
		theWidth = pg.getWidth();
		theHeight = pg.getHeight();
		argbPixels = (int[]) pg.getPixels();
		ciPixels = new byte[argbPixels.length];
	}

	DirectGif89Frame(int width, int height, int argb_pixels[]) {
		theWidth = width;
		theHeight = height;
		argbPixels = new int[theWidth * theHeight];
		System.arraycopy(argb_pixels, 0, argbPixels, 0, argbPixels.length);
		ciPixels = new byte[argbPixels.length];
	}

	Object getPixelSource() {
		return argbPixels;
	}
}


class GifColorTable {
	private int[] theColors = new int[256];
	private int colorDepth;
	private int transparentIndex = -1;
	private int ciCount = 0;
	private ReverseColorMap ciLookup;

	GifColorTable() {
		ciLookup = new ReverseColorMap();
	}

	GifColorTable(Color[] colors) {
		int n2copy = Math.min(theColors.length, colors.length);
		for (int i = 0; i < n2copy; ++i) {
			theColors[i] = colors[i].getRGB();
		}
	}

	int getDepth() {
		return colorDepth;
	}

	int getTransparent() {
		return transparentIndex;
	}

	void setTransparent(int color_index) {
		transparentIndex = color_index;
	}

	void processPixels(Gif89Frame gf) throws IOException {
		if (gf instanceof DirectGif89Frame) {
			filterPixels((DirectGif89Frame) gf);
		}
		else {
			trackPixelUsage((IndexGif89Frame) gf);
		}
	}

	void closePixelProcessing() {
		colorDepth = computeColorDepth(ciCount);
	}

	void encode(OutputStream os) throws IOException {
		int palette_size = 1 << colorDepth;
		for (int i = 0; i < palette_size; ++i) {
			os.write(theColors[i] >> 16 & 0xff);
			os.write(theColors[i] >> 8 & 0xff);
			os.write(theColors[i] & 0xff);
		}
	}

	private void filterPixels(DirectGif89Frame dgf) throws IOException {
		if (ciLookup == null) {
			throw new IOException("RGB frames require palette autodetection");
		}
		int[] argb_pixels = (int[]) dgf.getPixelSource();
		byte[] ci_pixels = dgf.getPixelSink();
		int npixels = argb_pixels.length;
		for (int i = 0; i < npixels; ++i) {
			int argb = argb_pixels[i];
			if ((argb >>> 24) < 0x80) {
				if (transparentIndex == -1) {
					transparentIndex = ciCount;
				}
				else if (argb != theColors[transparentIndex]) {
					ci_pixels[i] = (byte) transparentIndex;
					continue;
				}
			}
			int color_index = ciLookup.getPaletteIndex(argb & 0xffffff);
			if (color_index == -1) {
				if (ciCount == 256) {
					throw new IOException("can't encode as GIF (> 256 colors)");
				}
				theColors[ciCount] = argb;
				ciLookup.put(argb & 0xffffff, ciCount);
				ci_pixels[i] = (byte) ciCount;
				++ciCount;
			}
			else {
				ci_pixels[i] = (byte) color_index;
			}
		}
	}

	private void trackPixelUsage(IndexGif89Frame igf) {
		byte[] ci_pixels = (byte[]) igf.getPixelSource();
		int npixels = ci_pixels.length;
		for (int i = 0; i < npixels; ++i) {
			if (ci_pixels[i] >= ciCount) {
				ciCount = ci_pixels[i] + 1;
			}
		}
	}

	private int computeColorDepth(int colorcount) {
		if (colorcount <= 2) {
			return 1;
		}
		if (colorcount <= 4) {
			return 2;
		}
		if (colorcount <= 16) {
			return 4;
		}
		return 8;
	}
}

class ReverseColorMap {
	private static class ColorRecord {
		int rgb;
		int ipalette;

		ColorRecord(int rgb, int ipalette) {
			this.rgb = rgb;
			this.ipalette = ipalette;
		}
	}

	private static final int HCAPACITY = 2053;
	private ColorRecord[] hTable = new ColorRecord[HCAPACITY];

	int getPaletteIndex(int rgb) {
		ColorRecord rec;
		for (int itable = rgb % hTable.length;
			 (rec = hTable[itable]) != null && rec.rgb != rgb;
			 itable = ++itable % hTable.length
				) {
			;
		}
		if (rec != null) {
			return rec.ipalette;
		}
		return -1;
	}


	void put(int rgb, int ipalette) {
		int itable;
		for (itable = rgb % hTable.length;
			 hTable[itable] != null;
			 itable = ++itable % hTable.length
				) {
			;
		}
		hTable[itable] = new ColorRecord(rgb, ipalette);
	}
}

abstract class Gif89Frame {
	static final int DM_UNDEFINED = 0;
	static final int DM_LEAVE = 1;
	static final int DM_BGCOLOR = 2;
	static final int DM_REVERT = 3;
	int theWidth = -1;
	int theHeight = -1;
	byte[] ciPixels;

	private Point thePosition = new Point(0, 0);
	private boolean isInterlaced;
	private int csecsDelay;
	private int disposalCode = DM_LEAVE;

	void setPosition(Point p) {
		thePosition = new Point(p);
	}

	void setInterlaced(boolean b) {
		isInterlaced = b;
	}

	void setDelay(int interval) {
		csecsDelay = interval;
	}

	void setDisposalMode(int code) {
		disposalCode = code;
	}

	Gif89Frame() {
	}

	abstract Object getPixelSource();

	int getWidth() {
		return theWidth;
	}

	int getHeight() {
		return theHeight;
	}

	byte[] getPixelSink() {
		return ciPixels;
	}

	void encode(OutputStream os, boolean epluribus, int color_depth,
				int transparent_index) throws IOException {
		writeGraphicControlExtension(os, epluribus, transparent_index);
		writeImageDescriptor(os);
		new GifPixelsEncoder(
				theWidth, theHeight, ciPixels, isInterlaced, color_depth
		).encode(os);
	}

	private void writeGraphicControlExtension(OutputStream os, boolean epluribus,
											  int itransparent) throws IOException {
		int transflag = itransparent == -1 ? 0 : 1;
		if (transflag == 1 || epluribus) {
			os.write((int) '!');
			os.write(0xf9);
			os.write(4);
			os.write((disposalCode << 2) | transflag);
			Put.leShort(csecsDelay, os);
			os.write(itransparent);
			os.write(0);
		}
	}

	private void writeImageDescriptor(OutputStream os) throws IOException {
		os.write((int) ',');
		Put.leShort(thePosition.x, os);
		Put.leShort(thePosition.y, os);
		Put.leShort(theWidth, os);
		Put.leShort(theHeight, os);
		os.write(isInterlaced ? 0x40 : 0);
	}
}

class GifPixelsEncoder {
	private static final int EOF = -1;
	private int imgW, imgH;
	private byte[] pixAry;
	private boolean wantInterlaced;
	private int initCodeSize;
	private int countDown;
	private int xCur, yCur;
	private int curPass;

	GifPixelsEncoder(int width, int height, byte[] pixels, boolean interlaced,
					 int color_depth) {
		imgW = width;
		imgH = height;
		pixAry = pixels;
		wantInterlaced = interlaced;
		initCodeSize = Math.max(2, color_depth);
	}

	void encode(OutputStream os) throws IOException {
		os.write(initCodeSize);

		countDown = imgW * imgH;
		xCur = yCur = curPass = 0;

		compress(initCodeSize + 1, os);

		os.write(0);
	}

	private void bumpPosition() {
		++xCur;
		if (xCur == imgW) {
			xCur = 0;
			if (!wantInterlaced) {
				++yCur;
			}
			else {
				switch (curPass) {
					case 0:
						yCur += 8;
						if (yCur >= imgH) {
							++curPass;
							yCur = 4;
						}
						break;
					case 1:
						yCur += 8;
						if (yCur >= imgH) {
							++curPass;
							yCur = 2;
						}
						break;
					case 2:
						yCur += 4;
						if (yCur >= imgH) {
							++curPass;
							yCur = 1;
						}
						break;
					case 3:
						yCur += 2;
						break;
				}
			}
		}
	}

	private int nextPixel() {
		if (countDown == 0) {
			return EOF;
		}
		--countDown;
		byte pix = pixAry[yCur * imgW + xCur];
		bumpPosition();
		return pix & 0xff;
	}

	static final int BITS = 12;
	static final int HSIZE = 5003;
	int n_bits;
	int maxbits = BITS;
	int maxcode;
	int maxmaxcode = 1 << BITS;

	final int MAXCODE(int n_bits) {
		return (1 << n_bits) - 1;
	}

	int[] htab = new int[HSIZE];
	int[] codetab = new int[HSIZE];
	int hsize = HSIZE;
	int free_ent = 0;
	boolean clear_flg = false;
	int g_init_bits;
	int ClearCode;
	int EOFCode;

	void compress(int init_bits, OutputStream outs) throws IOException {
		int fcode;
		int i /* = 0 */;
		int c;
		int ent;
		int disp;
		int hsize_reg;
		int hshift;
		g_init_bits = init_bits;
		clear_flg = false;
		n_bits = g_init_bits;
		maxcode = MAXCODE(n_bits);
		ClearCode = 1 << (init_bits - 1);
		EOFCode = ClearCode + 1;
		free_ent = ClearCode + 2;

		char_init();
		ent = nextPixel();
		hshift = 0;
		for (fcode = hsize; fcode < 65536; fcode *= 2) {
			++hshift;
		}
		hshift = 8 - hshift;
		hsize_reg = hsize;
		cl_hash(hsize_reg);
		output(ClearCode, outs);
		outer_loop:
		while ((c = nextPixel()) != EOF) {
			fcode = (c << maxbits) + ent;
			i = (c << hshift) ^ ent;
			if (htab[i] == fcode) {
				ent = codetab[i];
				continue;
			}
			else if (htab[i] >= 0) {
				disp = hsize_reg - i;
				if (i == 0) {
					disp = 1;
				}
				do {
					if ((i -= disp) < 0) {
						i += hsize_reg;
					}

					if (htab[i] == fcode) {
						ent = codetab[i];
						continue outer_loop;
					}
				} while (htab[i] >= 0);
			}
			output(ent, outs);
			ent = c;
			if (free_ent < maxmaxcode) {
				codetab[i] = free_ent++;
				htab[i] = fcode;
			}
			else {
				cl_block(outs);
			}
		}
		output(ent, outs);
		output(EOFCode, outs);
	}

	int cur_accum = 0;
	int cur_bits = 0;
	int masks[] = {0x0000, 0x0001, 0x0003, 0x0007, 0x000F,
			0x001F, 0x003F, 0x007F, 0x00FF,
			0x01FF, 0x03FF, 0x07FF, 0x0FFF,
			0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF};

	void output(int code, OutputStream outs) throws IOException {
		cur_accum &= masks[cur_bits];
		if (cur_bits > 0) {
			cur_accum |= (code << cur_bits);
		}
		else {
			cur_accum = code;
		}

		cur_bits += n_bits;

		while (cur_bits >= 8) {
			char_out((byte) (cur_accum & 0xff), outs);
			cur_accum >>= 8;
			cur_bits -= 8;
		}
		if (free_ent > maxcode || clear_flg) {
			if (clear_flg) {
				maxcode = MAXCODE(n_bits = g_init_bits);
				clear_flg = false;
			}
			else {
				++n_bits;
				if (n_bits == maxbits) {
					maxcode = maxmaxcode;
				}
				else {
					maxcode = MAXCODE(n_bits);
				}
			}
		}
		if (code == EOFCode) {

			while (cur_bits > 0) {
				char_out((byte) (cur_accum & 0xff), outs);
				cur_accum >>= 8;
				cur_bits -= 8;
			}
			flush_char(outs);
		}
	}


	void cl_block(OutputStream outs) throws IOException {
		cl_hash(hsize);
		free_ent = ClearCode + 2;
		clear_flg = true;

		output(ClearCode, outs);
	}


	void cl_hash(int hsize) {
		for (int i = 0; i < hsize; ++i) {
			htab[i] = -1;
		}
	}

	int a_count;

	void char_init() {
		a_count = 0;
	}

	byte[] accum = new byte[256];

	void char_out(byte c, OutputStream outs) throws IOException {
		accum[a_count++] = c;
		if (a_count >= 254) {
			flush_char(outs);
		}
	}

	void flush_char(OutputStream outs) throws IOException {
		if (a_count > 0) {
			outs.write(a_count);
			outs.write(accum, 0, a_count);
			a_count = 0;
		}
	}
}

class IndexGif89Frame extends Gif89Frame {

	IndexGif89Frame(int width, int height, byte ci_pixels[]) {
		theWidth = width;
		theHeight = height;
		ciPixels = new byte[theWidth * theHeight];
		System.arraycopy(ci_pixels, 0, ciPixels, 0, ciPixels.length);
	}

	Object getPixelSource() {
		return ciPixels;
	}
}


final class Put {
	static void ascii(String s, OutputStream os) throws IOException {
		byte[] bytes = new byte[s.length()];
		for (int i = 0; i < bytes.length; ++i) {
			bytes[i] = (byte) s.charAt(i);
		}
		os.write(bytes);
	}

	static void leShort(int i16, OutputStream os) throws IOException {
		os.write(i16 & 0xff);
		os.write(i16 >> 8 & 0xff);
	}
}