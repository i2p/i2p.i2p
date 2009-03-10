package net.i2p.router;

import android.app.Activity;
import android.os.Bundle;

import net.i2p.router.Router;

public class I2PAndroid extends Activity
{
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        Router.main(null);
    }
}
