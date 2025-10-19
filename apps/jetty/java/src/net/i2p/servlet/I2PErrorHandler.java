package net.i2p.servlet;

import java.io.File;

import org.eclipse.jetty.ee8.servlet.ErrorPageErrorHandler;

import net.i2p.I2PAppContext;
import net.i2p.util.FileSuffixFilter;

/**
 *  Customize the error page.
 *
 *  @since Jetty 12
 */
public class I2PErrorHandler extends ErrorPageErrorHandler
{
    private final File _docroot;
    private static final String RESOURCES = ".resources";

    public I2PErrorHandler() {
        this(new File(I2PAppContext.getGlobalContext().getConfigDir(), "eepsite/docroot"));
    }

    public I2PErrorHandler(String docroot) {
        this(new File(docroot));
    }

    public I2PErrorHandler(File docroot) {
        super();
        _docroot = docroot;
        setShowServlet(false);
        setShowStacks(false);
        setErrorPages();
    }

    /*
     *  Add error pages for any nnn.html files found.
     *  Also for the special files 000.html (default), 4xx.html, and 5xx.html.
     */
    private void setErrorPages() {
        File dir = new File(_docroot, RESOURCES);
        if (!dir.isDirectory())
            return;
        File[] files = dir.listFiles(new FileSuffixFilter(".html"));
        if (files == null)
            return;
        for (File file : files) {
            String name = file.getName();
            if (name.equals("000.html")) {
                addErrorPage(GLOBAL_ERROR_PAGE, '/' + RESOURCES + "/000.html");
            } else if (name.equals("4xx.html")) {
                addErrorPage(400, 499, '/' + RESOURCES + "/4xx.html");
            } else if (name.equals("5xx.html")) {
                addErrorPage(500, 599, '/' + RESOURCES + "/5xx.html");
            } else if (name.length() == 8 && (name.startsWith("4") || name.startsWith("5"))) {
                int code;
                try {
                    code = Integer.parseInt(name.substring(0, 3));
                } catch (NumberFormatException nfe) {
                    continue;
                }
                addErrorPage(code, '/' + RESOURCES + '/' + name);
            }
        }
    }

    // TODO Overrides

}
