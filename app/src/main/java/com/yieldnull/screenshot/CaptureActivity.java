package com.yieldnull.screenshot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.percent.PercentLayoutHelper;
import android.support.percent.PercentRelativeLayout;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CaptureActivity extends AppCompatActivity {

    private static final String TAG = CaptureActivity.class.getSimpleName();
    private static final int REQUEST_CODE = 100;


    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;

    private ImageReader mImageReader;
    private VirtualDisplay mCaptureVirtualDisplay;
    private VirtualDisplay mPreviewVirtualDisplay;

    private Handler mWorkingHandler;
    private HandlerThread mWorkingThread;

    private File repository;

    private int mWidth;
    private int mHeight;
    private int mDensityDpi;

    private TextureView mTextureView;
    private Surface mPreviewSurface;
    private PercentRelativeLayout mRelativeLayout;

    private boolean gotPreview;
    private boolean gotCapture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // TODO do not capture when starting from history cached app

        // AppCompat no title
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_capture);

        // disable background dim when capturing
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.dimAmount = 0;
        getWindow().setAttributes(layoutParams);

        // get screen width, height and density
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        mWidth = size.x;
        mHeight = size.y;

        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        mDensityDpi = metrics.densityDpi;

        Log.i(TAG, String.format("Screen:: Width:%d Height:%d Density:%d", mWidth, mHeight, mDensityDpi));


        // set preview image and its container invisible
        mTextureView = (TextureView) findViewById(R.id.textureView);
        mRelativeLayout = (PercentRelativeLayout) findViewById(R.id.percentRelativeLayout);

        mRelativeLayout.setBackground(null);

        // set preview image size corresponding to orientation
        PercentRelativeLayout.LayoutParams params = (PercentRelativeLayout.LayoutParams) mTextureView.getLayoutParams();
        PercentLayoutHelper.PercentLayoutInfo info = params.getPercentLayoutInfo();

        if (mWidth < mHeight) {
            info.widthPercent = 0.75f;
        } else {
            info.widthPercent = 1.0f; // why in landscape mode wrap_content not working?
        }
        info.aspectRatio = mWidth * 1.0f / mHeight;
        mTextureView.requestLayout();

        // file repository
        repository = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "Screenshots");


        // init thread for saving capture to file
        mWorkingThread = new HandlerThread("CaptureThread");
        mWorkingThread.start();
        mWorkingHandler = new Handler(mWorkingThread.getLooper());


        // init capture
        mProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {

            private int count = 0;

            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                mPreviewSurface = new Surface(surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

                if (count++ == 0) {
                    Log.i(TAG, "SurfaceTexture: updated");
                    gotPreview = true;
                    stopProjection();
                }
            }
        });

        // start capture when quick setting panel collapsed
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "starting projection");

                startActivityForResult(mProjectionManager.createScreenCaptureIntent(), REQUEST_CODE);
            }
        }, 1000);
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.i(TAG, "onActivityResult");

        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);

            Log.i(TAG, "projection started");

            if (mMediaProjection != null) {
                mCaptureVirtualDisplay = mMediaProjection.createVirtualDisplay("ScreenCapture",
                        mWidth, mHeight, mDensityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                        mImageReader.getSurface(), null, null);

                mPreviewVirtualDisplay = mMediaProjection.createVirtualDisplay("ScreenPreview",
                        mTextureView.getWidth(), mTextureView.getHeight(), mDensityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                        mPreviewSurface, null, null);

                mImageReader.setOnImageAvailableListener(mImageAvailableListener, mWorkingHandler);

                mMediaProjection.registerCallback(mProjectionStopCallback, mWorkingHandler);
            }

        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy quit working thread");
        mWorkingThread.quit();

        super.onDestroy();
    }


    /**
     * Show preview
     */
    private void showPreview() {
        Log.i(TAG, "Show preview");

        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.dimAmount = 0.6f;
        getWindow().setAttributes(layoutParams);

        mRelativeLayout.setBackground(getDrawable(R.drawable.border_preview));
    }


    /**
     * Stop projection after showing preview and capturing screen.
     */
    private void stopProjection() {
        if (gotCapture && gotPreview) {
            showPreview();
            mMediaProjection.stop();
        }
    }


    /**
     * Release virtual display when stopping projection.
     */
    private MediaProjection.Callback mProjectionStopCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.i(TAG, "projection stopped");

            mCaptureVirtualDisplay.release();
            mPreviewVirtualDisplay.release();

            mCaptureVirtualDisplay = null;
            mPreviewVirtualDisplay = null;
        }
    };


    /**
     * Saving captured image to file.
     * <p/>
     * http://stackoverflow.com/questions/27581750/android-capture-screen-to-surface-of-imagereader
     */
    private ImageReader.OnImageAvailableListener mImageAvailableListener = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.i(TAG, "ImageReader:image available");

            mImageReader.setOnImageAvailableListener(null, null);

            gotCapture = true;
            stopProjection();

            Image image = null;
            try {
                image = mImageReader.acquireLatestImage();

                if (image == null) {
                    return;
                }

                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();

                int width = image.getWidth();
                int height = image.getHeight();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * width;

                Log.i(TAG, "Starting process");

                ByteBuffer bufferCopy = ByteBuffer.allocate(mWidth * mHeight * 4);
                final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

                int offset = 0;
                int index = 0;
                for (int i = 0; i < height; ++i) {
                    for (int j = 0; j < width; ++j) {
                        bufferCopy.put(index++, buffer.get(offset));
                        bufferCopy.put(index++, buffer.get(offset + 1));
                        bufferCopy.put(index++, buffer.get(offset + 2));
                        bufferCopy.put(index++, buffer.get(offset + 3));

                        offset += pixelStride;
                    }
                    offset += rowPadding;
                }
                bitmap.copyPixelsFromBuffer(bufferCopy);

                Log.i(TAG, "Starting storing file");

                FileOutputStream fos = null;
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMDD-HHmmss", Locale.ENGLISH);
                String time = dateFormat.format(new Date());

                try {
                    fos = new FileOutputStream(new File(repository, String.format("Screenshot_%s.png", time)));
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);

                    Log.i(TAG, "ImageReader:image captured");

                } catch (FileNotFoundException e) {
                    Log.w(TAG, e.getMessage());
                } finally {
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (IOException e) {
                            Log.w(TAG, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, e);
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }
    };
}
