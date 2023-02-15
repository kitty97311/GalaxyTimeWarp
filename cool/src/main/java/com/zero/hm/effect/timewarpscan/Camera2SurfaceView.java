package com.zero.hm.effect.timewarpscan;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.PermissionChecker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class Camera2SurfaceView extends SurfaceView {

    private EGLUtils mEglUtils = new EGLUtils();
    private GLVideoRenderer videoRenderer = new GLVideoRenderer();
    private GLRenderer mRenderer = new GLRenderer();
    private GLScanRenderer scanRenderer = new GLScanRenderer();
    private GLLineRenderer lineRenderer = new GLLineRenderer();

    private String mCameraId;
    private CameraManager mCameraManager;
    private CameraCaptureSession mCameraCaptureSession;
    private CameraDevice mCameraDevice;
    private Handler mHandler;

    private int screenWidth = -1, screenHeight, previewWidth,previewHeight;
    private Rect rect = new Rect();

    public Handler cameraHandler;
    private HandlerThread cameraThread;

    private boolean isScan = false;
    private boolean isNewScan = false;
    private boolean isf = false;
    private float scanHeightPixel;
    private float scanHeight;
    private float pixelHeight;
    private int scanContainerHeight;
    private int scanContainerWidth;
    private int correctionDistance;
    private int speed = 8;
    private int activeCamera = 0;
    long startTime = 0;
    private boolean isHorizontal = true;

    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isScanVideo() {
        return isScan;
    }

    public void setScanVideo(boolean scan) {
        isScan = scan;
        isNewScan = scan;
    }

    public boolean isRearCameraActive(Context context) {
        return getActiveCamera(context) == 0;
    }

    private int getActiveCamera(Context context) {
        SharedPreferences sharedPref = context.getSharedPreferences("camera", Context.MODE_PRIVATE);
        return sharedPref.getInt("camera", 0);
    }

    public void setWarpMode(boolean mIsHorizontal, int mSpeed) {
        isHorizontal = mIsHorizontal;
        SharedPreferences sharedPref = getContext().getSharedPreferences("camera", Context.MODE_PRIVATE);
        sharedPref.edit().putString("warpMode", mIsHorizontal ? "horizontal" : "vertical").apply();
        isScan = false;
        speed = mSpeed;
    }

    public Camera2SurfaceView(Context context) {
        super(context);
        init(context);
    }

    public Camera2SurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(final Context context){
        cameraThread = new HandlerThread("Camera2Thread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        activeCamera = getActiveCamera(context);

        initCamera2();
        getHolder().addCallback(sfc);

    }


    public SurfaceHolder.Callback sfc = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder surfaceHolder) {
            cameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    mEglUtils.initEGL(getHolder().getSurface());
                    GLES20.glEnable(GLES20.GL_BLEND);
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                    mRenderer.initShader();
                    videoRenderer.initShader();
                    scanRenderer.initShader();
                    lineRenderer.initShader();

                    videoRenderer.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                        @Override
                        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                            cameraHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if(mCameraCaptureSession == null){
                                        return;
                                    }
                                    videoRenderer.drawFrame();
                                    int videoTexture = videoRenderer.getTexture();
                                    if(isScan){
                                        if(!isf){
//                                            galaxyMediaRecorder.start();

                                            startTime = System.currentTimeMillis();
                                            scanHeight = pixelHeight*speed;
                                            scanHeightPixel = (float) (isHorizontal ? 0 : correctionDistance);
                                        }else{
                                            isNewScan = false;
                                            scanHeight += (pixelHeight*speed);
                                            scanHeightPixel = (isHorizontal ? 0 : correctionDistance)
                                                    + scanHeight * (isHorizontal ? scanContainerWidth : scanContainerHeight);

                                            ((AppCompatActivity)getContext()).runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    listener.moveScanLine((int) scanHeightPixel, isHorizontal);
                                                }
                                            });

                                        }

                                        float fh = scanHeight;
                                        if(scanHeight >= 1.0){
                                            cameraThread.quit();
                                            ((AppCompatActivity)getContext()).runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    listener.quitScan();
//                                                    galaxyMediaRecorder.stop();
                                                }
                                            });

                                        }
                                        lineRenderer.setVertices(
                                                -10f, 10f, 0f,
                                                10f, 10f, 0f);
                                        lineRenderer.setColor(.8f, .8f, 0f, 1.0f);
                                        lineRenderer.drawFrame();
                                        scanRenderer.drawFrame(videoRenderer.getTexture(),fh, getContext(), isNewScan);
                                        videoTexture = scanRenderer.getTexture();
                                    }
                                    isf = isScan;
                                    GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
                                    GLES20.glViewport(rect.left,rect.top,rect.width(),rect.height());
                                    mRenderer.drawFrame(videoTexture);
                                    mEglUtils.swap();

                                }
                            });
                        }
                    });

                    if(screenWidth != -1){
                        openCamera2();

                    }

                }
            });
        }

        @Override
        public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int w, int h) {
            final int sw = screenWidth;
            screenWidth = w;
            screenHeight = h;
            cameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    mPreviewSize =  getPreferredPreviewSize(mSizes, screenWidth, screenHeight);
                    Log.d("TAG", "run: " + mPreviewSize);
                    previewWidth = mPreviewSize.getHeight();
                    previewHeight = mPreviewSize.getWidth();
                    pixelHeight = 1.0f/previewHeight;
                    int left, top, viewWidth, viewHeight;
                    float sh = screenWidth * 1.0f / screenHeight;
                    float vh = previewWidth * 1.0f / previewHeight;
                    if (sh < vh) {
                        left = 0;
                        viewWidth = screenWidth;
                        viewHeight = (int) (previewHeight * 1.0f / previewWidth * viewWidth);
                        top = (screenHeight - viewHeight) / 2;
                        correctionDistance = top;
                    }
                    else {
                        top = 0;
                        viewHeight = screenHeight;
                        viewWidth = (int) (previewWidth * 1.0f / previewHeight * viewHeight);
                        left = (screenWidth - viewWidth) / 2;
                        correctionDistance = left;
                    }
                    rect.left = left;
                    rect.top = top;
                    rect.right = left + viewWidth;
                    rect.bottom = top + viewHeight;
                    scanContainerHeight = viewHeight;
                    scanContainerWidth = viewWidth;
                    videoRenderer.setSize(mPreviewSize.getWidth(),mPreviewSize.getHeight());
                    lineRenderer.setSize(mPreviewSize.getWidth(),mPreviewSize.getHeight());
                    scanRenderer.setSize(mPreviewSize.getWidth(),mPreviewSize.getHeight());
                    if(sw == -1){
                        openCamera2();
                    }
                }
            });
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
            cameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    destroyAll();
                }
            });
        }
    };

    public void setBrightness(int value) {
        int brightness = value;
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, brightness);
        applySettings();
    }

    private void applySettings() {
        try {
            mCameraCaptureSession.setRepeatingRequest(builder.build(), null, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void destroyAll() {
        if(mCameraCaptureSession != null){
            mCameraCaptureSession.getDevice().close();
            mCameraCaptureSession.close();
            mCameraCaptureSession = null;
        }
        GLES20.glDisable(GLES20.GL_BLEND);
        videoRenderer.release();
        mRenderer.release();
        scanRenderer.release();
        mEglUtils.release();
    }

    public boolean isFlashSupported = false;
    private Size[] mSizes;
    private void initCamera2() {
        HandlerThread handlerThread = new HandlerThread("Camera2");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        mCameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);

        try {
            assert mCameraManager != null;
            String[] CameraIdList = mCameraManager.getCameraIdList();
            mCameraId = CameraIdList[activeCamera];
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(map != null){
                mSizes = map.getOutputSizes(SurfaceTexture.class);
            }
            Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            isFlashSupported = available != null && available;
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    private Size mPreviewSize;










    private MediaRecorder galaxyMediaRecorder;
    private void galaxySetupMediaRecorder() {
        galaxyMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        galaxyMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        galaxyMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        galaxyMediaRecorder.setOutputFile(GalaxyConstants.FILTER_IMAGE_SAVED_PATH
                + System.currentTimeMillis() + ".mp4");
        galaxyMediaRecorder.setVideoEncodingBitRate(10000000);
        galaxyMediaRecorder.setVideoFrameRate(30);
        galaxyMediaRecorder.setVideoSize(previewWidth, previewHeight);;
        galaxyMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        galaxyMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        galaxyMediaRecorder.setPreviewDisplay(getHolder().getSurface());
        try {
            galaxyMediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }













    @SuppressLint("WrongConstant")
    private void openCamera2(){
        if (PermissionChecker.checkSelfPermission(getContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                galaxyMediaRecorder = new MediaRecorder();
                mCameraManager.openCamera(mCameraId, stateCallback, mHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }
    private CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            takePreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {

        }
    };

    public CaptureRequest.Builder builder;

    private void takePreview() {
        try {
            List<Surface> surfaces = new ArrayList<>();
            Surface renderSurface = videoRenderer.getSurface();
            surfaces.add(renderSurface);
            builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(renderSurface);
//            Surface recorderSurface = galaxyMediaRecorder.getSurface();
//            surfaces.add(recorderSurface);

//            galaxySetupMediaRecorder();

//            builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
//            builder.addTarget(videoRenderer.getSurface());

//            List<Surface> surfaces = new ArrayList<>();
//            surfaces.add(videoRenderer.getSurface());

            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (null == mCameraDevice) return;
                    mCameraCaptureSession = cameraCaptureSession;
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                    CaptureRequest previewRequest = builder.build();
                    try {
                        mCameraCaptureSession.setRepeatingRequest(previewRequest, null, mHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }

                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {

                }
            }, mHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private Size getPreferredPreviewSize(Size[] sizes, int width, int height) {
        List<Size> collectorSizes = new ArrayList<>();
        for (Size option : sizes) {
            if (width > height) {
                if (option.getWidth() > width && option.getHeight() > height) {
                    collectorSizes.add(option);
                }
            } else {
                if (option.getHeight() > width && option.getWidth() > height) {
                    collectorSizes.add(option);
                }
            }
        }
        Log.d("TAG", "getPreferredPreviewSize: " + collectorSizes);
        if (!collectorSizes.isEmpty()) {
            return Collections.min(collectorSizes, new Comparator<Size>() {
                @Override
                public int compare(Size s1, Size s2) {
                    return Long.signum((long) s1.getWidth() * s1.getHeight() - (long) s2.getWidth() * s2.getHeight());
                }
            });
        }
//        return sizes[0];
        return new Size(height * 4 / 5, width);
    }}
