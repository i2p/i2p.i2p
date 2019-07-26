package net.i2p.servlet;

import org.apache.tomcat.SimpleInstanceManager;
import org.eclipse.jetty.deploy.providers.WebAppProvider;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;

import net.i2p.I2PAppContext;

/**
 *  Work around the
 *  "No org.apache.tomcat.InstanceManager set in ServletContext" problem
 *  for eepsites with webapps.
 *
 *  See WebAppStarter and WebAppConfiguration for the console version.
 *
 *  @since 0.9.41
 */
public class WebAppProviderConfiguration {

    /**
     *  Modified from routerconsole WebAppStarter.
     *  MUST be called from jetty.xml after the WebAppProvider is created.
     */
    public static void configure(WebAppProvider wap) {
        String[] classNames = WebAppContext.getDefaultConfigurationClasses();
        int sz = classNames.length;
        String[] newClassNames = new String[sz + 1];
        for (int j = 0; j < sz; j++) {
            newClassNames[j] = classNames[j];
        }
        newClassNames[sz] = WAPConfiguration.class.getName();
        wap.setConfigurationClasses(newClassNames);

        // set the temp dir while we're at it,
        // so the extracted wars don't end up in /tmp
        wap.setTempDir(I2PAppContext.getGlobalContext().getTempDir());
    }

    public static class WAPConfiguration implements Configuration {

        public void deconfigure(WebAppContext context) {}

        public void configure(WebAppContext context) throws Exception {
            // http://stackoverflow.com/questions/17529936/issues-while-using-jetty-embedded-to-handle-jsp-jasperexception-unable-to-com
            // https://github.com/jetty-project/embedded-jetty-jsp/blob/master/src/main/java/org/eclipse/jetty/demo/Main.java
            context.getServletContext().setAttribute("org.apache.tomcat.InstanceManager", new SimpleInstanceManager());
        }

        public void cloneConfigure(WebAppContext template, WebAppContext context) {}

        public void destroy(WebAppContext context) {}

        public void preConfigure(WebAppContext context) {}

        public void postConfigure(WebAppContext context) {}
    }
}
