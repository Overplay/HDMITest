package io.ourglass.hdmitest.Views;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

import io.ourglass.hdmitest.HDMIStateException;
import io.ourglass.hdmitest.R;
import io.ourglass.hdmitest.RtkHdmiWrapper;

public class HDMIView2 extends RelativeLayout {

    private static final String TAG = "HDMIView2";

    private Context mContext;

    private LayoutInflater mInflater;

    public RtkHdmiWrapper rtkHdmiWrapper;
    private boolean hdmiConnectedState = false;

    RelativeLayout mHdmiHolder, mDebugHolder;
    TextView mHdmiErrorTextView, mDebugStateTV, mDebugErrorTV, mDebugMsgTV;

    // Surface View stuff
    private ViewGroup mSurfaceHolderView;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
    private boolean mHDMISurfaceReady = false;

    ArrayList<String> errorMessages, stateMessages, fyiMessages;

    HDMIViewListener mListener;

    public boolean hdmiSurfaceReady = false;
    public boolean hdmiDriverReady = false;
    public boolean hdmiPHYConnected = false;

    public boolean enableAutoManageMode = true;
    public boolean mDebugMode = false;
    public boolean enableStreamingAudio = false;

    public boolean hasIssuedPlayToDriver = false; // issuing 2 causes lockup right now!

    public enum VideoState {
    }

    private Handler mHandler = new Handler();

    public interface HDMIViewListener {
        public void surfaceReady();
        public void ready();
        public void error(RtkHdmiWrapper.OGHdmiError error);
    }

    private RtkHdmiWrapper.RtkWrapperListener mRtkWrapperListener = new RtkHdmiWrapper.RtkWrapperListener() {
        @Override
        public void error(RtkHdmiWrapper.OGHdmiError error, String msg) {

            Log.e(TAG, "HDMI error: " + error.name());
            Log.e(TAG, "HDMI error msg: " + msg);
            if (mDebugMode) {
                addDebugErrorMessage(error.name() + "\n" + msg);
            }

            switch (error) {

                case HDMI_CANT_OPEN_DRIVER:
                    // this is a fucking, we're done
                    mHdmiHolder.setVisibility(View.INVISIBLE);
                    mHdmiErrorTextView.setText("CAN'T ACQUIRE DRIVER");
                    break;

                case FYI:
                    Log.d(TAG, "FYI error: " + msg);
                    break;

                default:
                    mHdmiErrorTextView.setText(error.name());
            }

            //mHdmiHolder.setVisibility(View.INVISIBLE);
            mHdmiErrorTextView.setVisibility(View.VISIBLE);
        }

        @Override
        public void hdmiStateChange(RtkHdmiWrapper.OGHdmiState state) {

            Log.d(TAG, "HDMI state change: " + state.name());
            if (mDebugMode) {
                addDebugStateMessage(state.name());
            }

            switch (state) {

                case HDMI_DRIVER_READY:
                    hdmiDriverReady = true;
                    if (enableAutoManageMode){
                        rtkHdmiWrapper.playHDMI();
                    }
                    break;


                case HDMI_PHY_CONNECTED:
                    hdmiPHYConnected = true;
                    if (enableAutoManageMode){
                        rtkHdmiWrapper.initHDMIDriver();
                    }
                    break;

                case HDMI_PHY_NOT_CONNECTED:
                    hdmiPHYConnected = false;
                    if (enableAutoManageMode){
                        startTeardownTimer(1000);
                    }
                    break;

                default:
                    Log.d(TAG, "State ignored");
                    break;
            }

        }

        @Override
        public void fyi(String msg) {
            if (mDebugMode) {
                addDebugMessage(msg + "\n");
            }

        }
    };

    public HDMIView2(Context context) {
        super(context);
        init(context);
    }

