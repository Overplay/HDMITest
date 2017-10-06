package io.ourglass.hdmitest;

import android.app.Activity;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/**
 * Created by mkahn on 5/22/17.
 */

public class BaseFullscreenActivity extends Activity {

    public static final String TAG = "BaseFullscreenActivity";

//    @Override
//    protected void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//
//        goFullScreen();
//
//    }

    @Override
    public void onResume(){
        super.onResume();
        goFullScreen();

    }

    @Override
    public void onBackPressed(){

       Log.d(TAG, "Back pressed");

    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            goFullScreen();
        }
    }

    public void goFullScreen() {

        if (Build.VERSION.SDK_INT < 16)//before Jelly Bean Versions
        {
            Log.d(TAG, "Hiding status bar for < SDK 16");
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            View decorView = getWindow().getDecorView();
            // Hide the status bar.
            int ui = View.SYSTEM_UI_FLAG_FULLSCREEN;
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // hide nav bar
                            | View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

    }

}

