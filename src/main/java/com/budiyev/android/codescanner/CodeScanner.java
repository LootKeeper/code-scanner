/*
 * MIT License
 *
 * Copyright (c) 2017 Yuriy Budiyev [yuriy.budiyev@yandex.ru]
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.budiyev.android.codescanner;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.Point;
import android.hardware.Camera;
import android.os.Handler;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.SurfaceHolder;

import com.google.zxing.BarcodeFormat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Scanner of different codes
 *
 * @see CodeScannerView
 * @see BarcodeFormat
 */
public final class CodeScanner {
    public static final List<BarcodeFormat> ALL_FORMATS =
            Arrays.asList(BarcodeFormat.AZTEC, BarcodeFormat.CODABAR, BarcodeFormat.CODE_39,
                    BarcodeFormat.CODE_93, BarcodeFormat.CODE_128, BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.EAN_8, BarcodeFormat.EAN_13, BarcodeFormat.ITF,
                    BarcodeFormat.MAXICODE, BarcodeFormat.PDF_417, BarcodeFormat.QR_CODE,
                    BarcodeFormat.RSS_14, BarcodeFormat.RSS_EXPANDED, BarcodeFormat.UPC_A,
                    BarcodeFormat.UPC_E, BarcodeFormat.UPC_EAN_EXTENSION);
    public static final List<BarcodeFormat> ONE_DIMENSIONAL_FORMATS =
            Arrays.asList(BarcodeFormat.CODABAR, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93,
                    BarcodeFormat.CODE_128, BarcodeFormat.EAN_8, BarcodeFormat.EAN_13,
                    BarcodeFormat.ITF, BarcodeFormat.RSS_14, BarcodeFormat.RSS_EXPANDED,
                    BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.UPC_EAN_EXTENSION);
    public static final List<BarcodeFormat> TWO_DIMENSIONAL_FORMATS =
            Arrays.asList(BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX, BarcodeFormat.MAXICODE,
                    BarcodeFormat.PDF_417, BarcodeFormat.QR_CODE);
    private static final long AUTO_FOCUS_INTERVAL = 1500L;
    private static final int UNSPECIFIED = -1;
    private static final int FOCUS_ATTEMPTS_THRESHOLD = 2;
    private final Lock mInitializeLock = new ReentrantLock();
    private final Context mContext;
    private final Handler mMainThreadHandler;
    private final CodeScannerView mScannerView;
    private final SurfaceHolder mSurfaceHolder;
    private final SurfaceHolder.Callback mSurfaceCallback;
    private final Camera.PreviewCallback mPreviewCallback;
    private final Camera.AutoFocusCallback mAutoFocusCallback;
    private final Runnable mAutoFocusTask;
    private final Runnable mStopPreviewTask;
    private final DecoderStateListener mDecoderStateListener;
    private final int mCameraId;
    private volatile List<BarcodeFormat> mFormats = ALL_FORMATS;
    private volatile DecodeCallback mDecodeCallback;
    private volatile ErrorCallback mErrorCallback;
    private volatile DecoderWrapper mDecoderWrapper;
    private volatile boolean mInitialization;
    private volatile boolean mInitialized;
    private volatile boolean mStoppingPreview;
    private volatile boolean mAutoFocusEnabled = true;
    private volatile boolean mFlashEnabled;
    private boolean mPreviewActive;
    private boolean mFocusing;
    private int mFocusAttemptsCount;

    /**
     * CodeScanner, associated with the first back-facing camera on the device
     *
     * @param context Context
     * @param view    A view to display the preview
     * @see CodeScannerView
     */
    @MainThread
    public CodeScanner(@NonNull Context context, @NonNull CodeScannerView view) {
        this(context, view, UNSPECIFIED);
    }

    /**
     * CodeScanner, associated with particular hardware camera
     *
     * @param context  Context
     * @param view     A view to display the preview
     * @param cameraId Camera id (between {@code 0} and
     *                 {@link Camera#getNumberOfCameras()} - {@code 1})
     * @see CodeScannerView
     */
    @MainThread
    public CodeScanner(@NonNull Context context, @NonNull CodeScannerView view, int cameraId) {
        mContext = context;
        mScannerView = view;
        mSurfaceHolder = view.getPreviewView().getHolder();
        mMainThreadHandler = new Handler();
        mSurfaceCallback = new SurfaceCallback();
        mPreviewCallback = new PreviewCallback();
        mAutoFocusCallback = new AutoFocusCallback();
        mAutoFocusTask = new AutoFocusTask();
        mStopPreviewTask = new StopPreviewTask();
        mDecoderStateListener = new DecoderStateListener();
        mCameraId = cameraId;
        mScannerView.setCodeScanner(this);
    }

