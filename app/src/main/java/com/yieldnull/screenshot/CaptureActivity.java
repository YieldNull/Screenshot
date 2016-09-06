package com.yieldnull.screenshot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
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
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

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
    private VirtualDisplay mVirtualDisplay;

    private Handler mWorkingHandler;
    private HandlerThread mWorkingThread;

    private File repository;

    private int mWidth;
    private int mHeight;
    private int mDensityDpi;

    private ImageView mPreviewImage;
    private PercentRelativeLayout mRelativeLayout;

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
        mPreviewImage = (ImageView) findViewById(R.id.imageView);
        mRelativeLayout = (PercentRelativeLayout) findViewById(R.id.percentRelativeLayout);

        mPreviewImage.setImageDrawable(null);
        mRelativeLayout.setBackground(null);

        // set preview image size corresponding to orientation
        PercentRelativeLayout.LayoutParams params = (PercentRelativeLayout.LayoutParams) mPreviewImage.getLayoutParams();
        PercentLayoutHelper.PercentLayoutInfo info = params.getPercentLayoutInfo();

        if (mWidth < mHeight) {
            info.widthPercent = 0.75f;
        } else {
            info.widthPercent = 1.0f; // why in landscape mode wrap_content not working?
        }
        info.aspectRatio = mWidth * 1.0f / mHeight;
        mPreviewImage.requestLayout();


        // init working thread
        mWorkingThread = new HandlerThread("CaptureThread");
        mWorkingThread.start();
        mWorkingHandler = new Handler(mWorkingThread.getLooper());

        // file repository
        repository = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "Screenshots");


        // init capture
        mProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);


        // start capture when quick setting panel collapsed
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                startActivityForResult(mProjectionManager.createScreenCaptureIntent(), REQUEST_CODE);
            }
        }, 1000);
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.i(TAG, "activityResult");

        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);

            if (mMediaProjection != null) {
                handleCapture(mMediaProjection);

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

    private void handleCapture(MediaProjection mediaProjection) {

        mVirtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                mWidth, mHeight, mDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                mImageReader.getSurface(), null, mWorkingHandler);


        mImageReader.setOnImageAvailableListener(mImageAvailableListener, mWorkingHandler);
    }

    private void showPreview(Bitmap bitmap) {
        Log.i(TAG, "Show preview");

        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.dimAmount = 0.6f;
        getWindow().setAttributes(layoutParams);

        mPreviewImage.setImageBitmap(bitmap);
        mRelativeLayout.setBackground(getDrawable(R.drawable.border_preview));
    }

    /**
     * http://stackoverflow.com/questions/27581750/android-capture-screen-to-surface-of-imagereader
     */
    private ImageReader.OnImageAvailableListener mImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.i(TAG, "image available");

            CaptureActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(CaptureActivity.this, "Saving picture", Toast.LENGTH_LONG).show();
                }
            });

            mImageReader.setOnImageAvailableListener(null, null);

            Image image = null;
            try {
                image = mImageReader.acquireLatestImage();

                if (image != null) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();

                    int width = image.getWidth();
                    int height = image.getHeight();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * width;


                    int offset = 0;
                    final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    for (int i = 0; i < height; ++i) {
                        for (int j = 0; j < width; ++j) {
                            int pixel = 0;
                            pixel |= (buffer.get(offset) & 0xff) << 16;     // R
                            pixel |= (buffer.get(offset + 1) & 0xff) << 8;  // G
                            pixel |= (buffer.get(offset + 2) & 0xff);       // B
                            pixel |= (buffer.get(offset + 3) & 0xff) << 24; // A
                            bitmap.setPixel(j, i, pixel);
                            offset += pixelStride;
                        }
                        offset += rowPadding;
                    }

                    CaptureActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showPreview(bitmap);
                        }
                    });


                    FileOutputStream fos = null;

                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMDD-HHmmss", Locale.ENGLISH);
                    String time = dateFormat.format(new Date());

                    try {
                        fos = new FileOutputStream(new File(repository, String.format("Screenshot_%s.png", time)));
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);

                        Log.i(TAG, "image captured");

                    } catch (FileNotFoundException e) {
                        Log.w(TAG, e.getMessage());
                    } finally {
                        if (fos != null) {
                            try {
                                fos.close();
                            } catch (IOException ignored) {
                            }
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

            mMediaProjection.stop();
        }
    };

    private MediaProjection.Callback mProjectionStopCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.i(TAG, "projection stopped");

            mWorkingHandler.post(new Runnable() {
                @Override
                public void run() {
                    mVirtualDisplay.release();
                }
            });
        }
    };
}
