package com.yieldnull.screenshot;

import android.annotation.TargetApi;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

@TargetApi(Build.VERSION_CODES.N)
public class TileService extends android.service.quicksettings.TileService {

    private static final String TAG = TileService.class.getSimpleName();

    public TileService() {
    }

    @Override
    public void onClick() {
        Log.i(TAG, "Tile clicked");

        startActivityAndCollapse(new Intent(this, CaptureActivity.class));
    }
}