    /**
     * Set formats, decoder to react to ({@link #ALL_FORMATS} by default)
     *
     * @param formats Formats
     * @see BarcodeFormat
     * @see #ALL_FORMATS
     * @see #ONE_DIMENSIONAL_FORMATS
     * @see #TWO_DIMENSIONAL_FORMATS
     */
    public void setFormats(@NonNull List<BarcodeFormat> formats) {
        mInitializeLock.lock();
        try {
            if (mInitialized) {
                mDecoderWrapper.getDecoder().setFormats(formats);
            } else {
                mFormats = formats;
            }
        } finally {
            mInitializeLock.unlock();
        }
    }

    /**
     * Set formats, decoder to react to ({@link #ALL_FORMATS} by default)
     *
     * @param formats Formats
     * @see BarcodeFormat
     * @see #ALL_FORMATS
     * @see #ONE_DIMENSIONAL_FORMATS
     * @see #TWO_DIMENSIONAL_FORMATS
     */
    public void setFormats(@NonNull BarcodeFormat... formats) {
        setFormats(Arrays.asList(formats));
    }

    /**
     * Set format, decoder to react to
     *
     * @param format Format
     * @see BarcodeFormat
     */
    public void setFormat(@NonNull BarcodeFormat format) {
        setFormats(Collections.singletonList(format));
    }

    /**
     * Set callback of decoding process
     *
     * @param decodeCallback Callback
     * @see DecodeCallback
     */
    public void setDecodeCallback(@Nullable DecodeCallback decodeCallback) {
        mDecodeCallback = decodeCallback;
    }

    /**
     * Set camera initialization error callback.
     * If not set, an exception will be thrown when error will occur.
     *
     * @param errorCallback Callback
     * @see ErrorCallback#SUPPRESS
     */
    public void setErrorCallback(@Nullable ErrorCallback errorCallback) {
        mErrorCallback = errorCallback;
    }

    /**
     * Whether to enable or disable auto focus if it's supported, {@code true} by default
     */
    public void setAutoFocusEnabled(boolean autoFocusEnabled) {
        mInitializeLock.lock();
        try {
            boolean changed = mAutoFocusEnabled != autoFocusEnabled;
            mAutoFocusEnabled = autoFocusEnabled;
            mScannerView.setAutoFocusEnabled(autoFocusEnabled);
            if (mInitialized && mPreviewActive && changed &&
                    mDecoderWrapper.isAutoFocusSupported()) {
                setAutoFocusEnabledInternal(autoFocusEnabled);
            }
        } finally {
            mInitializeLock.unlock();
        }
    }

    /**
     * Whether if auto focus is currently enabled
     */
    public boolean isAutoFocusEnabled() {
        return mAutoFocusEnabled;
    }

    /**
     * Whether to enable or disable flash light if it's supported, {@code false} by default
     */
    public void setFlashEnabled(boolean flashEnabled) {
        mInitializeLock.lock();
        try {
            boolean changed = mFlashEnabled != flashEnabled;
            mFlashEnabled = flashEnabled;
            mScannerView.setFlashEnabled(flashEnabled);
            if (mInitialized && mPreviewActive && changed && mDecoderWrapper.isFlashSupported()) {
                setFlashEnabledInternal(flashEnabled);
            }
        } finally {
            mInitializeLock.unlock();
        }
    }

    /**
     * Whether if flash light is currently enabled
     */
    public boolean isFlashEnabled() {
        return mFlashEnabled;
    }

    /**
     * Whether if preview is active
     */
    public boolean isPreviewActive() {
        return mPreviewActive;
    }

    /**
     * Start camera preview
     * <br>
     * Requires {@link Manifest.permission#CAMERA} permission
     */
    @MainThread
    public void startPreview() {
        mInitializeLock.lock();
        try {
            if (!mInitialized && !mInitialization) {
                mInitialization = true;
                initialize();
                return;
            }
        } finally {
            mInitializeLock.unlock();
        }
        if (!mPreviewActive) {
            mSurfaceHolder.addCallback(mSurfaceCallback);
            startPreviewInternal(false);
        }
    }

