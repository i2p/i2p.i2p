package net.i2p.imagegen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

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
	private static final String IDENTICON_IMAGE_FORMAT = "PNG";
	private static final String IDENTICON_IMAGE_MIMETYPE = "image/png";
	private static final long DEFAULT_IDENTICON_EXPIRES_IN_MILLIS = 24 * 60 * 60 * 1000;
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
				Class cacheClass = Class.forName(cacheProvider);
				this.cache = (IdenticonCache) cacheClass.newInstance();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	protected void doGet(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		String codeParam = request.getParameter(PARAM_IDENTICON_CODE_SHORT);
		boolean codeSpecified = codeParam != null && codeParam.length() > 0;
		if (!codeSpecified) {
			// TODO 404
			codeParam="http://stats.i2p/?i2paddresshelper=Okd5sN9hFWx-sr0HH8EFaxkeIMi6PC5eGTcjM1KB7uQ0ffCUJ2nVKzcsKZFHQc7pLONjOs2LmG5H-2SheVH504EfLZnoB7vxoamhOMENnDABkIRGGoRisc5AcJXQ759LraLRdiGSR0WTHQ0O1TU0hAz7vAv3SOaDp9OwNDr9u902qFzzTKjUTG5vMTayjTkLo2kOwi6NVchDeEj9M7mjj5ySgySbD48QpzBgcqw1R27oIoHQmjgbtbmV2sBL-2Tpyh3lRe1Vip0-K0Sf4D-Zv78MzSh8ibdxNcZACmZiVODpgMj2ejWJHxAEz41RsfBpazPV0d38Mfg4wzaS95R5hBBo6SdAM4h5vcZ5ESRiheLxJbW0vBpLRd4mNvtKOrcEtyCvtvsP3FpA-6IKVswyZpHgr3wn6ndDHiVCiLAQZws4MsIUE1nkfxKpKtAnFZtPrrB8eh7QO9CkH2JBhj7bG0ED6mV5~X5iqi52UpsZ8gnjZTgyG5pOF8RcFrk86kHxAAAA";
			//response.setStatus(403);
			//return;
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
				MatrixToImageWriter.writeToStream(matrix, IDENTICON_IMAGE_FORMAT, byteOut);
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
			response.setContentLength(imageBytes.length);
			response.getOutputStream().write(imageBytes);
		}
	}
}
