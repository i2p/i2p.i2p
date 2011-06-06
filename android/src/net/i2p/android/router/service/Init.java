package net.i2p.android.router.service;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

import net.i2p.android.router.R;
import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterLaunch;
import net.i2p.util.OrderedProperties;
import net.i2p.util.NativeBigInteger;

class Init {

    private final Context ctx;
    private final String myDir;

    public Init(Context c) {
        ctx = c;
        myDir = c.getFilesDir().getAbsolutePath();
    }

    void debugStuff() {
        System.err.println("java.io.tmpdir" + ": " + System.getProperty("java.io.tmpdir"));
        System.err.println("java.vendor" + ": " + System.getProperty("java.vendor"));
        System.err.println("java.version" + ": " + System.getProperty("java.version"));
        System.err.println("os.arch" + ": " + System.getProperty("os.arch"));
        System.err.println("os.name" + ": " + System.getProperty("os.name"));
        System.err.println("os.version" + ": " + System.getProperty("os.version"));
        System.err.println("user.dir" + ": " + System.getProperty("user.dir"));
        System.err.println("user.home" + ": " + System.getProperty("user.home"));
        System.err.println("user.name" + ": " + System.getProperty("user.name"));
        System.err.println("getFilesDir()" + ": " + myDir);
        System.err.println("Package" + ": " + ctx.getPackageName());
        System.err.println("Version" + ": " + getOurVersion());
        System.err.println("MODEL" + ": " + Build.MODEL);
        System.err.println("DISPLAY" + ": " + Build.DISPLAY);
        System.err.println("VERSION" + ": " + Build.VERSION.RELEASE);
        System.err.println("SDK" + ": " + Build.VERSION.SDK);
    }

    private String getOurVersion() {
        PackageManager pm = ctx.getPackageManager();
        String us = ctx.getPackageName();
        try {
            PackageInfo pi = pm.getPackageInfo(us, 0);
            System.err.println("VersionCode" + ": " + pi.versionCode);
            if (pi.versionName != null)
                return pi.versionName;
        } catch (Exception e) {}
        return "??";
    }

    void initialize() {
        mergeResourceToFile(R.raw.router_config, "router.config");
        mergeResourceToFile(R.raw.logger_config, "logger.config");
        copyResourceToFile(R.raw.blocklist_txt, "blocklist.txt");

        // Set up the locations so Router and WorkingDir can find them
        System.setProperty("i2p.dir.base", myDir);
        System.setProperty("i2p.dir.config", myDir);
        System.setProperty("wrapper.logfile", myDir + "/wrapper.log");
    }

    private void copyResourceToFile(int resID, String f) {
        InputStream in = null;
        FileOutputStream out = null;

        System.err.println("Creating file " + f + " from resource");
        byte buf[] = new byte[4096];
        try {
            // Context methods
            in = ctx.getResources().openRawResource(resID);
            out = ctx.openFileOutput(f, 0);
            
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
            in = ctx.getResources().openRawResource(resID);
            DataHelper.loadProps(props,  in);
            
            try {
                fin = ctx.openFileInput(f);
                DataHelper.loadProps(props,  fin);
                System.err.println("Merging resource into file " + f);
            } catch (IOException ioe) {
                System.err.println("Creating file " + f + " from resource");
            } finally {
                if (fin != null) try { fin.close(); } catch (IOException ioe) {}
            }

            DataHelper.storeProps(props, ctx.getFileStreamPath(f));
        } catch (IOException ioe) {
        } catch (Resources.NotFoundException nfe) {
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (fin != null) try { fin.close(); } catch (IOException ioe) {}
        }
    }
    
}