    /**
     * Stop camera preview
     */
    @MainThread
    public void stopPreview() {
        if (mInitialized && mPreviewActive) {
            mSurfaceHolder.removeCallback(mSurfaceCallback);
            stopPreviewInternal(false);
        }
    }

    /**
     * Release resources, and stop preview if needed; call this method in {@link Activity#onPause()}
     */
    @MainThread
    public void releaseResources() {
        if (mInitialized) {
            if (mPreviewActive) {
                stopPreview();
            }
            releaseResourcesInternal();
        }
    }

    private void initialize() {
        if (Utils.isLaidOut(mScannerView)) {
            initialize(mScannerView.getWidth(), mScannerView.getHeight());
        } else {
            mScannerView.setLayoutListener(new ScannerLayoutListener());
        }
    }

    private void initialize(int width, int height) {
        new InitializationThread(width, height).start();
    }

    private void startPreviewInternal(boolean internal) {
        try {
            DecoderWrapper decoderWrapper = mDecoderWrapper;
            Camera camera = decoderWrapper.getCamera();
            camera.setPreviewCallback(mPreviewCallback);
            camera.setPreviewDisplay(mSurfaceHolder);
            if (!internal && decoderWrapper.isFlashSupported() && mFlashEnabled) {
                setFlashEnabledInternal(true);
            }
            camera.startPreview();
            mStoppingPreview = false;
            mPreviewActive = true;
            mFocusing = false;
            mFocusAttemptsCount = 0;
            scheduleAutoFocusTask();
        } catch (Exception ignored) {
        }
    }

    private void startPreviewInternalSafe() {
        if (mInitialized && !mPreviewActive) {
            startPreviewInternal(true);
        }
    }

    private void stopPreviewInternal(boolean internal) {
        try {
            DecoderWrapper decoderWrapper = mDecoderWrapper;
            Camera camera = decoderWrapper.getCamera();
            if (!internal && decoderWrapper.isFlashSupported() && mFlashEnabled) {
                Camera.Parameters parameters = camera.getParameters();
                if (parameters != null &&
                        Utils.setFlashMode(parameters, Camera.Parameters.FLASH_MODE_OFF)) {
                    camera.setParameters(parameters);
                }
            }
            camera.setPreviewCallback(null);
            camera.stopPreview();
        } catch (Exception ignored) {
        }
        mStoppingPreview = false;
        mPreviewActive = false;
        mFocusing = false;
        mFocusAttemptsCount = 0;
    }

    private void stopPreviewInternalSafe() {
        if (mInitialized && mPreviewActive) {
            stopPreviewInternal(true);
        }
    }

    private void releaseResourcesInternal() {
        mInitialized = false;
        mInitialization = false;
        mStoppingPreview = false;
        mPreviewActive = false;
        mFocusing = false;
        DecoderWrapper decoderWrapper = mDecoderWrapper;
        if (decoderWrapper != null) {
            mDecoderWrapper = null;
            decoderWrapper.release();
        }
    }

    private void setFlashEnabledInternal(boolean flashEnabled) {
        try {
            DecoderWrapper decoderWrapper = mDecoderWrapper;
            Camera camera = decoderWrapper.getCamera();
            Camera.Parameters parameters = camera.getParameters();
            if (parameters == null) {
                return;
            }
            boolean changed;
            if (flashEnabled) {
                changed = Utils.setFlashMode(parameters, Camera.Parameters.FLASH_MODE_TORCH);
            } else {
                changed = Utils.setFlashMode(parameters, Camera.Parameters.FLASH_MODE_OFF);
            }
            if (changed) {
                camera.setParameters(parameters);
            }
        } catch (Exception ignored) {
        }
    }

    private void setAutoFocusEnabledInternal(boolean autoFocusEnabled) {
        try {
            DecoderWrapper decoderWrapper = mDecoderWrapper;
            Camera camera = decoderWrapper.getCamera();
            Camera.Parameters parameters = camera.getParameters();
            if (parameters == null) {
                return;
            }
            boolean changed;
            if (autoFocusEnabled) {
                changed = Utils.setFocusMode(parameters, Camera.Parameters.FOCUS_MODE_AUTO);
            } else {
                camera.cancelAutoFocus();
                changed = Utils.setFocusMode(parameters, Camera.Parameters.FOCUS_MODE_FIXED);
            }
            if (changed) {
                camera.setParameters(parameters);
            }
            if (autoFocusEnabled) {
                scheduleAutoFocusTask();
            }
        } catch (Exception ignored) {
        }
    }

