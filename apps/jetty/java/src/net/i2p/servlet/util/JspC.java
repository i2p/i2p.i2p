package net.i2p.servlet.util;

import java.lang.reflect.Method;

/**
 *  Simply call org.apache.jasper.JspC, then exit.
 *
 *  As of Tomcat 8.5.33, forking their JspC won't complete,
 *  because the JspC compilation is now threaded and the thread pool workers aren't daemons.
 *  May be fixed in a future release, maybe not, but we don't know what version distros may have.
 *
 *  https://tomcat.apache.org/tomcat-8.5-doc/changelog.html
 *  https://bz.apache.org/bugzilla/show_bug.cgi?id=53492
 *
 *  We could set fork=false in build.xml, but then the paths are all wrong.
 *  Only for use in build scripts, obviously not a public API.
 *  See apps/routerconsole/java/build.xml for more information.
 *
 *  @since 0.9.37
 */
public class JspC {
    public static void main(String args[]) {
       try {
           String cls = "org.apache.jasper.JspC";
           Class<?> c = Class.forName(cls, true, ClassLoader.getSystemClassLoader());
           Method main = c.getMethod("main", String[].class);
           main.invoke(null, (Object) args);
           System.exit(0);
       } catch (Exception e) {
           e.printStackTrace();
           System.exit(1);
       }
    }
}
