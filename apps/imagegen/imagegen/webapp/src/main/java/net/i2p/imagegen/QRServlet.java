package net.i2p.imagegen;

/* contains code adapted from jrobin: */
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

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.docuverse.identicon.IdenticonCache;
import com.docuverse.identicon.IdenticonUtil;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import net.i2p.util.SystemVersion;

/**
 * This servlet generates QR code images.
 * 
 * @author modiied from identicon
 * @since 0.9.25
 */
public class QRServlet extends HttpServlet {

	private static final long serialVersionUID = -3507466186902317988L;
	private static final String INIT_PARAM_VERSION = "version";
	private static final String INIT_PARAM_CACHE_PROVIDER = "cacheProvider";
	private static final String PARAM_IDENTICON_SIZE_SHORT = "s";
	private static final String PARAM_IDENTICON_CODE_SHORT = "c";
	private static final String PARAM_IDENTICON_TEXT_SHORT = "t";
	private static final String IDENTICON_IMAGE_FORMAT = "PNG";
	private static final String IDENTICON_IMAGE_MIMETYPE = "image/png";
	private static final long DEFAULT_IDENTICON_EXPIRES_IN_MILLIS = 24 * 60 * 60 * 1000;
        // TODO the fonts all look terrible. See also the rendering hints below, nothing helps
	private static final String DEFAULT_FONT_NAME = SystemVersion.isWindows() ?
	                                                "Lucida Sans Typewriter" : Font.SANS_SERIF;	
	private static final Font DEFAULT_LARGE_FONT = new Font(DEFAULT_FONT_NAME, Font.BOLD, 16);

	private int version = 1;
	private IdenticonCache cache;
	private long identiconExpiresInMillis = DEFAULT_IDENTICON_EXPIRES_IN_MILLIS;

	@Override
	public void init(ServletConfig cfg) throws ServletException {
		super.init(cfg);

		// Since identicons cache expiration is very long, version is
		// used in ETag to force identicons to be updated as needed.
		// Change veresion whenever rendering codes changes result in
		// visual changes.
		if (cfg.getInitParameter(INIT_PARAM_VERSION) != null)
			this.version = Integer.parseInt(cfg
					.getInitParameter(INIT_PARAM_VERSION));

		String cacheProvider = cfg.getInitParameter(INIT_PARAM_CACHE_PROVIDER);
		if (cacheProvider != null) {
			try {
				Class<?> cacheClass = Class.forName(cacheProvider);
				this.cache = (IdenticonCache) cacheClass.getDeclaredConstructor().newInstance();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	protected void doGet(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		if (request.getCharacterEncoding() == null)
			request.setCharacterEncoding("UTF-8");
		String codeParam = request.getParameter(PARAM_IDENTICON_CODE_SHORT);
		boolean codeSpecified = codeParam != null && codeParam.length() > 0;
		if (!codeSpecified) {
			response.setStatus(403);
			return;
		}

		String sizeParam = request.getParameter(PARAM_IDENTICON_SIZE_SHORT);
		// very rougly, number of "modules" is about 4 * sqrt(chars)
		// (assuming 7 bit) default margin each side is 4
		// assuming level L
		// min modules is 21x21
		// shoot for 2 pixels per module
                int size = Math.max(50, (2 * 4) + (int) (2 * 5 * Math.sqrt(codeParam.length())));
		if (sizeParam != null) {
			try {
				size = Integer.parseInt(sizeParam);
				if (size < 40)
					size = 40;
				else if (size > 512)
					size = 512;
			} catch (NumberFormatException nfe) {}
		}

		String identiconETag = IdenticonUtil.getIdenticonETag(codeParam.hashCode(), size,
				version);
		String requestETag = request.getHeader("If-None-Match");

		if (requestETag != null && requestETag.equals(identiconETag)) {
			response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
		} else {
			byte[] imageBytes = null;

			// retrieve image bytes from either cache or renderer
			if (cache == null
					|| (imageBytes = cache.get(identiconETag)) == null) {
				ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
				QRCodeWriter qrcw = new QRCodeWriter();
				BitMatrix matrix;
				try {
					matrix = qrcw.encode(codeParam, BarcodeFormat.QR_CODE, size, size);
				} catch (WriterException we) {
					throw new IOException("encode failed", we);
				}
				String text = request.getParameter(PARAM_IDENTICON_TEXT_SHORT);
				if (text != null) {
					BufferedImage bi = MatrixToImageWriter.toBufferedImage(matrix);
					Graphics2D g = bi.createGraphics();
					// anti-aliasing and hinting degrade text with 1bit input, so let's turn this off to improve quality  
//					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
//					g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
//					g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
//					g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE);
//					g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
//					g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
					Font font = DEFAULT_LARGE_FONT;
					g.setFont(font);
					// doesn't work
					Color color = Color.RED;
					g.setColor(color);
					int width = bi.getWidth();
					int height = bi.getHeight();
					double swidth = font.getStringBounds(text, 0, text.length(),
					                                     g.getFontRenderContext()).getBounds().getWidth();
					int x = (width - (int) swidth) / 2;
					int y = height - 10;
					g.drawString(text, x, y);
					if (!ImageIO.write(bi, IDENTICON_IMAGE_FORMAT, byteOut))
						throw new IOException("ImageIO.write() fail");
				} else {
					MatrixToImageWriter.writeToStream(matrix, IDENTICON_IMAGE_FORMAT, byteOut);
				}
				imageBytes = byteOut.toByteArray();
				if (cache != null)
					cache.add(identiconETag, imageBytes);
			} else {
				response.setStatus(403);
				return;
			}

			// set ETag and, if code was provided, Expires header
			response.setHeader("ETag", identiconETag);
			if (codeSpecified) {
				long expires = System.currentTimeMillis()
						+ identiconExpiresInMillis;
				response.addDateHeader("Expires", expires);
			}

			// return image bytes to requester
			response.setContentType(IDENTICON_IMAGE_MIMETYPE);
			response.setHeader("X-Content-Type-Options", "nosniff");
			response.setHeader("Accept-Ranges", "none");
			response.setContentLength(imageBytes.length);
			response.getOutputStream().write(imageBytes);
		}
	}
}
