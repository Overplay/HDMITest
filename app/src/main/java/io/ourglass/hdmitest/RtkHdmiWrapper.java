package io.ourglass.hdmitest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.widget.RelativeLayout.LayoutParams;

import com.realtek.hardware.RtkHDMIRxManager;
import com.realtek.server.HDMIRxParameters;
import com.realtek.server.HDMIRxStatus;

import java.io.IOException;
import java.util.List;

import static io.ourglass.hdmitest.RtkHdmiWrapper.OGHdmiError.FYI;
import static io.ourglass.hdmitest.RtkHdmiWrapper.OGHdmiError.HDMI_DRIVER_ALREADY_OPEN;
import static io.ourglass.hdmitest.RtkHdmiWrapper.OGHdmiState.HDMI_DRIVER_READY;
import static io.ourglass.hdmitest.RtkHdmiWrapper.OGHdmiState.HDMI_PAUSED;
import static io.ourglass.hdmitest.RtkHdmiWrapper.OGHdmiState.HDMI_PHY_CONNECTED;
import static io.ourglass.hdmitest.RtkHdmiWrapper.OGHdmiState.HDMI_PHY_NOT_CONNECTED;
import static io.ourglass.hdmitest.RtkHdmiWrapper.OGHdmiState.HDMI_PLAY;
import static io.ourglass.hdmitest.RtkHdmiWrapper.OGHdmiState.HDMI_STOP_AND_RELEASE;


public class RtkHdmiWrapper {

    private static final String TAG = "RtkHdmiWrapper";

    // Used by the BG Handler for pressing playHDMI/pause on RtkMgr
    private static final int PLAY_MSG = 1;
    private static final int STOP_MSG = 2;
    private static final int SHUTDOWN_MSG = 3;


    public enum OGHdmiState {
        HDMI_PHY_CONNECTED,
        HDMI_PHY_NOT_CONNECTED,
        HDMI_DRIVER_READY,
        HDMI_UNAVAILABLE,
        HDMI_PLAY,
        HDMI_STOP_AND_RELEASE,
        HDMI_PAUSED,
        SURFACE_READY,
        SURFACE_DESTROYED,
        SURFACE_CHANGED
    }

    public enum OGHdmiError {
        NULL_SURFACE_HOLDER,
        SURFACE_NOT_READY,
        HDMI_UNAVAILABLE,
        HDMI_UNRECOVERABLE,
        HDMI_CANT_OPEN_DRIVER, // this is fatal, means someone else has driver
        HDMI_DRIVER_ALREADY_OPEN,
        FYI
    }

    public interface RtkWrapperListener {
        void error(OGHdmiError error, String msg);
        void hdmiStateChange(OGHdmiState state);
        void fyi(String msg);
    }

    private RtkWrapperListener mListener;

    private Context mContext;
    public boolean debugMode = false;
    // Coughs out more messages on HDMIView
    public boolean verboseDebug = false;


    // interface to the SurfaceView
    private SurfaceHolder mSurfaceHolder;

    // Interface to the really shitty underlying driver
    private RtkHDMIRxManager mHDMIRX;

    private Handler mHandler;
    private HandlerThread mHandlerThread;

    public boolean autoRetryOnHDMIInitFailure = true;
    public boolean autoShutdownOnError = false;
    public boolean driverReady = false;

    private BroadcastReceiver mHdmiRxHotPlugReceiver;
    private boolean hdmiConnectedState = false;

    private boolean mHDMISurfaceReady = false;


    private final static int DISPLAY = 0;
    private final static int DISPLAYTIME = 200;
    private int mFps = 0;
    private int mWidth = 0;
    private int mHeight = 0;

    // This variable is actually disconnected from reality. It is what the code *thinks* is happening
    // with no feedback from H/W
    private boolean iThinkHDMIisPlaying = false;
    private ViewGroup mRootView = null;


    /**
     * RtkHdmiWrapper
     * Constructor for non-debug mode
     *
     * @param context
     * @param surfaceHolder
     * @param listener
     */
    public RtkHdmiWrapper(Context context, SurfaceHolder surfaceHolder, RtkWrapperListener listener) {
        this(context, surfaceHolder, listener, false);
    }

