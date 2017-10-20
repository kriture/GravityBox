/*
 * Copyright (C) 2017 Peter Gregus for GravityBox Project (C3C076@xda)
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

package com.ceco.nougat.gravitybox.quicksettings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ceco.nougat.gravitybox.BroadcastSubReceiver;
import com.ceco.nougat.gravitybox.GravityBox;
import com.ceco.nougat.gravitybox.GravityBoxSettings;
import com.ceco.nougat.gravitybox.ModHwKeys;
import com.ceco.nougat.gravitybox.ModQsTiles;
import com.ceco.nougat.gravitybox.R;
import com.ceco.nougat.gravitybox.Utils;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class QsPanel implements BroadcastSubReceiver {
    private static final String TAG = "GB:QsPanel";
    private static final boolean DEBUG = false;

    public static final String CLASS_QS_PANEL = "com.android.systemui.qs.QSPanel";
    private static final String CLASS_BRIGHTNESS_CTRL = "com.android.systemui.settings.BrightnessController";
    private static final String CLASS_TILE_LAYOUT = "com.android.systemui.qs.TileLayout";
    private static final String CLASS_TILE_HOST = "com.android.systemui.statusbar.phone.QSTileHost";
    private static final String ACTION_SHOW_ADMIN_SUPPORT_DETAILS = "android.settings.SHOW_ADMIN_SUPPORT_DETAILS";

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private XSharedPreferences mPrefs;
    private ViewGroup mQsPanel;
    private int mNumColumns;
    private int mScaleCorrection;
    private View mBrightnessSlider;
    private boolean mHideBrightness;
    private boolean mBrightnessIconEnabled;
    private Integer mCellWidthOriginal;
    private QsTileEventDistributor mEventDistributor;
    @SuppressWarnings("unused")
    private QsQuickPulldownHandler mQuickPulldownHandler;
    private Map<String, BaseTile> mTiles = new HashMap<>();

    public QsPanel(XSharedPreferences prefs, ClassLoader classLoader) {
        mPrefs = prefs;

        initPreferences();
        createHooks(classLoader);

        if (DEBUG) log("QsPanel wrapper created");
    }

    private void initPreferences() {
        mNumColumns = Integer.valueOf(mPrefs.getString(
                GravityBoxSettings.PREF_KEY_QUICK_SETTINGS_TILES_PER_ROW, "0"));
        mScaleCorrection = mPrefs.getInt(GravityBoxSettings.PREF_KEY_QS_SCALE_CORRECTION, 0);
        mHideBrightness = mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_QUICK_SETTINGS_HIDE_BRIGHTNESS, false);
        mBrightnessIconEnabled = mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_QS_BRIGHTNESS_ICON, false);
        if (DEBUG) log("initPreferences: mNumColumns=" + mNumColumns +
                "; mHideBrightness=" + mHideBrightness +
                "; mBrightnessIconEnabled=" + mBrightnessIconEnabled);
    }

    @Override
    public void onBroadcastReceived(Context context, Intent intent) {
        if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_QUICKSETTINGS_CHANGED)) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QS_COLS)) {
                mNumColumns = intent.getIntExtra(GravityBoxSettings.EXTRA_QS_COLS, 0);
                updateLayout();
                if (DEBUG) log("onBroadcastReceived: mNumColumns=" + mNumColumns);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QS_SCALE_CORRECTION)) {
                mScaleCorrection = intent.getIntExtra(GravityBoxSettings.EXTRA_QS_SCALE_CORRECTION, 0);
                updateLayout();
                if (DEBUG) log("onBroadcastReceived: mScaleCorrection=" + mScaleCorrection);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QS_HIDE_BRIGHTNESS)) {
                mHideBrightness = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_QS_HIDE_BRIGHTNESS, false);
                updateResources();
                if (DEBUG) log("onBroadcastReceived: mHideBrightness=" + mHideBrightness);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QS_BRIGHTNESS_ICON)) {
                mBrightnessIconEnabled = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_QS_BRIGHTNESS_ICON, false);
                if (DEBUG) log("onBroadcastReceived: mBrightnessIconEnabled=" + mBrightnessIconEnabled);
            }
        } 
    }

    private void updateResources() {
        try {
            XposedHelpers.callMethod(mQsPanel, "updateResources");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void updateLayout() {
        updateResources();
        try {
            List<?> records = (List<?>) XposedHelpers.getObjectField(mQsPanel, "mRecords");
            for (Object record : records) {
                Object tileObj = XposedHelpers.getObjectField(record, "tile");
                String key = (String) XposedHelpers.getObjectField(tileObj, "mTileSpec");
                BaseTile tile = mTiles.get(key);
                if (tile != null) {
                    if (DEBUG) log("Updating layout for: " + key);
                    tile.updateTileViewLayout();
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    public float getScalingFactor() {
        float correction = (float)mScaleCorrection / 100f;
        switch (mNumColumns) {
            default:
            case 0: return 1f + correction;
            case 3: return 1f + correction;
            case 4: return 0.85f + correction;
            case 5: return 0.75f + correction;
            case 6: return 0.65f + correction;
        }
    }

    private View getBrightnessSlider() {
        if (mBrightnessSlider != null) return mBrightnessSlider;
        mBrightnessSlider = (ViewGroup)XposedHelpers.getObjectField(mQsPanel, "mBrightnessView");
        return mBrightnessSlider;
    }

    private void createHooks(final ClassLoader classLoader) {
        try {
            Class<?> classQsPanel = XposedHelpers.findClass(CLASS_QS_PANEL, classLoader);

            XposedBridge.hookAllConstructors(classQsPanel, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (CLASS_QS_PANEL.equals(param.thisObject.getClass().getName())) {
                        mQsPanel = (ViewGroup) param.thisObject;
                        if (DEBUG) log("QSPanel created");
                    }
                }
            });

            XposedHelpers.findAndHookMethod(QsPanel.CLASS_QS_PANEL, classLoader,
                    "setTiles", Collection.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!QsPanel.CLASS_QS_PANEL.equals(param.thisObject.getClass().getName()))
                            return;

                    mPrefs.reload();
                    Object host = XposedHelpers.getObjectField(param.thisObject, "mHost");

                    if (mEventDistributor == null) {
                        mEventDistributor = new QsTileEventDistributor(host, mPrefs);
                        mEventDistributor.setQsPanel(QsPanel.this);
                        mQuickPulldownHandler = new QsQuickPulldownHandler(
                                mQsPanel.getContext(), mPrefs, mEventDistributor);
                    }

                    Collection<?> tiles = (Collection<?>)param.args[0];

                    // destroy wrappers for removed tiles
                    for (String ourKey : new ArrayList<String>(mTiles.keySet())) {
                        boolean removed = true;
                        for (Object tile : tiles) {
                            String key = (String) XposedHelpers.getObjectField(tile, "mTileSpec");
                            if (key.equals(ourKey)) {
                                removed = false;
                                break;
                            }
                        }
                        if (removed) {
                            mTiles.get(ourKey).handleDestroy();
                            mTiles.remove(ourKey);
                            if (DEBUG) log("destroyed wrapper for: " + ourKey);
                        }
                    }

                    // prepare tile wrappers
                    for (Object tile : tiles) {
                        String key = (String) XposedHelpers.getObjectField(tile, "mTileSpec");
                        if (mTiles.containsKey(key)) {
                            mTiles.get(key).setTile(tile);
                            if (DEBUG) log("Updated tile reference for: " + key);
                            continue;
                        }
                        if (key.contains(GravityBox.PACKAGE_NAME)) {
                            if (DEBUG) log("Creating wrapper for custom tile: " + key);
                            QsTile gbTile = QsTile.create(host, key, tile,
                                mPrefs, mEventDistributor);
                            if (gbTile != null) {
                                mTiles.put(key, gbTile);
                            }
                        } else {
                            if (DEBUG) log("Creating wrapper for AOSP tile: " + key);
                            AospTile aospTile = AospTile.create(host, tile, 
                                    key, mPrefs, mEventDistributor);
                            mTiles.put(aospTile.getKey(), aospTile);
                        }
                    }
                    if (DEBUG) log("Tile wrappers created");
                }
            });

            XposedHelpers.findAndHookMethod(classQsPanel, "updateResources",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject == mQsPanel) {
                        updateBrightnessSliderVisibility();
                    }
                }
            });

            XposedHelpers.findAndHookMethod(classQsPanel, "onTuningChanged",
                    String.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject == mQsPanel && "qs_show_brightness".equals(param.args[0])) {
                        updateBrightnessSliderVisibility();
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            XposedHelpers.findAndHookMethod(CLASS_TILE_LAYOUT, classLoader, "updateResources",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (mCellWidthOriginal == null) {
                        mCellWidthOriginal = XposedHelpers.getIntField(param.thisObject, "mCellWidth");
                    } else {
                        XposedHelpers.setIntField(param.thisObject, "mCellWidth", mCellWidthOriginal);
                    }
                    // tiles per row
                    if (mNumColumns != 0) {
                        XposedHelpers.setIntField(param.thisObject, "mColumns", mNumColumns);
                        if (DEBUG) log("updateResources: Updated number of columns per row");
                        final float factor = getScalingFactor();
                        if (factor != 1f) {
                            int ch = XposedHelpers.getIntField(param.thisObject, "mCellHeight");
                            XposedHelpers.setIntField(param.thisObject, "mCellHeight", Math.round(ch*factor));
                            XposedHelpers.setIntField(param.thisObject, "mCellWidth",
                                    Math.round(mCellWidthOriginal*factor));
                            int cm = XposedHelpers.getIntField(param.thisObject, "mCellMargin");
                            XposedHelpers.setIntField(param.thisObject, "mCellMargin", Math.round(cm*factor));
                            int cmTop = XposedHelpers.getIntField(param.thisObject, "mCellMarginTop");
                            XposedHelpers.setIntField(param.thisObject, "mCellMarginTop", Math.round(cmTop*factor));
                            if (DEBUG) log("updateResources: scaling applied with factor=" + factor);
                        }
                        ((View)param.thisObject).requestLayout();
                        param.setResult(true);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            XposedHelpers.findAndHookMethod(CLASS_BRIGHTNESS_CTRL, classLoader,
                    "updateIcon", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (DEBUG) log("BrightnessController: updateIcon");
                    ImageView icon = (ImageView) XposedHelpers.getObjectField(param.thisObject, "mIcon");                      
                    if (icon != null) {
                        if (!icon.hasOnClickListeners()) {
                            icon.setOnClickListener(mBrightnessIconOnClick);
                            icon.setOnLongClickListener(mBrightnessIconOnLongClick);
                            icon.setBackground(Utils.getGbContext(
                                    icon.getContext()).getDrawable(
                                            R.drawable.ripple));
                        }
                        boolean automatic = XposedHelpers.getBooleanField(param.thisObject, "mAutomatic");
                        int resId = icon.getResources().getIdentifier(
                                (automatic ? "ic_qs_brightness_auto_on" : "ic_qs_brightness_auto_off"),
                                "drawable", ModQsTiles.PACKAGE_NAME);
                        if (resId != 0) {
                            icon.setImageResource(resId);
                        }
                        icon.setVisibility(mBrightnessIconEnabled ? View.VISIBLE : View.GONE);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        // Disable info icon for protected tiles
        try {
            XposedHelpers.findAndHookMethod(BaseTile.CLASS_TILE_VIEW, classLoader,
                    "handleStateChanged", BaseTile.CLASS_TILE_STATE, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    View padlock = (View) XposedHelpers.getObjectField(param.thisObject, "mPadLock");
                    if (padlock != null) {
                        padlock.setVisibility(View.GONE);
                    }
                }
            });
        } catch (Throwable t) {
            if (DEBUG) XposedBridge.log(t);
        }

        // Disable restricted by admin dialog
        try {
            XposedHelpers.findAndHookMethod(CLASS_TILE_HOST, classLoader,
                    "startActivityDismissingKeyguard", Intent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (ACTION_SHOW_ADMIN_SUPPORT_DETAILS.equals(
                            ((Intent)param.args[0]).getAction())) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable t) {
            if (DEBUG) XposedBridge.log(t);
        }
    }

    private void updateBrightnessSliderVisibility() {
        View bs = getBrightnessSlider();
        if (bs != null) {
            final int vis = mHideBrightness ? View.GONE : View.VISIBLE; 
            if (bs.getVisibility() != vis) {
                bs.setVisibility(vis);
                mQsPanel.postInvalidate();
            }
        }
    }

    private View.OnClickListener mBrightnessIconOnClick = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            Intent intent = new Intent(ModHwKeys.ACTION_TOGGLE_AUTO_BRIGHTNESS);
            v.getContext().sendBroadcast(intent);
        }
    };

    private View.OnLongClickListener mBrightnessIconOnLongClick = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            try {
                Intent intent = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
                Object host = XposedHelpers.getObjectField(mQsPanel, "mHost");
                XposedHelpers.callMethod(host, "startActivityDismissingKeyguard", intent);
                return true;
            } catch (Throwable t) {
                XposedBridge.log(t);
                return false;
            }
        }
    };
}
