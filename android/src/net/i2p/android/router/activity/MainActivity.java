package net.i2p.android.router.activity;

import android.os.Bundle;

import net.i2p.android.router.R;

public class MainActivity extends I2PActivityBase {

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
    }
}