    /**
     * RtkHdmiWrapper
     *
     * @param context
     * @param surfaceHolder
     * @param listener
     * @param debugMode
     */
    public RtkHdmiWrapper(Context context, SurfaceHolder surfaceHolder, RtkWrapperListener listener, boolean debugMode){

        this.mContext = context;
        this.mSurfaceHolder = surfaceHolder;
        this.mListener = listener;
        this.debugMode = debugMode;

        // We've learned playback methods need to be called from a background thread, or at the minimum
        // a handler on the same thread (this part makes no sense). So this handler is for that.
        mHandlerThread = new HandlerThread("PlaybackControlThread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper()){
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case PLAY_MSG: {
                       playAction();
                        break;
                    }
                    case STOP_MSG:
                        stopAction();
                        break;

                    default:
                        break;
                }
            }
        };

        registerBroadcastRx();
        initStreamer();

    }

    // This won't do dick-all for force-kills, I think.
    private void setupCrashHandler(){
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread paramThread, Throwable paramThrowable) {
                Log.e(TAG,"Uncaught excpetion, releasing HDMI.");
                orderlyShutdownDriver();
            }
        });
    }

    // should only be called from sep thread, via Handler
    private void playAction(){
        mHDMIRX.play();
        iThinkHDMIisPlaying = true;
        //mHDMIRX.setPlayback(true, true);
        Log.d(TAG, "hdmi mIsPlaying successfully, I hope");
    }

    // should only be called from sep thread, via Handler
    private void stopAction(){
        //mHDMIRX.setPlayback(false, false);
        int stopResult = mHDMIRX.stop();
        iThinkHDMIisPlaying = false;
        Log.d(TAG, "pause() result of driver stop was (0=good) " + stopResult);
    }

    private void shutdownAction(){

        stopAction();

    }

    private void registerBroadcastRx() {

        mHdmiRxHotPlugReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                hdmiConnectedState = intent.getBooleanExtra(HDMIRxStatus.EXTRA_HDMIRX_PLUGGED_STATE, false);
                Log.v(TAG, "HDMI Connected state received is " + hdmiConnectedState);
                OGHdmiState cstate = hdmiConnectedState ? HDMI_PHY_CONNECTED : HDMI_PHY_NOT_CONNECTED;
                stateCallback(cstate);
            }
        };

        IntentFilter hdmiRxFilter = new IntentFilter(HDMIRxStatus.ACTION_HDMIRX_PLUGGED);

        // Read it once.
        Intent pluggedStatus = mContext.registerReceiver(null, hdmiRxFilter);
        // this could be invalid...
        hdmiConnectedState = pluggedStatus.getBooleanExtra(HDMIRxStatus.EXTRA_HDMIRX_PLUGGED_STATE, false);
        Log.v(TAG, "registerBroadcastRx Connected state read directly is " + hdmiConnectedState);

        // Watch it.
        mContext.registerReceiver(mHdmiRxHotPlugReceiver, hdmiRxFilter);

    }

    public void unregisterBroadcastRx(){
        if (mHdmiRxHotPlugReceiver != null) {
            mContext.unregisterReceiver(mHdmiRxHotPlugReceiver);
            mHdmiRxHotPlugReceiver = null;
        }
    }

    public boolean areHdmiPHYAndDriverConnected(){

        if (mHDMIRX!=null){
            return mHDMIRX.isHDMIRxPlugged();
        } else {
            return false;
        }
    }


    /**
     * Calls back state change to listener if one is registered.
     * @param state
     */
    private void stateCallback(OGHdmiState state) {
        Log.d(TAG, "HDMI stateCallback: " + state.name());
        if (mListener != null) {
            mListener.hdmiStateChange(state);
        }
    }

    /**
     * Calls backerror to listener if one is registered.
     * @param error
     * @param msg
     */
    private void errorCallback(OGHdmiError error, String msg) {
        if (mListener != null) {
            mListener.error(error, msg);
        }
    }

    /**
     * Calls back fyi to listener if one is registered.
     * @param msg
     */
    private void fyiCallback( String msg ) {
        if (mListener != null) {
            mListener.fyi(msg);
        }
    }

    // Probably only ever use this during dev
    private void sendFYIStatus(HDMIRxStatus rxStatus){
        if (debugMode && verboseDebug){
            StringBuilder sb = new StringBuilder("RxManager Status:\n");
            sb.append("type = "+rxStatus.type + "\n");
            sb.append("status = "+rxStatus.status + "\n");
            sb.append("width = "+rxStatus.width + "\n");
            sb.append("height = "+rxStatus.height + "\n");
            sb.append("scanMode = "+rxStatus.scanMode + "\n");
            sb.append("color = "+rxStatus.color + "\n");
            sb.append("freq = "+rxStatus.freq + "\n");
            sb.append("spdif = "+rxStatus.spdif + "\n");
            sb.append("audioStatus = "+rxStatus.audioStatus + "\n");
            fyiCallback(sb.toString());
        }
    }



    public void initHDMIDriver() {

        if (mHDMIRX == null) {
            Log.d(TAG, "initHDMIDriver called and there is no HDMIRxManager (null), creating");
            Log.d(TAG, "...this is normal, relax.");

            fyiCallback("Creating new RxManager instance");

            driverReady = false;
            mWidth = 0;
            mHeight = 0;

            mHDMIRX = new RtkHDMIRxManager();

            HDMIRxStatus rxStatus = mHDMIRX.getHDMIRxStatus();

            if (rxStatus == null) {

                Log.wtf(TAG, "initHDMIDriver: rxStatus from chipset is NULL. This is a hard fucking fail!");
                errorCallback(OGHdmiError.HDMI_UNAVAILABLE, "NULL status returned. This blows hard.");
                // Just in case, orderly shutdown
                //cleanUpBadManager();
                orderlyShutdownDriver();

            } else if (rxStatus.status == HDMIRxStatus.STATUS_READY) {

                Log.v(TAG, "initHDMIDriver: HDMI status is STATUS_READY, trying open driver.");
                sendFYIStatus(rxStatus);

                // This registers package name with underlying shitty C code
                //if (mHDMIRX.open() != 0) {

                int openStatus = mHDMIRX.open();
                if (openStatus != 0) {
                    // Could not open the driver, so we are probably really fucked

                    // SUPER FUCKING IMPORTANT!!!
                    // Your entire view hierarchy is now POISONED. If you touch anything, the underlying driver will crash!!!
                    // Well, it looks like this is another inconsistency. Can't replicate this now.

                    Log.d(TAG, "initHDMIDriver: Could not open driver (" + openStatus + "). Probably we are fucked. Attempting releaseHDMI(), better close your eyes.");
                    //cleanUpBadManager(); // This was MAK's releaser
                    errorCallback(OGHdmiError.HDMI_CANT_OPEN_DRIVER, "Could not open the driver.");
                    orderlyShutdownDriver();

                } else {

                    // So good so far
                    Log.d(TAG, "initHDMIDriver: successfully opened the driver. So we got that going for us.");
                    HDMIRxParameters hdmirxGetParam = mHDMIRX.getParameters();
                    Log.v(TAG, "initHDMIDriver: Params from driver: " + hdmirxGetParam.flatten());
                    sendFYIStatus(rxStatus);

                    getSupportedPreviewSize(hdmirxGetParam, rxStatus.width, rxStatus.height);
                    getSupportedPreviewFrameRate(hdmirxGetParam);

                    try {
                        mHDMIRX.setPreviewDisplay(mSurfaceHolder);
                        // configureTargetFormat
                        HDMIRxParameters hdmirxParam = new HDMIRxParameters();
                        Log.v(TAG, "initHDMIDriver: hdmi setPreviewSize  mWidth = " + mWidth + "  mHeight = " + mHeight + "  mFps = " + mFps);
                        hdmirxParam.setPreviewSize(mWidth, mHeight);
                        hdmirxParam.setPreviewFrameRate(mFps);
                        mHDMIRX.setParameters(hdmirxParam);
                        mHDMIRX.setPlayback(true, true);
                        driverReady = true;
                        stateCallback(HDMI_DRIVER_READY);

                    } catch (IOException e) {
                        Log.e(TAG, "initHDMIDriver: Exception setting preview display", e);
                        errorCallback(OGHdmiError.HDMI_UNAVAILABLE, "Could not set preview display. Really bad.");
                        orderlyShutdownDriver();
                        e.printStackTrace();
                    }
                }

            } else {
                sendFYIStatus(rxStatus);
                Log.d(TAG, "initHDMIDriver:  got an non-ready status from the driver. Fuck.");
                Log.d(TAG, "initHDMIDriver:  Status ( 0 = not ready ): " + rxStatus.status);
                errorCallback(OGHdmiError.HDMI_UNAVAILABLE, "Got non-ready status from driver. Unplugged?");
                //cleanUpBadManager();
                orderlyShutdownDriver();
            }


        } else {
            Log.d(TAG, "initHDMIDriver called and there already is a manager. Think about your choices.");
            errorCallback(HDMI_DRIVER_ALREADY_OPEN, "");
        }

    }


    //Scott's method
    public void pauseHDMI() {

        Log.v(TAG, "pauseHDMI() called.");
        fyiCallback("pauseHDMI() called");

        if (mHDMIRX != null) {

            if (driverReady) {
                mHandler.sendEmptyMessage(STOP_MSG);
                stopStreamer();
            }

            stateCallback(HDMI_PAUSED);
        }
        //(new SystemStatusMessage(SystemStatusMessage.SystemStatus.HDMI_PAUSE)).post();

    }

    /**
     *
     * SHUTDOWN ORDER:
     * 1. Remove the preview display
     * 2. .release() the manager
     * 3. null the driver
     */

    public void releaseHDMI() {
        Log.d(TAG, "releaseHDMI() called.");
        fyiCallback("releaseHDMI() called");
        if (mHDMIRX != null ) {

            mHDMIRX.stop();

            try {
                mHDMIRX.setPreviewDisplay(null);
            } catch (IOException e) {
                Log.wtf(TAG,"Exception thrown releasing preview display.");
            }

            mHDMIRX.release();
            mHDMIRX = null;
            driverReady = false;
            Log.d(TAG, "releaseHDMI() complete");
            fyiCallback("driver nulled");
            stateCallback(HDMI_STOP_AND_RELEASE);


        }
    }

    // Scott's method...more tested
    private void orderlyShutdownDriver() {

        Log.d(TAG, "orderlyShutdownDriver shutdown called");
        fyiCallback("Orderly shutdown requested");

        if (!autoShutdownOnError) {
            errorCallback(FYI, "Shutdown called, but auto shutdown is disbaled, ignoring!");
            return;
        }

        if (mHDMIRX != null ) {
            Log.d(TAG, "orderlyShutdownDriver calling stop() on driver..");
            int stopResult = mHDMIRX.stop();
            Log.d(TAG, "orderlyShutdownDriver result of driver stop was (0=good) " + stopResult);
            releaseHDMI();
            Log.d(TAG, "orderlyShutdownDriver complete");
            fyiCallback("orderly shutdown complete");

        } else {
            Log.d(TAG, "orderlyShutdownDriver called on null driver");
        }

    }


    public void playHDMI() {

        Log.d(TAG, "playHDMI() called.");
        fyiCallback("playHDMI()");

        if (driverReady) {
            mHandler.sendEmptyMessage(PLAY_MSG);
            stateCallback(HDMI_PLAY);
        } else {
            Log.d(TAG, "playHDMI() called and driver not ready, piss off.");
            fyiCallback("Play called on a non-ready driver. Ignored.");
        }

    }


    private void getSupportedPreviewFrameRate(HDMIRxParameters hdmirxGetParam) {
        List<Integer> previewFrameRates = hdmirxGetParam.getSupportedPreviewFrameRates();
        int fps = 0;
        if (previewFrameRates != null && previewFrameRates.size() > 0)
            fps = previewFrameRates.get(previewFrameRates.size() - 1);
        else
            fps = 30;
        mFps = fps;
    }

    private void getSupportedPreviewSize(HDMIRxParameters hdmirxGetParam, int rxWidth, int rxHeight) {
        List<RtkHDMIRxManager.Size> previewSizes = hdmirxGetParam.getSupportedPreviewSizes();
        int retWidth = 0, retHeight = 0;
        if (previewSizes == null || previewSizes.size() <= 0)
            return;
        for (int i = 0; i < previewSizes.size(); i++) {
            if (previewSizes.get(i) != null && rxWidth == previewSizes.get(i).width) {
                retWidth = previewSizes.get(i).width;
                retHeight = previewSizes.get(i).height;
                if (rxHeight == previewSizes.get(i).height)
                    break;
            }
        }
        if (retWidth == 0 && retHeight == 0) {
            if (previewSizes.get(previewSizes.size() - 1) != null) {
                retWidth = previewSizes.get(previewSizes.size() - 1).width;
                retHeight = previewSizes.get(previewSizes.size() - 1).height;
            }
        }

        mWidth = retWidth;
        mHeight = retHeight;
    }


    // "SAFE" METHODS. Nothing in here needs reverse engineering


    // Scott's audio Stuff, Commented Out for Now

    private ParcelFileDescriptor[] ffPipe = null;
    //private AudioStreamer mAudioStreamer = null;
    private boolean isStreaming = false;

    public boolean isStreaming() {
        return isStreaming;
    }

    private void initStreamer() {
//        mAudioStreamer = new AudioStreamer(mContext, new AudioStreamer.StreamDeadListener() {
//            @Override
//            public void streamDead(Context lContext) {
//                Log.v(TAG, "streamDead");
//
//                ((Activity) lContext).runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//
//                        if ((ffPipe != null) && (ffPipe.length == 2)) {
//                            try {
//                                ffPipe[1].closeWithError("Die Milk Face");
//                                ffPipe[0].checkError();
//                                Log.v(TAG, "all good");
//                            } catch (IOException e) {
//                                Log.e(TAG, "Exception killing", e);
//                                ;
//                            }
//                        }
//
//                        if (isStreaming()) {
//                            stopStreamer();
//                        }
//
//                        OGLogMessage.newOGLog("streaming_failed")
//                                .addFieldToMessage("description", "not sure the reason")
//                                .addFieldToMessage("exception", "pipe or process exited")
//                                .addFieldToMessage("issue_code", 8675309)
//                                .post();
//
//                        //SystemStatusMessage.sendStatusMessageWithException(AS_LOS, result);
//                        SystemStatusMessage.sendStatusMessage(AS_LOS);
//
//                    }
//                });
//            }
//        });
    }

    public void stopStreamer() {

        if (isStreaming){
            try {
                isStreaming = false;
                if (mHDMIRX != null) {
                    mHDMIRX.setTranscode(false);
                }
//            if (mAudioStreamer != null) {
//                mAudioStreamer.killStream();
//            }

                //Toast.makeText(mContext, "Stop streamer successful!", Toast.LENGTH_SHORT).show();
                //ABApplication.dbToast("Stop streamer successful!");

            } catch (Exception e) {
                Log.e(TAG, "Exception mHDMIRX.setTranscode2", e);
                ;
            }

        }

    }

    public void startStreamer() {

            if (isStreaming) {
                fyiCallback("Start streaming called, but already streaming.");
                return;
            }

//        int videoBitrate = OGConstants.BUCANERO_AV_V_BITRATE;
//        int channelCount = OGConstants.BUCANERO_AV_A_CHANNELS;
//        int sampleRate = OGConstants.BUCANERO_AV_A_SAMPLERATE;
//        int audioBitrate = OGConstants.BUCANERO_AV_A_BITRATE;
//        int w = mWidth;
//        int h = mHeight;
//        if ((w * h) > OGConstants.BUCANERO_AV_V_MAXWIDTH * OGConstants.BUCANERO_AV_V_MAXHEIGHT) {
//            w = OGConstants.BUCANERO_AV_V_MAXWIDTH;
//            h = OGConstants.BUCANERO_AV_V_MAXHEIGHT;
//        }
//
//        if (!iThinkHDMIisPlaying) {
//            return;
//        }
//
//        try {
//            ffPipe = ParcelFileDescriptor.createReliablePipe();
//            if (!mAudioStreamer.runStream(ffPipe[0])) {
//                return;
//            }
//            /* For kicks I tried to make vConfig (1,1,10) but that breaks the screen/view */
//            RtkHDMIRxManager.VideoConfig vConfig = new RtkHDMIRxManager.VideoConfig(w, h, videoBitrate);
//            RtkHDMIRxManager.AudioConfig aConfig = new RtkHDMIRxManager.AudioConfig(channelCount, sampleRate, audioBitrate);
//
//            mHDMIRX.configureTargetFormat(vConfig, aConfig);
//            mHDMIRX.setTargetFd(ffPipe[1], RtkHDMIRxManager.HDMIRX_FILE_FORMAT_TS);
//            mHDMIRX.setTranscode(true);
//            isStreaming = true;
//            //Toast.makeText(mContext, "Start streamer successful ...", Toast.LENGTH_SHORT).show();
//            ABApplication.dbToast("Start streamer successful!");
//        } catch (IOException e) {
//            Log.e(TAG, "Exception creating ffPipe", e);
//            return;
//        }
//
//        try {
//        } catch (Exception e) {
//            Log.e(TAG, "Exception streaming", e);
//            ;
//        }
    }


    // Garbage Area

    // Not sure what this ever did
    @Deprecated
    public void setSize(boolean isFull) {
        if (isFull) {
            LayoutParams param = (LayoutParams) mRootView.getLayoutParams();
            param.width = ViewGroup.LayoutParams.MATCH_PARENT;
            param.height = ViewGroup.LayoutParams.MATCH_PARENT;
            mRootView.setLayoutParams(param);
        } else {
            LayoutParams param = (LayoutParams) mRootView.getLayoutParams();
            param.width = (int) (640 * 1.5f);
            param.height = (int) (420 * 1.5f);
            mRootView.setLayoutParams(param);
        }
    }




}