    private void autoFocusCamera() {
        if (!mInitialized || !mPreviewActive) {
            return;
        }
        if (!mDecoderWrapper.isAutoFocusSupported() || !mAutoFocusEnabled) {
            return;
        }
        if (mFocusing && mFocusAttemptsCount < FOCUS_ATTEMPTS_THRESHOLD) {
            mFocusAttemptsCount++;
        } else {
            try {
                mDecoderWrapper.getCamera().autoFocus(mAutoFocusCallback);
                mFocusAttemptsCount = 0;
                mFocusing = true;
            } catch (Exception e) {
                mFocusing = false;
            }
        }
        scheduleAutoFocusTask();
    }

    private void scheduleAutoFocusTask() {
        mMainThreadHandler.postDelayed(mAutoFocusTask, AUTO_FOCUS_INTERVAL);
    }

    private final class ScannerLayoutListener implements CodeScannerView.LayoutListener {
        @Override
        public void onLayout(int width, int height) {
            initialize(width, height);
            mScannerView.setLayoutListener(null);
        }
    }

    private final class PreviewCallback implements Camera.PreviewCallback {
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            if (!mInitialized || mStoppingPreview) {
                return;
            }
            DecoderWrapper decoderWrapper = mDecoderWrapper;
            Decoder decoder = decoderWrapper.getDecoder();
            if (decoder.isProcessing()) {
                return;
            }
            Point previewSize = decoderWrapper.getPreviewSize();
            Point frameSize = decoderWrapper.getFrameSize();
            decoder.decode(data, previewSize.x, previewSize.y, frameSize.x, frameSize.y,
                    decoderWrapper.getDisplayOrientation(), mScannerView.isSquareFrame(),
                    decoderWrapper.getCameraInfo().facing == Camera.CameraInfo.CAMERA_FACING_FRONT,
                    mDecodeCallback);
        }
    }

    private final class SurfaceCallback implements SurfaceHolder.Callback {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            startPreviewInternalSafe();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (holder.getSurface() == null) {
                mPreviewActive = false;
                return;
            }
            stopPreviewInternalSafe();
            startPreviewInternalSafe();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            stopPreviewInternalSafe();
        }
    }

    private final class DecoderStateListener implements Decoder.StateListener {
        @Override
        public void onStateChanged(int state) {
            if (state == Decoder.State.DECODED) {
                mStoppingPreview = true;
                mMainThreadHandler.post(mStopPreviewTask);
            }
        }
    }

    boolean isAutoFocusSupportedOrUnknown() {
        DecoderWrapper wrapper = mDecoderWrapper;
        return wrapper == null || wrapper.isAutoFocusSupported();
    }

    boolean isFlashSupportedOrUnknown() {
        DecoderWrapper wrapper = mDecoderWrapper;
        return wrapper == null || wrapper.isFlashSupported();
    }

    private final class InitializationThread extends Thread {
        private final int mWidth;
        private final int mHeight;

        public InitializationThread(int width, int height) {
            super("Code scanner initialization thread");
            if (getPriority() != Thread.NORM_PRIORITY) {
                setPriority(Thread.NORM_PRIORITY);
            }
            if (isDaemon()) {
                setDaemon(false);
            }
            mWidth = width;
            mHeight = height;
        }

        @Override
        @SuppressWarnings("SuspiciousNameCombination")
        public void run() {
            try {
                initialize();
            } catch (Exception e) {
                releaseResourcesInternal();
                ErrorCallback errorCallback = mErrorCallback;
                if (errorCallback != null) {
                    errorCallback.onError(e);
                } else {
                    throw e;
                }
            }
        }

        private void initialize() {
            Camera camera = null;
            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
            if (mCameraId == UNSPECIFIED) {
                int numberOfCameras = Camera.getNumberOfCameras();
                for (int i = 0; i < numberOfCameras; i++) {
                    Camera.getCameraInfo(i, cameraInfo);
                    if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                        camera = Camera.open(i);
                        break;
                    }
                }
            } else {
                camera = Camera.open(mCameraId);
                Camera.getCameraInfo(mCameraId, cameraInfo);
            }
            if (camera == null) {
                throw new RuntimeException("Unable to access camera");
            }
            Camera.Parameters parameters = camera.getParameters();
            if (parameters == null) {
                throw new RuntimeException("Unable to configure camera");
            }
            int orientation = Utils.getDisplayOrientation(mContext, cameraInfo);
            boolean portrait = Utils.isPortrait(orientation);
            Point previewSize =
                    Utils.findSuitablePreviewSize(parameters, portrait ? mHeight : mWidth,
                            portrait ? mWidth : mHeight);
            parameters.setPreviewSize(previewSize.x, previewSize.y);
            Point frameSize = Utils.getFrameSize(portrait ? previewSize.y : previewSize.x,
                    portrait ? previewSize.x : previewSize.y, mWidth, mHeight);
            List<String> focusModes = parameters.getSupportedFocusModes();
            boolean autoFocusSupported =
                    focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO);
            if (!autoFocusSupported) {
                mAutoFocusEnabled = false;
            }
            if (autoFocusSupported && mAutoFocusEnabled) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            List<String> flashModes = parameters.getSupportedFlashModes();
            boolean flashSupported =
                    flashModes != null && flashModes.contains(Camera.Parameters.FLASH_MODE_TORCH);
            if (!flashSupported) {
                mFlashEnabled = false;
            }
            camera.setParameters(Utils.optimizeParameters(parameters));
            camera.setDisplayOrientation(orientation);
            mInitializeLock.lock();
            try {
                Decoder decoder = new Decoder(mDecoderStateListener, mFormats);
                mDecoderWrapper =
                        new DecoderWrapper(camera, cameraInfo, decoder, previewSize, frameSize,
                                orientation, autoFocusSupported, flashSupported);
                decoder.start();
                mInitialization = false;
                mInitialized = true;
            } finally {
                mInitializeLock.unlock();
            }
            mMainThreadHandler.post(new FinishInitializationTask(frameSize));
        }
    }

    private final class AutoFocusCallback implements Camera.AutoFocusCallback {
        @Override
        public void onAutoFocus(boolean success, Camera camera) {
            mFocusing = false;
        }
    }

    private final class AutoFocusTask implements Runnable {
        @Override
        public void run() {
            autoFocusCamera();
        }
    }

    private final class StopPreviewTask implements Runnable {
        @Override
        public void run() {
            stopPreview();
        }
    }

    private final class FinishInitializationTask implements Runnable {
        private final Point mFrameSize;

        private FinishInitializationTask(@NonNull Point frameSize) {
            mFrameSize = frameSize;
        }

        @Override
        public void run() {
            if (!mInitialized) {
                return;
            }
            mScannerView.setFrameSize(mFrameSize);
            mScannerView.setCodeScanner(CodeScanner.this);
            startPreview();
        }
    }

    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int mCameraId = UNSPECIFIED;
        private List<BarcodeFormat> mFormats = ALL_FORMATS;
        private DecodeCallback mDecodeCallback;
        private ErrorCallback mErrorCallback;
        private boolean mAutoFocusEnabled = true;
        private boolean mFlashEnabled;

        private Builder() {
        }

        @NonNull
        public Builder camera(int cameraId) {
            mCameraId = cameraId;
            return this;
        }

        @NonNull
        public Builder formats(@NonNull BarcodeFormat... formats) {
            mFormats = Arrays.asList(formats);
            return this;
        }

        @NonNull
        public Builder formats(@NonNull List<BarcodeFormat> formats) {
            mFormats = formats;
            return this;
        }

        @NonNull
        public Builder onDecoded(@Nullable DecodeCallback callback) {
            mDecodeCallback = callback;
            return this;
        }

        @NonNull
        public Builder onError(@Nullable ErrorCallback callback) {
            mErrorCallback = callback;
            return this;
        }

        @NonNull
        public Builder autoFocus(boolean enabled) {
            mAutoFocusEnabled = enabled;
            return this;
        }

        @NonNull
        public Builder flash(boolean enabled) {
            mFlashEnabled = enabled;
            return this;
        }

        @NonNull
        public CodeScanner build(@NonNull Context context, @NonNull CodeScannerView view) {
            CodeScanner scanner = new CodeScanner(context, view, mCameraId);
            scanner.mFormats = mFormats;
            scanner.mDecodeCallback = mDecodeCallback;
            scanner.mErrorCallback = mErrorCallback;
            scanner.mAutoFocusEnabled = mAutoFocusEnabled;
            scanner.mFlashEnabled = mFlashEnabled;
            return scanner;
        }
    }
}
