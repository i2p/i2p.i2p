package net.i2p.router;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;

import net.i2p.router.Router;
import net.i2p.router.web.ContextHelper;
// import net.i2p.util.NativeBigInteger;

public class I2PAndroid extends Activity
{
    static Context _context;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        _context = this;  // Activity extends Context
        debugStuff();
        initialize();
        // 300ms per run
        // 5x slower than java on my server and 50x slower than native on my server
        // NativeBigInteger.main(null);
    }

    public void onRestart()
    {
        System.err.println("onRestart called");
        super.onRestart();
    }

    public void onStart()
    {
        System.err.println("onStart called");
        super.onStart();
        Router.main(null);
        System.err.println("Router.main finished");
    }

    public void onResume()
    {
        System.err.println("onResume called");
        super.onResume();
    }

    public void onPause()
    {
        System.err.println("onPause called");
        super.onPause();
    }

    public void onStop()
    {
        System.err.println("onStop called");
        super.onStop();
        // shutdown() doesn't return so use shutdownGracefully()
        ContextHelper.getContext(null).router().shutdownGracefully(Router.EXIT_HARD);
        System.err.println("shutdown complete");
    }

    public void onDestroy()
    {
        System.err.println("onDestroy called");
        super.onDestroy();
    }

    public static Context getContext() {
        return _context;
    }

    private void debugStuff() {
        System.err.println("java.vendor" + ": " + System.getProperty("java.vendor"));
        System.err.println("java.version" + ": " + System.getProperty("java.version"));
        System.err.println("os.arch" + ": " + System.getProperty("os.arch"));
        System.err.println("os.name" + ": " + System.getProperty("os.name"));
        System.err.println("os.version" + ": " + System.getProperty("os.version"));
        System.err.println("user.dir" + ": " + System.getProperty("user.dir"));
        System.err.println("user.home" + ": " + System.getProperty("user.home"));
        System.err.println("user.name" + ": " + System.getProperty("user.name"));
    }

    private void initialize() {
        // Until we can edit the router.config on the device,
        // copy it from the resource every time.
        // File f = new I2PFile("router.config");
        // if (!f.exists()) {
            copyResourceToFile(R.raw.router_config, "router.config");
            copyResourceToFile(R.raw.logger_config, "logger.config");
            copyResourceToFile(R.raw.blocklist_txt, "blocklist.txt");
        // }
    }

    private void copyResourceToFile(int resID, String f) {
        InputStream in = null;
        FileOutputStream out = null;

        System.err.println("Creating file " + f + " from resource");
        byte buf[] = new byte[4096];
        try {
            // Context methods
            in = getResources().openRawResource(resID);
            out = openFileOutput(f, 0);
            
            int read = 0;
            while ( (read = in.read(buf)) != -1)
                out.write(buf, 0, read);
            
        } catch (IOException ioe) {
        } catch (Resources.NotFoundException nfe) {
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) try { out.close(); } catch (IOException ioe) {}
        }
    }
    
}
