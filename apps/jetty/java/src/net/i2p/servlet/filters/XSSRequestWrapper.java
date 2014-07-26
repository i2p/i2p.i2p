package net.i2p.servlet.filters;

import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

//import org.owasp.esapi.ESAPI;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 *  @since 0.9.14
 */
public class XSSRequestWrapper extends HttpServletRequestWrapper {
    // Adapted from https://owasp-esapi-java.googlecode.com/svn/trunk/configuration/esapi/ESAPI.properties
    private static final Pattern parameterValuePattern = Pattern.compile("^[\\p{L}\\p{Nd}.,:\\-\\/+=~\\[\\]?@_ \r\n]*$");
    private static final Pattern headerValuePattern = Pattern.compile("^[a-zA-Z0-9()\\-=\\*\\.\\?;,+\\/:&_ ]*$");

    public XSSRequestWrapper(HttpServletRequest servletRequest) {
        super(servletRequest);
    }

    @Override
    public String[] getParameterValues(String parameter) {
        String[] values = super.getParameterValues(parameter);

        if (values == null) {
            return null;
        }

        int count = values.length;
        String[] encodedValues = new String[count];
        for (int i = 0; i < count; i++) {
            encodedValues[i] = stripXSS(values[i], parameterValuePattern);
        }

        return encodedValues;
    }

    @Override
    public String getParameter(String parameter) {
        String value = super.getParameter(parameter);
        String rv = stripXSS(value, parameterValuePattern);
        if (value != null && rv == null) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(XSSRequestWrapper.class);
            log.logAlways(Log.WARN, "URL \"" + getServletPath() + "\" Stripped param \"" + parameter + "\" : \"" + value + '"');
        }
        return rv;
    }

    @Override
    public String getHeader(String name) {
        String value = super.getHeader(name);
        String rv = stripXSS(value, headerValuePattern);
        if (value != null && rv == null) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(XSSRequestWrapper.class);
            log.logAlways(Log.WARN, "URL \"" + getServletPath() + "\" Stripped header \"" + name + "\" : \"" + value + '"');
        }
        return rv;
    }

    private String stripXSS(String value, Pattern whitelistPattern) {
        if (value != null) {
            // NOTE: It's highly recommended to use the ESAPI library and uncomment the following line to
            // avoid encoded attacks.
            //value = ESAPI.encoder().canonicalize(value);

            // Remove bad parameters entirely.
            // NOTE: This doesn't consider whether null is acceptable.
            if (!whitelistPattern.matcher(value).matches()) {
                value = null;
            }
        }
        return value;
    }
}
