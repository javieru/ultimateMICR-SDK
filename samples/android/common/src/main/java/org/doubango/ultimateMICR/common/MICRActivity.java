/*
 * Copyright (C) 2016-2020 Doubango AI <https://www.doubango.org>
 * License: For non-commercial use only
 * Source code: https://github.com/DoubangoTelecom/ultimateMICR-SDK
 * WebSite: https://www.doubango.org/webapps/micr/
 */
package org.doubango.ultimateMICR.common;

import android.graphics.RectF;
import android.media.ExifInterface;
import android.media.Image;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.doubango.ultimateMicr.Sdk.ULTMICR_SDK_IMAGE_TYPE;
import org.doubango.ultimateMicr.Sdk.UltMicrSdkEngine;
import org.doubango.ultimateMicr.Sdk.UltMicrSdkResult;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * Base activity to subclass to make our life easier
 */
public abstract class MICRActivity extends AppCompatActivity implements MICRCameraFragment.CCardCameraFragmentSink {

    static final String TAG = MICRActivity.class.getCanonicalName();

    private String mDebugInternalDataPath = null;

    private boolean mIsProcessing = false;

    private boolean mIsPaused = true;

    private MICRView mMICRView;

    private final MICRBackgroundTask mBackgroundTaskInference = new MICRBackgroundTask();

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        Log.i(TAG, "onCreate " + this);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(getLayoutResId());

        // Create folder to dump input images for debugging
        File dummyFile = new File(getExternalFilesDir(null), "dummyFile");
        if (!dummyFile.getParentFile().exists() && !dummyFile.getParentFile().mkdirs()) {
            Log.e(TAG,"mkdir failed: " + dummyFile.getParentFile().getAbsolutePath());
        }
        mDebugInternalDataPath = dummyFile.getParentFile().exists() ? dummyFile.getParent() : Environment.getExternalStorageDirectory().getAbsolutePath();
        dummyFile.delete();

