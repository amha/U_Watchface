/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;


public class WeatherWatchface extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    private static final int MSG_UPDATE_TIME = 0;
    final String TAG = "AMHA-Data";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WeatherWatchface.Engine> mWeakReference;

        public EngineHandler(WeatherWatchface.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherWatchface.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(WeatherWatchface.this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        //Weather Icons
        Bitmap clear;
        Bitmap clouds;
        Bitmap fog;
        Bitmap lightClouds;
        Bitmap lightRain;
        Bitmap rain;
        Bitmap snow;
        Bitmap storm;

        // Styles
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mSmallTextPain;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        float imageYOffset;

        String city = "DEFAULT";
        String high = "HI";
        String low = "LOW";
        int weatherId = -1;

        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherWatchface.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = WeatherWatchface.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            // Getting Images
            clear = ((BitmapDrawable) resources.getDrawable(R.drawable.art_clear, null)).getBitmap();
            clouds = ((BitmapDrawable) resources.getDrawable(R.drawable.art_clouds, null)).getBitmap();
            fog = ((BitmapDrawable) resources.getDrawable(R.drawable.art_fog, null)).getBitmap();
            lightClouds = ((BitmapDrawable) resources.getDrawable(R.drawable.art_light_clouds, null)).getBitmap();
            lightRain = ((BitmapDrawable) resources.getDrawable(R.drawable.art_light_rain, null)).getBitmap();
            rain = ((BitmapDrawable) resources.getDrawable(R.drawable.art_rain, null)).getBitmap();
            snow = ((BitmapDrawable) resources.getDrawable(R.drawable.art_snow, null)).getBitmap();
            storm = ((BitmapDrawable) resources.getDrawable(R.drawable.art_storm, null)).getBitmap();

            // Setting styles
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(Color.rgb(33, 150, 243));
            mTextPaint = new Paint();
            mTextPaint.setTextSize(30f);
            mTextPaint = createTextPaint(Color.DKGRAY);
            mSmallTextPain = new Paint();
            mSmallTextPain = createTextPaint(Color.DKGRAY);
            mSmallTextPain.setTextSize(22f);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                registerReceiver();
            } else {
                unregisterReceiver();
            }
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mGoogleApiClient.connect();
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WeatherWatchface.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mGoogleApiClient.disconnect();
            mRegisteredTimeZoneReceiver = false;
            WeatherWatchface.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WeatherWatchface.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }
            updateTimer();
        }

        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            imageYOffset = mYOffset +30;

            // Draw time
            String text = mAmbient
                    ? String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE))
                    : String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));
            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);

            // Draw date
            String today = mCalendar.get(Calendar.MONTH) + ", " +
                    mCalendar.get(Calendar.DAY_OF_MONTH) + ", " +
                    mCalendar.get(Calendar.YEAR);
            canvas.drawText(today, mXOffset + 50, mYOffset + 30, mSmallTextPain);

            // Draw whether data
            canvas.drawText(city, mXOffset + 50, mYOffset + 60, mSmallTextPain);
            canvas.drawText("Hi: " + high, mXOffset + 50, mYOffset + 90, mSmallTextPain);
            canvas.drawText("Low: " + low, mXOffset + 140, mYOffset + 90, mSmallTextPain);

            if (weatherId >= 200 && weatherId <= 232) {
                canvas.drawBitmap(storm, mXOffset, imageYOffset, null);
            } else if (weatherId >= 300 && weatherId <= 321) {
                canvas.drawBitmap(lightRain, mXOffset, imageYOffset, null);
            } else if (weatherId >= 500 && weatherId <= 504) {
                canvas.drawBitmap(rain, mXOffset, imageYOffset, null);
            } else if (weatherId == 511) {
                canvas.drawBitmap(snow, mXOffset, imageYOffset, null);
            } else if (weatherId >= 520 && weatherId <= 531) {
                canvas.drawBitmap(rain, mXOffset, imageYOffset, null);
            } else if (weatherId >= 600 && weatherId <= 622) {
                canvas.drawBitmap(snow, mXOffset, imageYOffset, null);
            } else if (weatherId >= 701 && weatherId <= 761) {
                canvas.drawBitmap(fog, mXOffset, imageYOffset, null);
            } else if (weatherId == 761 || weatherId == 781) {
                canvas.drawBitmap(storm, mXOffset, imageYOffset, null);
            } else if (weatherId == 800) {
                canvas.drawBitmap(clear, mXOffset, imageYOffset, null);
            } else if (weatherId == 801) {
                canvas.drawBitmap(lightClouds, mXOffset, imageYOffset, null);
            } else if (weatherId >= 802 && weatherId <= 804) {
                canvas.drawBitmap(clouds, mXOffset, imageYOffset, null);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "Connected watch");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "Connected suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "CONNECTION FAILED");
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    Log.d(TAG, "DATA CHANGED");
                    if (item.getUri().toString().contains("/weather")) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        city = dataMap.getString("city");
                        high = dataMap.getString("high_temp");
                        low = dataMap.getString("low_temp");
                        weatherId = dataMap.getInt("id");
                    }
                }
            }
            dataEventBuffer.release();
            invalidate();
        }
    }
}
