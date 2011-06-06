package net.i2p.android.router.activity;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

public abstract class I2PActivityBase extends Activity {
    protected String _myDir;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        _myDir = getFilesDir().getAbsolutePath();
    }

    @Override
    public void onRestart()
    {
        System.err.println(this + " onRestart called");
        super.onRestart();
    }

    @Override
    public void onStart()
    {
        System.err.println(this + " onStart called");
        super.onStart();
        Intent intent = new Intent();
        intent.setClassName(this, "net.i2p.android.router.service.RouterService");
        System.err.println(this + " calling startService");
        ComponentName name = startService(intent);
        System.err.println(this + " got from startService: " + name);
    }

    @Override
    public void onResume()
    {
        System.err.println(this + " onResume called");
        super.onResume();
    }

    @Override
    public void onPause()
    {
        System.err.println(this + " onPause called");
        super.onPause();
    }

    @Override
    public void onStop()
    {
        System.err.println(this + " onStop called");
        super.onStop();
    }

    @Override
    public void onDestroy()
    {
        System.err.println(this + "onDestroy called");
        super.onDestroy();
    }
}
