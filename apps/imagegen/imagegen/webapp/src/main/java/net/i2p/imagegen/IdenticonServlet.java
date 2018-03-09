package net.i2p.imagegen;

import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import javax.imageio.ImageIO;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.docuverse.identicon.IdenticonCache;
import com.docuverse.identicon.IdenticonRenderer;
import com.docuverse.identicon.IdenticonUtil;
import com.docuverse.identicon.NineBlockIdenticonRenderer2;

import net.i2p.data.Hash;
import net.i2p.util.ConvertToHash;


/**
 * This servlet generates <i>identicon</i> (visual identifier) images ranging
 * from 16x16 to 512x512 in size.
 * 
 * <h3>Supported Image Formats</h3>
 * <p>
 * Currently only PNG is supported because <code>javax.imageio</code> package
 * does not come with built-in GIF encoder and PNG is the only remaining
 * reasonable format.
 * </p>
 * <h3>Initialization Parameters:</h3>
 * <blockquote>
 * <dl>
 * <dt>inetSalt</dt>
 * <dd>salt used to generate identicon code with. must be fairly long.
 * (Required)</dd>
 * <dt>cacheProvider</dt>
 * <dd>full class path to <code>IdenticonCache</code> implementation.
 * (Optional)</dd>
 * </dl>
 * </blockquote>
 * <h3>Request ParametersP</h3>
 * <blockquote>
 * <dl>
 * <dt>code</dt>
 * <dd>identicon code to render. If missing, requester's IP addresses is used
 * to generated one. (Optional)</dd>
 * <dt>size</dt>
 * <dd>identicon size in pixels. If missing, a 16x16 pixels identicon is
 * returned. Minimum size is 16 and maximum is 64. (Optional)</dd>
 * </dl>
 * </blockquote>
 * 
 * @author don
 * @since 0.9.25
 */
public class IdenticonServlet extends HttpServlet {

	private static final long serialVersionUID = -3507466186902317988L;
	private static final String INIT_PARAM_VERSION = "version";
	private static final String INIT_PARAM_CACHE_PROVIDER = "cacheProvider";
	private static final String PARAM_IDENTICON_SIZE_SHORT = "s";
	private static final String PARAM_IDENTICON_CODE_SHORT = "c";
	private static final String IDENTICON_IMAGE_FORMAT = "PNG";
	private static final String IDENTICON_IMAGE_MIMETYPE = "image/png";
	private static final long DEFAULT_IDENTICON_EXPIRES_IN_MILLIS = 24 * 60 * 60 * 1000;
	private int version = 1;
	private final IdenticonRenderer renderer = new NineBlockIdenticonRenderer2();
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
		int size = 32;
		if (sizeParam != null) {
			try {
				size = Integer.parseInt(sizeParam);
				if (size < 16)
					size = 16;
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
			// we try to interpret the codeParam parameter as:
			// 1) a number
			// 2) a base32 or base64 hash, which we take the Java hashcode of
			// 3) a string, which we take the Java hashcode of
			int code;
			try {
				code = Integer.parseInt(codeParam);
			} catch (NumberFormatException nfe) {
				Hash h = ConvertToHash.getHash(codeParam);
				if (h != null)
					code = Arrays.hashCode(h.getData());
				else
					code = codeParam.hashCode();
			}
			byte[] imageBytes = null;

			// retrieve image bytes from either cache or renderer
			if (cache == null
					|| (imageBytes = cache.get(identiconETag)) == null) {
				ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
				RenderedImage image = renderer.render(code, size);
				ImageIO.write(image, IDENTICON_IMAGE_FORMAT, byteOut);
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