    public HDMIView2(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public HDMIView2(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    @TargetApi(21)
    public HDMIView2(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    /**
     * PRIMARY CONTROL METHODS
     */



    /**
     * Turn on/off debug overlays
     *
     * @param debugModeOn
     */
    public void setmDebugMode(boolean debugModeOn) {
        mDebugMode = debugModeOn;
        updateDebugViews();
    }

    public boolean getmDebugMode(){
        return mDebugMode;
    }


    public void updateDebugViews() {

        // Calls can come from bg thread, so promote to UI
        mDebugHolder.post(new Runnable() {
            @Override
            public void run() {

                int vstate = mDebugMode ? View.VISIBLE : View.GONE;
                mDebugHolder.setVisibility(vstate);

                if (mDebugMode) {

                    String sep = "\n-------\n";

                    String errMsgs = "";
                    for (int j = errorMessages.size() - 1; j >= 0; j--) {
                        errMsgs += errorMessages.get(j) + sep;
                    }

                    String stateMsgs = "";
                    for (int j = stateMessages.size() - 1; j >= 0; j--) {
                        stateMsgs += stateMessages.get(j) + sep;
                    }

                    String fyiMsgs = "";
                    for (int j = fyiMessages.size() - 1; j >= 0; j--) {
                        fyiMsgs += fyiMessages.get(j) + sep;
                    }

                    mDebugErrorTV.setText(errMsgs);
                    mDebugStateTV.setText(stateMsgs);
                    mDebugMsgTV.setText(fyiMsgs);
                }

            }
        });

    }


    public void addDebugStateMessage(String msg) {
        stateMessages.add(msg);
        updateDebugViews();
    }

    public void addDebugErrorMessage(String msg) {
        errorMessages.add(msg);
        updateDebugViews();
    }

    public void addDebugMessage(String msg) {
        fyiMessages.add(msg);
        updateDebugViews();
    }


    /**
     * Init does NOT prepare to play HDMI. You must call one of the prepare methods.
     * @param context
     */
    public void init(Context context) {

        mContext = context;

        errorMessages = new ArrayList();
        stateMessages = new ArrayList();
        fyiMessages = new ArrayList();

        mInflater = LayoutInflater.from(context);
        View v = mInflater.inflate(R.layout.hdmi_view_plus, this, true);

        mHdmiHolder = (RelativeLayout) v.findViewById(R.id.home_ac_hdmi_textureView);
        mHdmiErrorTextView = (TextView) v.findViewById(R.id.home_ac_hdmi_nosignal_text_view);

        mDebugErrorTV = (TextView) v.findViewById(R.id.textViewErr);
        mDebugErrorTV.setText("");
        mDebugStateTV = (TextView) v.findViewById(R.id.textViewState);
        mDebugStateTV.setText("");
        mDebugMsgTV = (TextView) v.findViewById(R.id.textViewMsg);
        mDebugMsgTV.setText("");

        mDebugHolder = (RelativeLayout) v.findViewById(R.id.debugViewHolder);
        updateDebugViews();

    }

    /**
     * Convenience method that starts the Surface and inits the driver
     * @param listener
     */
    public void prepareManual(HDMIViewListener listener)  throws HDMIStateException {
        enableAutoManageMode = false;
        prepare(listener, false);
    }

    public void prepareAuto(HDMIViewListener listener)  throws HDMIStateException {
        enableAutoManageMode = true;
        prepare(listener, true);
    }


    private synchronized void prepare(HDMIViewListener listener, final boolean andInitDriverWrapper) throws HDMIStateException {

        if (mSurfaceView != null)
            throw new HDMIStateException("Prepare already called!");

        mListener = listener;

        addDebugMessage("Prepare called with initDriverWrapper = " + andInitDriverWrapper );

        mSurfaceView = new SurfaceView(mContext);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(new SurfaceHolder.Callback() {

            @Override
            public void surfaceChanged(SurfaceHolder arg0, int arg1, int width, int height) {
                Log.v(TAG, "Surface changed");
                // Call myself
                mRtkWrapperListener.fyi("Surface Changed");
            }

            @Override
            public void surfaceCreated(SurfaceHolder arg0) {
                Log.v(TAG, "SurfaceCreated");
                mHDMISurfaceReady = true;
                mRtkWrapperListener.fyi("Surface Created");
                // We *want* to crash if no listener passed, so no null check
                mListener.surfaceReady();

                if (andInitDriverWrapper){
                    createDriver(); // we're going to init when we get an attach message
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder arg0) {
                Log.v(TAG, "SurfaceDestroyed");
                mHDMISurfaceReady = false;
                mRtkWrapperListener.fyi("Surface Destroyed");
            }

        });

        LayoutParams param = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        mSurfaceView.setLayoutParams(param);
        mHdmiHolder.addView(mSurfaceView);

    }

    // All this does is instantiate the driver wrapper, which only attempts to init the driver and
    // fires up the PHY Broadcast rx. Later it will init audio.
    public void createDriver() {
        rtkHdmiWrapper = new RtkHdmiWrapper(mContext, mSurfaceHolder, mRtkWrapperListener, mDebugMode);
    }


    private void initDriverUnsafe(){

    }

    // Automanage Methods
    private void startTeardownTimer(int ms){
        addDebugMessage("Starting Teardown Timer");
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                addDebugMessage("Auto Teardown");
                release();
            }
        }, ms);
    }


    // LOW LEVEL METHODS. MEANT for DEBUG PURPOSES


    // Initting the driver doesn't start any playback.
    public void initRtkDriver() throws HDMIStateException {
        if (rtkHdmiWrapper != null) {
            rtkHdmiWrapper.initHDMIDriver();
        } else {
            addDebugErrorMessage("Can't init driver before it exists!");
            Toast.makeText(mContext, "No Wrapper Yet, Homeboy", Toast.LENGTH_SHORT).show();
            throw new HDMIStateException("Can't init driver on a null wrapper.");
        }
    }

    public void play() throws HDMIStateException {
        if (rtkHdmiWrapper != null) {
            hasIssuedPlayToDriver = true;
            rtkHdmiWrapper.playHDMI();
        } else {
            addDebugErrorMessage("Can't play driver before it exists!");
            Toast.makeText(mContext, "No Wrapper Yet, Homeboy", Toast.LENGTH_SHORT).show();
            throw new HDMIStateException("Can't play driver on a null wrapper.");
        }
    }

    public void pause()  throws HDMIStateException {
        if (rtkHdmiWrapper != null) {
            rtkHdmiWrapper.pauseHDMI();
        } else {
            addDebugErrorMessage("Can't pause driver before it exists!");
            Toast.makeText(mContext, "No Wrapper Yet, Homeboy", Toast.LENGTH_SHORT).show();
            throw new HDMIStateException("Can't pause driver on a null wrapper.");
        }
    }


    /**
     * Stops the playback, nulls driver. Only safe way to stop playback.
     */
    public void release() {
        if (rtkHdmiWrapper != null) {
            rtkHdmiWrapper.releaseHDMI();
        }

    }

    public void streamAudio() {
        rtkHdmiWrapper.startStreamer();
    }

}
