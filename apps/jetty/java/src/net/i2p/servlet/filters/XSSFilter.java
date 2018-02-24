package net.i2p.servlet.filters;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 *  @since 0.9.14
 */
public class XSSFilter implements Filter {
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void destroy() {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        try {
            chain.doFilter(new XSSRequestWrapper((HttpServletRequest) request), response);
        } catch (IllegalStateException ise) {
            // Multipart form error, probably file too big
            // We need to send the error quickly, if we just throw a ServletException,
            // the data keeps coming and the connection gets reset.
            // This way we at least get the error to the browser.
            try {
                ((HttpServletResponse)response).sendError(413, ise.getMessage());
            } catch (IllegalStateException ise2) {
                // Committed, probably wasn't a multipart form error after all
            }
        }
    }
}
