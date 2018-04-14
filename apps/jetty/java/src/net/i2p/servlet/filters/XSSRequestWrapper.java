package net.i2p.servlet.filters;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

//import org.owasp.esapi.ESAPI;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 *  @since 0.9.14
 */
public class XSSRequestWrapper extends HttpServletRequestWrapper {
    // Adapted from https://owasp-esapi-java.googlecode.com/svn/trunk/configuration/esapi/ESAPI.properties
    private static final String NON_WIN_PATTERN = "^[\\p{L}\\p{Nd}.,:\\-\\/+=~\\[\\]?@_ \r\n]*$";
    // Same as above but with backslash for file paths
    private static final String WIN_PATTERN     = "^[\\p{L}\\p{Nd}.,:\\-\\/+=~\\[\\]?@_ \r\n\\\\]*$";
    private static final Pattern parameterValuePattern = Pattern.compile(SystemVersion.isWindows() ? WIN_PATTERN : NON_WIN_PATTERN);
    private static final Pattern headerValuePattern = Pattern.compile("^[a-zA-Z0-9()\\-=\\*\\.\\?;,+\\/:&_ \"]*$");
    private static final String NOFILTER = "nofilter_";

    public XSSRequestWrapper(HttpServletRequest servletRequest) {
        super(servletRequest);
    }

    /**
     *  Parameter names starting with "nofilter_" will not be filtered.
     */
    @Override
    public String[] getParameterValues(String parameter) {
        String[] values = super.getParameterValues(parameter);
        if (parameter.startsWith(NOFILTER))
            return values;

        if (values == null) {
            return null;
        }

        int count = values.length;
        String[] encodedValues = new String[count];
        int good = 0;
        for (int i = 0; i < count; i++) {
            String value = values[i];
            String v2 = stripXSS(value, parameterValuePattern);
            if (v2 != null) {
                encodedValues[good++] = v2;
            } else if (value != null) {
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(XSSRequestWrapper.class);
                log.logAlways(Log.WARN, "URL \"" + getServletPath() + "\" Stripped param \"" + parameter + "\" : \"" + value + '"');
            }
        }
        if (good <= 0)
            return null;
        if (good < count) {
            // shrink array
            String[] rv = new String[good];
            System.arraycopy(encodedValues, 0, rv, 0, good);
            encodedValues = rv;
        }
        return encodedValues;
    }

    /**
     *  Parameter names starting with "nofilter_" will not be filtered.
     */
    @Override
    public String getParameter(String parameter) {
        String value = super.getParameter(parameter);
        if (parameter.startsWith(NOFILTER))
            return value;
        String rv = stripXSS(value, parameterValuePattern);
        if (value != null && rv == null) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(XSSRequestWrapper.class);
            log.logAlways(Log.WARN, "URL \"" + getServletPath() + "\" Stripped param \"" + parameter + "\" : \"" + value + '"');
        }
        return rv;
    }

    /**
     *  Parameter names starting with "nofilter_" will not be filtered.
     */
    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> rv = new HashMap<String, String[]>();
        for (Enumeration<String> keys = getParameterNames(); keys.hasMoreElements(); ) {
             String k = keys.nextElement();
             String[] v = getParameterValues(k);
             if (v != null)
                 rv.put(k, v);
        }
        return Collections.unmodifiableMap(rv);
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

    private static String stripXSS(String value, Pattern whitelistPattern) {
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
