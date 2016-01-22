package net.i2p.imagegen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.docuverse.identicon.IdenticonUtil;

import net.i2p.data.Hash;
import net.i2p.util.ConvertToHash;

/**
 * This servlet generates random art (visual identifier) images.
 * 
 * @author modiied from identicon
 * @since 0.9.25
 */
public class RandomArtServlet extends HttpServlet {

	private static final long serialVersionUID = -3507466186902317988L;
	private static final String PARAM_IDENTICON_CODE_SHORT = "c";
	private static final long DEFAULT_IDENTICON_EXPIRES_IN_MILLIS = 24 * 60 * 60 * 1000;
	private int version = 1;
	private long identiconExpiresInMillis = DEFAULT_IDENTICON_EXPIRES_IN_MILLIS;

	@Override
	protected void doGet(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		String codeParam = request.getParameter(PARAM_IDENTICON_CODE_SHORT);
		boolean codeSpecified = codeParam != null && codeParam.length() > 0;
		if (!codeSpecified)
			codeParam="stats.i2p";
		String identiconETag = IdenticonUtil.getIdenticonETag(codeParam.hashCode(), 0,
				version);
		String requestETag = request.getHeader("If-None-Match");

		if (requestETag != null && requestETag.equals(identiconETag)) {
			response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
		} else {
			Hash h = ConvertToHash.getHash(codeParam);
			if (h == null) {
				response.setStatus(403);
			} else {
				boolean html = true;
				StringBuilder buf = new StringBuilder(512);
				if (html) {
					response.setContentType("text/html");
					buf.append("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"></head><body>");
				} else {
					response.setContentType("text/plain");
				}
				buf.append(RandomArt.gnutls_key_fingerprint_randomart(h.getData(), "SHA", 256, "", true, html));
				if (html)
					buf.append("</body></html>");

				// set ETag and, if code was provided, Expires header
				response.setHeader("ETag", identiconETag);
				if (codeSpecified) {
					long expires = System.currentTimeMillis()
							+ identiconExpiresInMillis;
					response.addDateHeader("Expires", expires);
				}
	
				// return image bytes to requester
				byte[] imageBytes = buf.toString().getBytes("UTF-8");
				response.setContentLength(imageBytes.length);
				response.getOutputStream().write(imageBytes);
			}
		}
	}
}
