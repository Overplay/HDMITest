package io.ourglass.hdmitest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
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
import static io.ourglass.hdmitest.RtkHdmiWrapper.OGHdmiState.SURFACE_CHANGED;
import static io.ourglass.hdmitest.RtkHdmiWrapper.OGHdmiState.SURFACE_DESTROYED;
import static io.ourglass.hdmitest.RtkHdmiWrapper.OGHdmiState.SURFACE_READY;


public class RtkHdmiWrapper {

    private static final String TAG = "RtkHdmiWrapper";

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
        NULL_SURFACE_VIEW,
        SURFACE_NOT_READY,
        HDMI_UNAVAILABLE,
        HDMI_UNRECOVERABLE,
        HDMI_CANT_OPEN_DRIVER, // this is fatal, means someone else has driver
        HDMI_DRIVER_ALREADY_OPEN,
        FYI
    }

    public interface RtkWrapperListener {

        public void error(OGHdmiError error, String msg);

        public void hdmiStateChange(OGHdmiState state);

    }

    private RtkWrapperListener mListener;

    private Context mContext;


    // Views
    private ViewGroup mSurfaceHolderView;
    private SurfaceView mSurfaceView;
    // interface to the SurfaceView
    private SurfaceHolder mSurfaceHolder;

    // Interface to the really shitty underlying driver
    private RtkHDMIRxManager mHDMIRX;

    private Handler mHandler = new Handler();
    private Handler mHandler2;
    private HandlerThread mHandlerThread2;

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
    private ParcelFileDescriptor[] ffPipe = null;
    //private AudioStreamer mAudioStreamer = null;
    private boolean isStreaming = false;

    /**
     * RtkHdmiWrapper
     *
     * @param mContext
     * @param viewGroupToInsertSurfaceViewInto
     */
    public RtkHdmiWrapper(Context mContext, ViewGroup viewGroupToInsertSurfaceViewInto, RtkWrapperListener listener) {
        this.mContext = mContext;
        this.mSurfaceHolderView = viewGroupToInsertSurfaceViewInto;
        this.mListener = listener;
        registerBroadcastRx();
        initView();
        initStreamer();
    }

    public void delayPlayHDMI(int delayMs) {

        Log.d(TAG, "delayPlayHDMI called. Waiting: " + delayMs);
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "delayPlayHDMI executing");
                play();
            }
        }, delayMs);

    }

    private void retryInitHDMI(int delayMs) {

        Log.d(TAG, "retryInitHDMI called");
        if (autoRetryOnHDMIInitFailure) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "retryInitHDMI executing");
                    initHDMIDriver();
                }
            }, delayMs);
        } else {
            Log.d(TAG, "Autoretry disabled, so fuckoff");
        }

    }

    private void initView() {

        mSurfaceView = new SurfaceView(mContext);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(new SurfaceHolder.Callback() {

            @Override
            public void surfaceChanged(SurfaceHolder arg0, int arg1, int width, int height) {
                Log.v(TAG, "Surface changed");
                stateCallback(SURFACE_CHANGED);
            }

            @Override
            public void surfaceCreated(SurfaceHolder arg0) {
                Log.v(TAG, "SurfaceCreated");
                mHDMISurfaceReady = true;
                stateCallback(SURFACE_READY);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder arg0) {
                Log.v(TAG, "SurfaceDestroyed");
                mHDMISurfaceReady = false;
                stateCallback(SURFACE_DESTROYED);
            }

        });


        LayoutParams param = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        mSurfaceView.setLayoutParams(param);
        mSurfaceHolderView.addView(mSurfaceView);

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
        hdmiConnectedState = pluggedStatus.getBooleanExtra(HDMIRxStatus.EXTRA_HDMIRX_PLUGGED_STATE, false);
        Log.v(TAG, "registerBroadcastRx Connected state read directly is " + hdmiConnectedState);

        // Watch it.
        mContext.registerReceiver(mHdmiRxHotPlugReceiver, hdmiRxFilter);

    }


    /**
     * Calls back state change to listener if one is registered.
     *
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
     *
     * @param error
     * @param msg
     */
    private void errorCallback(OGHdmiError error, String msg) {
        if (mListener != null) {
            mListener.error(error, msg);
        }
    }

    private void orderlyShutdownDriver() {

        Log.d(TAG, "orderlyShutdownDriver shutdown called");

        if (!autoShutdownOnError) {
            errorCallback(FYI, "Shutdown called, but autoshutdown is disbaled, ignoring!");
            return;
        }

        if (mHDMIRX != null ) {
            Log.d(TAG, "orderlyShutdownDriver calling stop() on driver..");
            int stopResult = mHDMIRX.stop();
            Log.v(TAG, "orderlyShutdownDriver result of driver stop was (0=good) " + stopResult);
            Log.v(TAG, "orderlyShutdownDriver releasing native driver, he's probably shitty at it anyway.");
            mHDMIRX.release();
            mHDMIRX = null;
            driverReady = false;
            Log.d(TAG, "orderlyShutdownDriver complete");

        } else {
            Log.d(TAG, "orderlyShutdownDriver called on null driver");
        }

    }

    public void initHDMIDriver() {

        if (mHDMIRX == null) {
            Log.d(TAG, "initHDMIDriver called and there is no HDMIRxManager (null), creating");
            Log.d(TAG, "...this is normal, relax.");

            driverReady = false;
            mWidth = 0;
            mHeight = 0;

            mHDMIRX = new RtkHDMIRxManager();

            HDMIRxStatus rxStatus = mHDMIRX.getHDMIRxStatus();

            if (rxStatus == null) {

                Log.wtf(TAG, "initHDMIDriver: rxStatus from chipset is NULL. This is a hard fucking fail!");
                errorCallback(OGHdmiError.HDMI_UNAVAILABLE, "NULL status returned. This blows hard.");
                // Just in case, orderly shutdown
                orderlyShutdownDriver();

            } else if (rxStatus.status == HDMIRxStatus.STATUS_READY) {

                Log.v(TAG, "initHDMIDriver: HDMI status is STATUS_READY, trying open driver.");

                // This registers package name with underlying shitty C code
                //if (mHDMIRX.open() != 0) {

                int openStatus = mHDMIRX.open();
                if (openStatus != 0) {
                    // Could not open the driver, so we are probably really fucked

                    // SUPER FUCKING IMPORTANT!!!
                    // Your entire view hierarchy is now POISONED. If you touch anything, the underlying driver will crash!!!
                    Log.d(TAG, "initHDMIDriver: Could not open driver (" + openStatus + "). Probably we are fucked. Attempting release(), better close your eyes.");
                    // Release does nothing
                    //mHDMIRX.release();
                    errorCallback(OGHdmiError.HDMI_CANT_OPEN_DRIVER, "Could not open the driver. This blows hard.");
                    orderlyShutdownDriver();

                } else {

                    // So good so far
                    Log.d(TAG, "initHDMIDriver: successfully opened the driver. So we got that going for us.");
                    HDMIRxParameters hdmirxGetParam = mHDMIRX.getParameters();
                    Log.v(TAG, "initHDMIDriver: Params from driver: " + hdmirxGetParam.flatten());
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
                Log.d(TAG, "initHDMIDriver:  got an non-ready status from the driver. Fuck.");
                Log.d(TAG, "initHDMIDriver:  Status ( 0 = not ready ): " + rxStatus.status);
                errorCallback(OGHdmiError.HDMI_UNAVAILABLE, "Got non-ready status from driver.");
                orderlyShutdownDriver();
            }


        } else {
            Log.d(TAG, "initHDMIDriver called and there already is a manager. Think about your choices.");
            errorCallback(HDMI_DRIVER_ALREADY_OPEN, "");
        }

    }

    /**
     * Kill this shit dead. May not be resurrectable?
     */
    public void kill() {

        Log.v(TAG, "kill() called.");
        if (mSurfaceView != null) {

            Log.v(TAG, "kill() hiding Surface View");
            //mSurfaceView.setVisibility(View.INVISIBLE);
        }

        orderlyShutdownDriver();

        iThinkHDMIisPlaying = false;
        mFps = 0;
        mWidth = 0;
        mHeight = 0;

        stateCallback(HDMI_STOP_AND_RELEASE);

        if (mHdmiRxHotPlugReceiver != null) {
            mContext.unregisterReceiver(mHdmiRxHotPlugReceiver);
            mHdmiRxHotPlugReceiver = null;
        }

        //(new SystemStatusMessage(SystemStatusMessage.SystemStatus.HDMI_STOP)).post();

    }

    /**
     * Does much the same shit as kill, without releasing the driver
     */
    public void pause() {

        Log.v(TAG, "pause() called.");
        if (mSurfaceView != null) {
            Log.v(TAG, "pause() NOT hiding Surface View (commented out)");
            //mSurfaceView.setVisibility(View.INVISIBLE);
        }

        if (mHDMIRX != null) {
            Log.v(TAG, "pause() calling stop() on driver..");
            mHDMIRX.setPlayback(false, false);
            int stopResult = mHDMIRX.stop();
            Log.v(TAG, "pause() result of driver stop was (0=good) " + stopResult);
            Log.v(TAG, "pause() NOT! releasing native driver, maybe this will help.");

        }

        iThinkHDMIisPlaying = false;
//        mFps = 0;
//        mWidth = 0;
//        mHeight = 0;
        stateCallback(HDMI_PAUSED);
        //(new SystemStatusMessage(SystemStatusMessage.SystemStatus.HDMI_PAUSE)).post();

    }

    public void pauseMain() {

        Log.v(TAG, "pause() called.");
        if (mSurfaceView != null) {
            Log.v(TAG, "pause() NOT hiding Surface View (commented out)");
            //mSurfaceView.setVisibility(View.INVISIBLE);
        }

        if (mHDMIRX != null) {

            if (driverReady) {
                (new Thread(new Runnable() {
                    @Override
                    public void run() {
                        mHDMIRX.setPlayback(false, false);
                        int stopResult = mHDMIRX.stop();
                        iThinkHDMIisPlaying = false;
                        Log.v(TAG, "pause() result of driver stop was (0=good) " + stopResult);
                    }
                })).start();
            }

            stateCallback(HDMI_PAUSED);
        }
        //(new SystemStatusMessage(SystemStatusMessage.SystemStatus.HDMI_PAUSE)).post();

    }

    public void release() {
        Log.d(TAG, "release() called.");
        if (mHDMIRX != null ) {

            try {
                mHDMIRX.setPreviewDisplay(null);
            } catch (IOException e) {
            }

            mHDMIRX.release();
            mHDMIRX = null;
            driverReady = false;
            Log.d(TAG, "orderlyShutdownDriver complete");

        }
    }

    public void play() {

        Log.d(TAG, "play() called.");

        // These checks are probably overkill, but they won't hurt anything

        if (mSurfaceView == null) {
            // TODO this should throw and really not even be possible
            Log.wtf(TAG, "play() called on a null surface view, bailing");
            errorCallback(OGHdmiError.NULL_SURFACE_VIEW, "There is no surface view to play on, this is not cool yo.");
            return;
        }

        if (!mHDMISurfaceReady) {
            Log.e(TAG, "play() called and HDMI SurfaceView isn't ready yet, gonna chill a bit (1 sec) and retry.");
            errorCallback(OGHdmiError.SURFACE_NOT_READY, "Play called on a surface that is not ready");
            return;
        }

        //SJMNOLog.d(TAG, "play() making surfaceview visible");
        //SJMNOmSurfaceView.setVisibility(View.VISIBLE);

        Log.v(TAG, "play------------- What I *think* HDMI is playing = " + iThinkHDMIisPlaying + " HDMI surface ready = " + mHDMISurfaceReady);

        if (driverReady) {

//            mHandler.post(new Runnable() {
//                @Override
//                public void run() {
//                    mHDMIRX.play();
//                    iThinkHDMIisPlaying = true;
//                    mHDMIRX.setPlayback(true, true);
//                    Log.d(TAG, "hdmi mIsPlaying successfully, I hope");
//                }
//            });

            (new Thread(new Runnable() {
                @Override
                public void run() {
                    mHDMIRX.play();
                    iThinkHDMIisPlaying = true;
                    mHDMIRX.setPlayback(true, true);
                    Log.d(TAG, "hdmi mIsPlaying successfully, I hope");
                }
            })).start();

            stateCallback(HDMI_PLAY);
            //(new SystemStatusMessage(SystemStatusMessage.SystemStatus.HDMI_PLAY)).post();

        } else {
            Log.d(TAG, "play() called and driver not ready, piss off.");
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


    public boolean isStreaming() {
        return isStreaming;
    }

    public void stopStreamer() {
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

    public void startStreamer() {
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
}