        // Init the engine
        final JSONObject config = getJsonConfig();
        // Retrieve previously stored key from internal storage
        String tokenFile = MICRLicenseActivator.tokenFile(this);
        if (!tokenFile.isEmpty()) {
            try {
                config.put("license_token_data", MICRLicenseActivator.tokenData(tokenFile));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        final UltMicrSdkResult result = MICRUtils.assertIsOk(UltMicrSdkEngine.init(
                getAssets(),
                config.toString()
        ));
        Log.i(TAG,"MICR engine initialized: " + MICRUtils.resultToString(result));

        // Activate the license
        final boolean isActivationPossible = !getActivationServerUrl().isEmpty() && !getActivationMasterOrSlaveKey().isEmpty();
        if (isActivationPossible && tokenFile.isEmpty()) {
            // Generate the license key and store it to the internal storage for next times
            tokenFile = MICRLicenseActivator.activate(this, getActivationServerUrl(), getActivationMasterOrSlaveKey(), false);
            if (!tokenFile.isEmpty()) {
                try {
                    config.put("license_token_data", MICRLicenseActivator.tokenData(tokenFile));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                MICRUtils.assertIsOk(UltMicrSdkEngine.init(
                        getAssets(),
                        config.toString()
                ));
            }
        }

        // WarmUp to avoid slow inference on the first detection
        MICRUtils.assertIsOk(UltMicrSdkEngine.warmUp(ULTMICR_SDK_IMAGE_TYPE.ULTMICR_SDK_IMAGE_TYPE_YUV420P));
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy " + this);
        // DeInitialize the engine. This will stop all threads and cleanup all pending calls.
        // If you're performing a work in a parallel callback thread, then this function will
        // block until the end.
        final UltMicrSdkResult result = MICRUtils.assertIsOk(UltMicrSdkEngine.deInit());
        Log.i(TAG,"MICR engine deInitialized: " + MICRUtils.resultToString(result));

        super.onDestroy();
    }

    @Override
    public synchronized void onResume() {
        super.onResume();

        mIsPaused = false;
        mBackgroundTaskInference.start("mBackgroundTaskInference");
    }

    @Override
    public synchronized void onPause() {
        mIsPaused = true;
        mBackgroundTaskInference.stop();

        super.onPause();
    }

    @Override
    public void setMICRView(@NonNull final MICRView view) {
        mMICRView = view;
        final List<Float> roi = getDetectROI();
        assert(roi.size() == 4);
        mMICRView.setDetectROI(
                new RectF(
                        roi.get(0).floatValue(),
                        roi.get(2).floatValue(),
                        roi.get(1).floatValue(),
                        roi.get(3).floatValue()
                )
        );
    }

    @Override
    public void setImage(@NonNull final Image image, final int jpegOrientation) {

        if (mIsProcessing || !mBackgroundTaskInference.isRunning() || mIsPaused) {
            image.close();
            return;
        }

        // Mark as processing
        mIsProcessing = true;

        // Schedule processing
        mBackgroundTaskInference.post(new Runnable() {
            @Override
            public void run() {
                final Size imageSize = new Size(image.getWidth(), image.getHeight());

                // Orientation
                // Convert from degree to real EXIF orientation
                int exifOrientation;
                switch (jpegOrientation) {
                    case 90: exifOrientation = ExifInterface.ORIENTATION_ROTATE_90; break;
                    case 180: exifOrientation = ExifInterface.ORIENTATION_ROTATE_180; break;
                    case 270: exifOrientation = ExifInterface.ORIENTATION_ROTATE_270; break;
                    case 0: default: exifOrientation = ExifInterface.ORIENTATION_NORMAL; break;
                }

                // The actual deep learning inference is done here
                final Image.Plane[] planes = image.getPlanes();
                final long startTimeInMillis = SystemClock.uptimeMillis();
                final UltMicrSdkResult result = /*MICRUtils.assertIsOk*/(UltMicrSdkEngine.process(
                        ULTMICR_SDK_IMAGE_TYPE.ULTMICR_SDK_IMAGE_TYPE_YUV420P,
                        planes[0].getBuffer(),
                        planes[1].getBuffer(),
                        planes[2].getBuffer(),
                        imageSize.getWidth(),
                        imageSize.getHeight(),
                        planes[0].getRowStride(),
                        planes[1].getRowStride(),
                        planes[2].getRowStride(),
                        planes[1].getPixelStride(),
                        exifOrientation
                ));
                final long durationInMillis = SystemClock.uptimeMillis() - startTimeInMillis;

                // Release the image and signal the inference process is finished
                image.close();

                // Mark as no longer processing
                mIsProcessing = false;

                // Display the result to the console
                if (result.isOK()) {
                    Log.d(TAG, MICRUtils.resultToString(result));
                } else {
                    Log.e(TAG, MICRUtils.resultToString(result));
                }

                // Draw the result to the view
                if (mMICRView != null) {
                    mMICRView.setResult(
                            result, (
                                    jpegOrientation % 180) == 0 ? imageSize : new Size(imageSize.getHeight(), imageSize.getWidth()),
                            durationInMillis
                    );
                }
            }
        });
    }

    /**
     * Gets the base folder defining a path where the application can write private
     * data.
     * @return The path
     */
    protected String getDebugInternalDataPath() {
        return mDebugInternalDataPath;
    }

    /**
     * Gets the server url used to activate the license. Please contact us to get the correct URL.
     * e.g. https://localhost:3600
     * @return The URL
     */
    protected String getActivationServerUrl() {
        return "";
    }

    /**
     * Gets the master or slave key to use for the activation.
     * You MUST NEVER include your master key in the code or share it with the end user.
     * The master key should be used to generate slaves (one-time activation keys).
     * More information about master/slave keys at https://www.doubango.org/SDKs/LicenseManager/docs/Jargon.html.
     * @return The master of slave key.
     */
    protected String getActivationMasterOrSlaveKey() {
        return "";
    }

    /**
     * Returns the layout Id for the activity
     * @return
     */
    protected abstract int getLayoutResId();

    /**
     * Returns JSON config to be used to initialize the MICR SDK.
     * @return The JSON config
     */
    protected abstract JSONObject getJsonConfig();

    protected abstract List<Float> getDetectROI();
}