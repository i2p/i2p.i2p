package net.i2p.jetty;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;
import org.slf4j.spi.MDCAdapter;

/**
 *  An SLF4J Service Provider logging to router log with levels,
 *  replacing Jetty's Service Provider that logs to stderr
 *  (wrapper log).
 *
 *  As this is a SLF4JServiceProvider, it requires SLF4J 2.0.x.
 *  It is enabled by setting the environment as follows:
 *
 *<pre>
 *      System.setProperty("slf4j.provider", "net.i2p.jetty.I2PLoggingServiceProvider");
 *</pre>
 *
 *  We do this in RouterConsoleRunner and in i2psnark RunStandalone.
 *
 *  @since Jetty 12
 */
public class I2PLoggingServiceProvider implements SLF4JServiceProvider {
    private final ILoggerFactory lf;
    private final IMarkerFactory mf;
    private final MDCAdapter md;

    public I2PLoggingServiceProvider() {
        lf = new LoggerFactory();
        mf = new BasicMarkerFactory();
        md = new BasicMDCAdapter();
    }

    public ILoggerFactory getLoggerFactory() {
        return lf;
    }

    public IMarkerFactory getMarkerFactory() {
        return mf;
    }

    public MDCAdapter getMDCAdapter() {
        return md;
    }

    public String getRequestedApiVersion() {
        return "2.0.999";
    }

    public void initialize() {}

    private static class LoggerFactory implements ILoggerFactory {
        private final Logger logger;

        public LoggerFactory() {
            logger = new I2PLogger();
        }

        public Logger getLogger(String name) {
            return logger;
        }
    }

}
