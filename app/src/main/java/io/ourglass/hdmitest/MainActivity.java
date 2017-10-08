package io.ourglass.hdmitest;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import io.ourglass.hdmitest.Views.HDMIView2;

public class MainActivity extends BaseFullscreenActivity {

    public static final String TAG = "MainActivity";
    private boolean mDebouncing = false;
    private HDMIView2 mHDMIView;

    private Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "onCreate called");

        mHDMIView = (HDMIView2)findViewById(R.id.home_hdmi_parent);
        mHDMIView.setmDebugMode(true);

    }

    @Override
    public void onStart(){
        super.onStart();
        Log.d(TAG, "onStart called");
    }

    @Override
    public void onRestart(){
        super.onRestart();
        Log.d(TAG, "onRestart called");
    }

    @Override
    public void onResume(){
        super.onResume();
        Log.d(TAG, "onResume called");
    }

    @Override
    public void onPause(){
        Log.d(TAG, "onPause called");
        super.onPause();
    }

    @Override
    public void onStop(){
        Log.d(TAG, "onStop called");
        super.onStop();
    }

    @Override
    public void onDestroy(){
        Log.d(TAG, "onDestroy called");
        super.onDestroy();
    }

    public void t(final String msg){
        // Pop to main thread just in case
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {

        Log.d(TAG, "Button with this code pressed: " + keyCode);

        // The remote control does not debounce and we can get multiple onKeyDown per click
        if (mDebouncing ) {
            return false;
        }

        mDebouncing = true;
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mDebouncing = false;
            }
        }, 1000);

        // 82 = Menu button,
        Log.d(TAG, "Button with this code being processed: " + keyCode);

        if ( keyCode == KeyEvent.KEYCODE_1 ){
            t("Starting HDMIView SurfaceView");
            hdmiPrepSurface();
        } else if ( keyCode == KeyEvent.KEYCODE_2 ) {
            t("Starting Rtk Driver");
            mHDMIView.createDriver();
        } else if ( keyCode == KeyEvent.KEYCODE_3 ){
            t("Playing Rtk Driver");
            try {
                mHDMIView.play();
            } catch (HDMIStateException e) {
                t(e.getMessage());
            }
        } else if ( keyCode == KeyEvent.KEYCODE_8 ){
            t("Pausing Rtk Driver");
            try {
                mHDMIView.pause();
            } catch (HDMIStateException e) {
                t(e.getMessage());
            }
        } else if ( keyCode == KeyEvent.KEYCODE_9 ){
            t("Rtk Driver Shutodwn");
            mHDMIView.release();
        } else if ( keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ) {

            t("Starting AutoMode");
            try {
                mHDMIView.prepareAuto(new HDMIView2.HDMIViewListener() {
                    @Override
                    public void surfaceReady() {

                    }

                    @Override
                    public void ready() {

                    }

                    @Override
                    public void error(RtkHdmiWrapper.OGHdmiError error) {

                    }
                });
            } catch (HDMIStateException e) {
                t(e.getMessage());
            }

        } else {
            t("Pressed key "+keyCode);
        }

        return false;
    }


    // Actions


    public void hdmiPrepSurface(){

        try {
            mHDMIView.prepareManual(new HDMIView2.HDMIViewListener() {
                @Override
                public void surfaceReady() {
                    Log.d(TAG, "HDMIView reports surface ready");
                    t("HDMIView reports Surface Ready");
                }

                @Override
                public void ready() {
                    Log.d(TAG, "HDMIView reports ready, starting it.");
                    t("HDMIView reports ready to rock");
                }

                @Override
                public void error(RtkHdmiWrapper.OGHdmiError error) {
                    Log.e(TAG, "Error initting HDMIView");
                    t("HDMIView reports ERROR in init! " + error.name());

                }

            });
        } catch (HDMIStateException e) {
            t(e.getMessage());
        }
    }

}