package net.i2p.router;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.os.Build;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.RouterLaunch;
import net.i2p.util.OrderedProperties;
import net.i2p.util.NativeBigInteger;

public class I2PAndroid extends Activity
{
    static Context _context;
    private String _myDir;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        _context = this;  // Activity extends Context
        _myDir = getFilesDir().getAbsolutePath();
        debugStuff();
        initialize();
        // 300ms per run on emulator on eeepc
        // 5x slower than java on my server and 50x slower than native on my server
        // 33 ms native 29 ms java moto droid 2.2.2
        NativeBigInteger.main(null);
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
//      net.i2p.crypto.DSAEngine.main(null);
        RouterLaunch.main(null);
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

        // from routerconsole ContextHelper
        List contexts = RouterContext.listContexts();
        if ( (contexts == null) || (contexts.isEmpty()) ) 
            throw new IllegalStateException("No contexts. This is usually because the router is either starting up or shutting down.");
        RouterContext ctx = (RouterContext)contexts.get(0);

        // shutdown() doesn't return so use shutdownGracefully()
        ctx.router().shutdownGracefully(Router.EXIT_HARD);
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
        System.err.println("java.io.tmpdir" + ": " + System.getProperty("java.io.tmpdir"));
        System.err.println("java.vendor" + ": " + System.getProperty("java.vendor"));
        System.err.println("java.version" + ": " + System.getProperty("java.version"));
        System.err.println("os.arch" + ": " + System.getProperty("os.arch"));
        System.err.println("os.name" + ": " + System.getProperty("os.name"));
        System.err.println("os.version" + ": " + System.getProperty("os.version"));
        System.err.println("user.dir" + ": " + System.getProperty("user.dir"));
        System.err.println("user.home" + ": " + System.getProperty("user.home"));
        System.err.println("user.name" + ": " + System.getProperty("user.name"));
        System.err.println("getFilesDir()" + ": " + _myDir);
        System.err.println("Package" + ": " + getPackageName());
        System.err.println("Version" + ": " + getOurVersion());
        System.err.println("MODEL" + ": " + Build.MODEL);
        System.err.println("DISPLAY" + ": " + Build.DISPLAY);
        System.err.println("VERSION" + ": " + Build.VERSION.RELEASE);
        System.err.println("SDK" + ": " + Build.VERSION.SDK);
    }

    private String getOurVersion() {
        PackageManager pm = getPackageManager();
        String us = getPackageName();
        try {
            PackageInfo pi = pm.getPackageInfo(us, 0);
            System.err.println("VersionCode" + ": " + pi.versionCode);
            if (pi.versionName != null)
                return pi.versionName;
        } catch (Exception e) {}
        return "??";
    }

    private void initialize() {
        mergeResourceToFile(R.raw.router_config, "router.config");
        mergeResourceToFile(R.raw.logger_config, "logger.config");
        copyResourceToFile(R.raw.blocklist_txt, "blocklist.txt");

        // Set up the locations so Router and WorkingDir can find them
        System.setProperty("i2p.dir.base", _myDir);
        System.setProperty("i2p.dir.config", _myDir);
        System.setProperty("wrapper.logfile", _myDir + "/wrapper.log");
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
    
    /**
     *  Load defaults from resource,
     *  then add props from file,
     *  and write back
     */
    private void mergeResourceToFile(int resID, String f) {
        InputStream in = null;
        InputStream fin = null;

        byte buf[] = new byte[4096];
        try {
            Properties props = new OrderedProperties();
            // Context methods
            in = getResources().openRawResource(resID);
            DataHelper.loadProps(props,  in);
            
            try {
                fin = openFileInput(f);
                DataHelper.loadProps(props,  fin);
                System.err.println("Merging resource into file " + f);
            } catch (IOException ioe) {
                System.err.println("Creating file " + f + " from resource");
            } finally {
                if (fin != null) try { fin.close(); } catch (IOException ioe) {}
            }

            DataHelper.storeProps(props, getFileStreamPath(f));
        } catch (IOException ioe) {
        } catch (Resources.NotFoundException nfe) {
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (fin != null) try { fin.close(); } catch (IOException ioe) {}
        }
    }
    
}
