/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2010-2014 CyanogenMod Project
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

package com.android.internal.policy.impl;

import com.android.internal.app.AlertController;
import com.android.internal.app.AlertController.AlertParams;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Profile;
import android.app.ProfileManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ThemeUtils;
import android.content.pm.UserInfo;
import android.content.res.TypedArray;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerInternal;
import android.view.WindowManagerPolicy.WindowManagerFuncs;
import android.view.accessibility.AccessibilityEvent;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper to show the global actions dialog.  Each item is an {@link Action} that
 * may show depending on whether the keyguard is showing, and whether the device
 * is provisioned.
 */
class GlobalActions implements DialogInterface.OnDismissListener, DialogInterface.OnClickListener  {

    private static final String TAG = "GlobalActions";

    private static final boolean SHOW_SILENT_TOGGLE = true;

    private static final String REBOOT = null;

    /* Valid settings for global actions keys.
     * see config.xml config_globalActionList */
    private static final String GLOBAL_ACTION_KEY_POWER = "power";
    private static final String GLOBAL_ACTION_KEY_REBOOT = "reboot";
    private static final String GLOBAL_ACTION_KEY_AIRPLANE = "airplane";
    private static final String GLOBAL_ACTION_KEY_BUGREPORT = "bugreport";
    private static final String GLOBAL_ACTION_KEY_SILENT = "silent";
    private static final String GLOBAL_ACTION_KEY_USERS = "users";
    private static final String GLOBAL_ACTION_KEY_SETTINGS = "settings";
    private static final String GLOBAL_ACTION_KEY_LOCKDOWN = "lockdown";
    private static final String GLOBAL_ACTION_KEY_SCREENSHOT = "screenshot";

    private final Context mContext;
    private final WindowManagerFuncs mWindowManagerFuncs;
    private Context mUiContext;
    private final AudioManager mAudioManager;
    private final IDreamManager mDreamManager;

    private ArrayList<Action> mItems;
    private GlobalActionsDialog mDialog;

    private Action mSilentModeAction;
    private ToggleAction mAirplaneModeOn;

    private MyAdapter mAdapter;

    private boolean mKeyguardShowing = false;
    private boolean mKeyguardSecure = false;
    private boolean mDeviceProvisioned = false;
    private ToggleAction.State mAirplaneState = ToggleAction.State.Off;
    private boolean mIsWaitingForEcmExit = false;
    private boolean mHasTelephony;
    private boolean mHasVibrator;
    private final boolean mShowSilentToggle;
    private boolean mRebootMenu;
    private Profile mChosenProfile;

    /**
     * @param context everything needs a context :(
     */
    public GlobalActions(Context context, WindowManagerFuncs windowManagerFuncs) {
        mContext = context;
        mWindowManagerFuncs = windowManagerFuncs;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.getService(DreamService.DREAM_SERVICE));

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        context.registerReceiver(mBroadcastReceiver, filter);

        ThemeUtils.registerThemeChangeReceiver(context, mThemeChangeReceiver);

        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mHasTelephony = cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        // get notified of phone state changes
        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), true,
                mAirplaneModeObserver);
        Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mHasVibrator = vibrator != null && vibrator.hasVibrator();

        mShowSilentToggle = SHOW_SILENT_TOGGLE && !mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_useFixedVolume);
    }

    /**
     * Show the global actions dialog (creating if necessary)
     * @param keyguardShowing True if keyguard is showing
     */
    public void showDialog(boolean keyguardShowing, boolean isKeyguardSecure,
                boolean isDeviceProvisioned) {
        showDialog(keyguardShowing, isKeyguardSecure, isDeviceProvisioned, false);
    }

    public void showDialog(boolean keyguardShowing, boolean isKeyguardSecure,
            boolean isDeviceProvisioned, boolean isRebootSubMenu) {
        mKeyguardShowing = keyguardShowing;
        mKeyguardSecure = isKeyguardSecure;
        mDeviceProvisioned = isDeviceProvisioned;
        mRebootMenu = isRebootSubMenu;
        if (mDialog != null && mUiContext == null) {
            mDialog.dismiss();
            mDialog = null;
            // Show delayed, so that the dismiss of the previous dialog completes
            mHandler.sendEmptyMessage(MESSAGE_SHOW);
        } else {
            handleShow();
        }
    }

    private void awakenIfNecessary() {
        if (mDreamManager != null) {
            try {
                if (mDreamManager.isDreaming()) {
                    mDreamManager.awaken();
                }
            } catch (RemoteException e) {
                // we tried
            }
        }
    }

    private void handleShow() {
        awakenIfNecessary();
        mDialog = createDialog();
        prepareDialog();

        // If we only have 1 item and it's a simple press action, just do this action.
        if (mAdapter.getCount() == 1
                && mAdapter.getItem(0) instanceof SinglePressAction
                && !(mAdapter.getItem(0) instanceof LongPressAction)) {
            ((SinglePressAction) mAdapter.getItem(0)).onPress();
        } else {
            WindowManager.LayoutParams attrs = mDialog.getWindow().getAttributes();
            attrs.setTitle("GlobalActions");
            mDialog.getWindow().setAttributes(attrs);
            mDialog.show();
            mDialog.getWindow().getDecorView().setSystemUiVisibility(View.STATUS_BAR_DISABLE_EXPAND);
        }
    }

    private Context getUiContext() {
        if (mUiContext == null) {
            mUiContext = ThemeUtils.createUiContext(mContext);
            mUiContext.setTheme(android.R.style.Theme_DeviceDefault_Light_DarkActionBar);
        }
        return mUiContext != null ? mUiContext : mContext;
    }

    /**
     * Create the global actions dialog.
     * @return A new dialog.
     */
    private GlobalActionsDialog createDialog() {
        if (mRebootMenu) {
            createRebootMenuItems();
        } else {
            // Simple toggle style if there's no vibrator, otherwise use a tri-state
        mSilentModeAction = new SilentModeTriStateAction(mContext, mAudioManager, mHandler);
            mAirplaneModeOn = new ToggleAction(
                    R.drawable.ic_lock_airplane_mode,
                    R.drawable.ic_lock_airplane_mode_off,
                    R.string.global_actions_toggle_airplane_mode,
                    R.string.global_actions_airplane_mode_on_status,
                    R.string.global_actions_airplane_mode_off_status) {

                void onToggle(boolean on) {
                    if (mHasTelephony && Boolean.parseBoolean(
                            SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE))) {
                        mIsWaitingForEcmExit = true;
                        // Launch ECM exit dialog
                        Intent ecmDialogIntent =
                                new Intent(TelephonyIntents.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS, null);
                        ecmDialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(ecmDialogIntent);
                    } else {
                        changeAirplaneModeSystemSetting(on);
                    }
                }

                @Override
                protected void changeStateFromPress(boolean buttonOn) {
                    if (!mHasTelephony) return;

                    // In ECM mode airplane state cannot be changed
                    if (!(Boolean.parseBoolean(
                            SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE)))) {
                        mState = buttonOn ? State.TurningOn : State.TurningOff;
                        mAirplaneState = mState;
                    }
                }

                public boolean showDuringKeyguard() {
                    return true;
                }

                public boolean showBeforeProvisioning() {
                    return false;
                }
            };
            onAirplaneModeChanged();

            mItems = new ArrayList<Action>();
            String[] defaultActions = mContext.getResources().getStringArray(
                    com.android.internal.R.array.config_globalActionsList);

            ArraySet<String> addedKeys = new ArraySet<String>();
            for (int i = 0; i < defaultActions.length; i++) {
                String actionKey = defaultActions[i];
                if (addedKeys.contains(actionKey)) {
                    // If we already have added this, don't add it again.
                    continue;
                }
                if (GLOBAL_ACTION_KEY_POWER.equals(actionKey)) {
                    mItems.add(new PowerAction());
                } else if (GLOBAL_ACTION_KEY_REBOOT.equals(actionKey)) {
                    mItems.add(new RebootAction());
                } else if (GLOBAL_ACTION_KEY_SCREENSHOT.equals(actionKey)) {
                    mItems.add(new ScreenShotAction());
                } else if (GLOBAL_ACTION_KEY_AIRPLANE.equals(actionKey)) {
                    mItems.add(mAirplaneModeOn);
                } else if (GLOBAL_ACTION_KEY_BUGREPORT.equals(actionKey)) {
                    if (Settings.Global.getInt(mContext.getContentResolver(),
                            Settings.Global.BUGREPORT_IN_POWER_MENU, 0) != 0 && isCurrentUserOwner()) {
                        mItems.add(getBugReportAction());
                    }
                } else if (GLOBAL_ACTION_KEY_SILENT.equals(actionKey)) {
                    if (mShowSilentToggle) {
                        mItems.add(mSilentModeAction);
                    }
                } else if (GLOBAL_ACTION_KEY_USERS.equals(actionKey)) {
                    if (SystemProperties.getBoolean("fw.power_user_switcher", false)) {
                        addUsersToMenu(mItems);
                    }
                } else if (GLOBAL_ACTION_KEY_SETTINGS.equals(actionKey)) {
                    mItems.add(getSettingsAction());
                } else if (GLOBAL_ACTION_KEY_LOCKDOWN.equals(actionKey)) {
                    mItems.add(getLockdownAction());
                } else {
                    Log.e(TAG, "Invalid global action key " + actionKey);
                }
                // Add here so we don't add more than one.
                addedKeys.add(actionKey);
            }
        }

        mAdapter = new MyAdapter();

        AlertParams params = new AlertParams(getUiContext());
        params.mAdapter = mAdapter;
        params.mOnClickListener = this;
        params.mForceInverseBackground = true;

        GlobalActionsDialog dialog = new GlobalActionsDialog(getUiContext(), params);
        dialog.setCanceledOnTouchOutside(false); // Handled by the custom class.

        dialog.getListView().setItemsCanFocus(true);
        dialog.getListView().setLongClickable(true);
        dialog.getListView().setOnItemLongClickListener(
                new AdapterView.OnItemLongClickListener() {
                    @Override
                    public boolean onItemLongClick(AdapterView<?> parent, View view, int position,
                            long id) {
                        final Action action = mAdapter.getItem(position);
                        if (action instanceof LongPressAction) {
                            return ((LongPressAction) action).onLongPress();
                        }
                        return false;
                    }
        });
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);

        dialog.setOnDismissListener(this);

        return dialog;
    }

    private final class PowerAction extends SinglePressAction implements LongPressAction {
        private PowerAction() {
            super(com.android.internal.R.drawable.ic_lock_power_off,
                R.string.global_action_power_off);
        }

        @Override
        public boolean onLongPress() {
            mWindowManagerFuncs.rebootSafeMode(true);
            return true;
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            final boolean quickbootEnabled = Settings.System.getInt(
                    mContext.getContentResolver(), "enable_quickboot", 0) == 1;
            // go to quickboot mode if enabled
            if (quickbootEnabled) {
                startQuickBoot();
                return;
            }
            // shutdown by making sure radio and power are handled accordingly.
            mWindowManagerFuncs.shutdown(true /* confirm */);
        }
    }

    private final class RebootAction extends SinglePressAction {
        private RebootAction() {
            super(com.android.internal.R.drawable.ic_lock_power_reboot,
                  R.string.global_action_reboot);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            if ((mKeyguardShowing && mKeyguardSecure) || !isAdvancedRebootEnabled()) {
                try {
                    IPowerManager pm = IPowerManager.Stub.asInterface(ServiceManager
                            .getService(Context.POWER_SERVICE));
                    pm.reboot(true /*confirm*/, REBOOT, false);
                } catch (RemoteException e) {
                    Log.e(TAG, "PowerManager service died!", e);
                    return;
                }
            } else {
                showDialog(mKeyguardShowing, mKeyguardSecure, mDeviceProvisioned, true);
            }
        }
    }

    private final class ScreenShotAction extends SinglePressAction {
        private ScreenShotAction(){
            super(com.android.internal.R.drawable.ic_lock_screenshot,
                    R.string.global_action_screenshot);
        }

        public void onPress() {
            takeScreenshot();
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return true;
        }
    }

    final Object mScreenshotLock = new Object();
    ServiceConnection mScreenshotConnection = null;

    final Runnable mScreenshotTimeout = new Runnable() {
        @Override public void run() {
            synchronized (mScreenshotLock) {
                if (mScreenshotConnection != null) {
                    mContext.unbindService(mScreenshotConnection);
                    mScreenshotConnection = null;
                }
            }
        }
    };

    private void takeScreenshot() {
        synchronized (mScreenshotLock) {
            if (mScreenshotConnection != null) {
                return;
            }
            ComponentName cn = new ComponentName("com.android.systemui",
                    "com.android.systemui.screenshot.TakeScreenshotService");
            Intent intent = new Intent();
            intent.setComponent(cn);
            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    synchronized (mScreenshotLock) {
                        if (mScreenshotConnection != this) {
                            return;
                        }
                        Messenger messenger = new Messenger(service);
                        Message msg = Message.obtain(null, 1);
                        final ServiceConnection myConn = this;
                        Handler h = new Handler(mHandler.getLooper()) {
                            @Override
                            public void handleMessage(Message msg) {
                                synchronized (mScreenshotLock) {
                                    if (mScreenshotConnection == myConn) {
                                        mContext.unbindService(mScreenshotConnection);
                                        mScreenshotConnection = null;
                                        mHandler.removeCallbacks(mScreenshotTimeout);
                                    }
                                }
                            }
                        };
                        msg.replyTo = new Messenger(h);
                        msg.arg1 = msg.arg2 = 0;

                        /* wait for the dialog box to close */
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                        }

                        /* take the screenshot */
                        try {
                            messenger.send(msg);
                        } catch (RemoteException e) {
                        }
                    }
                }
                @Override
                public void onServiceDisconnected(ComponentName name) {}
            };
            if (mContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
                mScreenshotConnection = conn;
                mHandler.postDelayed(mScreenshotTimeout, 10000);
            }
        }
    }

    private Action getBugReportAction() {
        return new SinglePressAction(com.android.internal.R.drawable.ic_lock_bugreport,
                R.string.bugreport_title) {

            public void onPress() {
                AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                builder.setTitle(com.android.internal.R.string.bugreport_title);
                builder.setMessage(com.android.internal.R.string.bugreport_message);
                builder.setNegativeButton(com.android.internal.R.string.cancel, null);
                builder.setPositiveButton(com.android.internal.R.string.report,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // don't actually trigger the bugreport if we are running stability
                                // tests via monkey
                                if (ActivityManager.isUserAMonkey()) {
                                    return;
                                }
                                // Add a little delay before executing, to give the
                                // dialog a chance to go away before it takes a
                                // screenshot.
                                mHandler.postDelayed(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            ActivityManagerNative.getDefault()
                                                    .requestBugReport();
                                        } catch (RemoteException e) {
                                        }
                                    }
                                }, 500);
                            }
                        });
                AlertDialog dialog = builder.create();
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
                dialog.show();
            }

            public boolean showDuringKeyguard() {
                return true;
            }

            public boolean showBeforeProvisioning() {
                return false;
            }

            @Override
            public String getStatus() {
                return mContext.getString(
                        com.android.internal.R.string.bugreport_status,
                        Build.VERSION.RELEASE,
                        Build.ID);
            }
        };
    }

    private Action getSettingsAction() {
        return new SinglePressAction(com.android.internal.R.drawable.ic_settings,
                R.string.global_action_settings) {

            @Override
            public void onPress() {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getLockdownAction() {
        return new SinglePressAction(com.android.internal.R.drawable.ic_lock_lock,
                R.string.global_action_lockdown) {

            @Override
            public void onPress() {
                new LockPatternUtils(mContext).requireCredentialEntry(UserHandle.USER_ALL);
                try {
                    WindowManagerGlobal.getWindowManagerService().lockNow(null);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error while trying to lock device.", e);
                }
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return false;
            }
        };
    }

    private UserInfo getCurrentUser() {
        try {
            return ActivityManagerNative.getDefault().getCurrentUser();
        } catch (RemoteException re) {
            return null;
        }
    }

    private boolean isCurrentUserOwner() {
        UserInfo currentUser = getCurrentUser();
        return currentUser == null || currentUser.isPrimary();
    }

    private void addUsersToMenu(ArrayList<Action> items) {
        UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        if (um.isUserSwitcherEnabled()) {
            List<UserInfo> users = um.getUsers();
            UserInfo currentUser = getCurrentUser();
            for (final UserInfo user : users) {
                if (user.supportsSwitchTo()) {
                    boolean isCurrentUser = currentUser == null
                            ? user.id == 0 : (currentUser.id == user.id);
                    Drawable icon = user.iconPath != null ? Drawable.createFromPath(user.iconPath)
                            : null;
                    SinglePressAction switchToUser = new SinglePressAction(
                            com.android.internal.R.drawable.ic_menu_cc, icon,
                            (user.name != null ? user.name : "Primary")
                            + (isCurrentUser ? " \u2714" : "")) {
                        public void onPress() {
                            try {
                                ActivityManagerNative.getDefault().switchUser(user.id);
                            } catch (RemoteException re) {
                                Log.e(TAG, "Couldn't switch user " + re);
                            }
                        }

                        public boolean showDuringKeyguard() {
                            return true;
                        }

                        public boolean showBeforeProvisioning() {
                            return false;
                        }
                    };
                    items.add(switchToUser);
                }
            }
        }
    }

    private boolean isAdvancedRebootEnabled() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ADVANCED_REBOOT, 0) != 0;
    }

    private void createRebootMenuItems() {
        mItems = new ArrayList<Action>();

        TypedArray ar = mContext.getResources().obtainTypedArray(R.array
                .shutdown_reboot_icons);
        int[] iconIds = new int[ar.length()];
        for (int i = 0; i < iconIds.length; i++) {
            iconIds[i] = ar.getResourceId(i, 0);
        }
        ar.recycle();

        String[] names = mContext.getResources().getStringArray(R.array
                .shutdown_reboot_options);
        String[] actions = mContext.getResources().getStringArray(R.array
                .shutdown_reboot_actions);

        for(int i = 0; i < names.length; i++) {
            addRebootItem(iconIds[i], names[i], actions[i]);
        }
    }

    private void addRebootItem(int drawable, String name, final String action) {
        mItems.add(
            new SinglePressAction(
                    drawable,
                    name) {

                public void onPress() {
                    try {
                        IPowerManager pm = IPowerManager.Stub.asInterface(ServiceManager
                                .getService(Context.POWER_SERVICE));
                        pm.reboot(true, action, false); // action specify extra action, null for normal reboot
                    } catch (RemoteException e) {
                        Log.e(TAG, "PowerManager service died!", e);
                        return;
                    }
                }

                public boolean showDuringKeyguard() {
                    return true;
                }

                public boolean showBeforeProvisioning() {
                    return true;
                }
            }
        );
    }

    private void prepareDialog() {
        mAirplaneModeOn.updateState(mAirplaneState);
        mAdapter.notifyDataSetChanged();
        mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        if (mShowSilentToggle) {
            IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
            mContext.registerReceiver(mRingerModeReceiver, filter);
        }
    }

    /** {@inheritDoc} */
    public void onDismiss(DialogInterface dialog) {
        if (mShowSilentToggle) {
            try {
                mContext.unregisterReceiver(mRingerModeReceiver);
            } catch (IllegalArgumentException ie) {
                // ignore this
                Log.w(TAG, ie);
            }
        }
    }

    /** {@inheritDoc} */
    public void onClick(DialogInterface dialog, int which) {
        mAdapter.getItem(which).onPress();
    }

    /**
     * The adapter used for the list within the global actions dialog, taking
     * into account whether the keyguard is showing via
     * {@link GlobalActions#mKeyguardShowing} and whether the device is provisioned
     * via {@link GlobalActions#mDeviceProvisioned}.
     */
    private class MyAdapter extends BaseAdapter {

        public int getCount() {
            int count = 0;

            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);

                if (mKeyguardShowing && !action.showDuringKeyguard()) {
                    continue;
                }
                if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
                    continue;
                }
                count++;
            }
            return count;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItem(position).isEnabled();
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        public Action getItem(int position) {

            int filteredPos = 0;
            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);
                if (mKeyguardShowing && !action.showDuringKeyguard()) {
                    continue;
                }
                if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
                    continue;
                }
                if (filteredPos == position) {
                    return action;
                }
                filteredPos++;
            }

            throw new IllegalArgumentException("position " + position
                    + " out of range of showable actions"
                    + ", filtered count=" + getCount()
                    + ", keyguardshowing=" + mKeyguardShowing
                    + ", provisioned=" + mDeviceProvisioned);
        }


        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            final Context context = getUiContext();
            return action.create(context, convertView, parent, LayoutInflater.from(context));
        }
    }

    // note: the scheme below made more sense when we were planning on having
    // 8 different things in the global actions dialog.  seems overkill with
    // only 3 items now, but may as well keep this flexible approach so it will
    // be easy should someone decide at the last minute to include something
    // else, such as 'enable wifi', or 'enable bluetooth'

    /**
     * What each item in the global actions dialog must be able to support.
     */
    private interface Action {
        /**
         * @return Text that will be announced when dialog is created.  null
         *     for none.
         */
        CharSequence getLabelForAccessibility(Context context);

        View create(Context context, View convertView, ViewGroup parent, LayoutInflater inflater);

        void onPress();

        /**
         * @return whether this action should appear in the dialog when the keygaurd
         *    is showing.
         */
        boolean showDuringKeyguard();

        /**
         * @return whether this action should appear in the dialog before the
         *   device is provisioned.
         */
        boolean showBeforeProvisioning();

        boolean isEnabled();
    }

    /**
     * An action that also supports long press.
     */
    private interface LongPressAction extends Action {
        boolean onLongPress();
    }

    /**
     * A single press action maintains no state, just responds to a press
     * and takes an action.
     */
    private static abstract class SinglePressAction implements Action {
        private final int mIconResId;
        private final Drawable mIcon;
        private final int mMessageResId;
        private final CharSequence mMessage;

        protected SinglePressAction(int iconResId, int messageResId) {
            mIconResId = iconResId;
            mMessageResId = messageResId;
            mMessage = null;
            mIcon = null;
        }

        protected SinglePressAction(int iconResId, Drawable icon, CharSequence message) {
            mIconResId = iconResId;
            mMessageResId = 0;
            mMessage = message;
            mIcon = icon;
        }

        protected SinglePressAction(int iconResId, CharSequence message) {
            mIconResId = iconResId;
            mMessageResId = 0;
            mMessage = message;
            mIcon = null;
        }

        public boolean isEnabled() {
            return true;
        }

        public String getStatus() {
            return null;
        }

        abstract public void onPress();

        public CharSequence getLabelForAccessibility(Context context) {
            if (mMessage != null) {
                return mMessage;
            } else {
                return context.getString(mMessageResId);
            }
        }

        public View create(
                Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
            View v = inflater.inflate(R.layout.global_actions_item, parent, false);

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);

            TextView statusView = (TextView) v.findViewById(R.id.status);
            final String status = getStatus();
            if (!TextUtils.isEmpty(status)) {
                statusView.setText(status);
            } else {
                statusView.setVisibility(View.GONE);
            }
            if (mIcon != null) {
                icon.setImageDrawable(mIcon);
                icon.setScaleType(ScaleType.CENTER_CROP);
            } else if (mIconResId != 0) {
                icon.setImageDrawable(context.getDrawable(mIconResId));
            }
            if (mMessage != null) {
                messageView.setText(mMessage);
            } else {
                messageView.setText(mMessageResId);
            }

            return v;
        }
    }

    /**
     * A toggle action knows whether it is on or off, and displays an icon
     * and status message accordingly.
     */
    private static abstract class ToggleAction implements Action {

        enum State {
            Off(false),
            TurningOn(true),
            TurningOff(true),
            On(false);

            private final boolean inTransition;

            State(boolean intermediate) {
                inTransition = intermediate;
            }

            public boolean inTransition() {
                return inTransition;
            }
        }

        protected State mState = State.Off;

        // prefs
        protected int mEnabledIconResId;
        protected int mDisabledIconResid;
        protected int mMessageResId;
        protected int mEnabledStatusMessageResId;
        protected int mDisabledStatusMessageResId;

        /**
         * @param enabledIconResId The icon for when this action is on.
         * @param disabledIconResid The icon for when this action is off.
         * @param essage The general information message, e.g 'Silent Mode'
         * @param enabledStatusMessageResId The on status message, e.g 'sound disabled'
         * @param disabledStatusMessageResId The off status message, e.g. 'sound enabled'
         */
        public ToggleAction(int enabledIconResId,
                int disabledIconResid,
                int message,
                int enabledStatusMessageResId,
                int disabledStatusMessageResId) {
            mEnabledIconResId = enabledIconResId;
            mDisabledIconResid = disabledIconResid;
            mMessageResId = message;
            mEnabledStatusMessageResId = enabledStatusMessageResId;
            mDisabledStatusMessageResId = disabledStatusMessageResId;
        }

        /**
         * Override to make changes to resource IDs just before creating the
         * View.
         */
        void willCreate() {

        }

        @Override
        public CharSequence getLabelForAccessibility(Context context) {
            return context.getString(mMessageResId);
        }

        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            willCreate();

            View v = inflater.inflate(R
                            .layout.global_actions_item, parent, false);

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);
            TextView statusView = (TextView) v.findViewById(R.id.status);
            final boolean enabled = isEnabled();

            if (messageView != null) {
                messageView.setText(mMessageResId);
                messageView.setEnabled(enabled);
            }

            boolean on = ((mState == State.On) || (mState == State.TurningOn));
            if (icon != null) {
                icon.setImageDrawable(context.getDrawable(
                        (on ? mEnabledIconResId : mDisabledIconResid)));
                icon.setEnabled(enabled);
            }

            if (statusView != null) {
                statusView.setText(on ? mEnabledStatusMessageResId : mDisabledStatusMessageResId);
                statusView.setVisibility(View.VISIBLE);
                statusView.setEnabled(enabled);
            }
            v.setEnabled(enabled);

            return v;
        }

        public final void onPress() {
            if (mState.inTransition()) {
                Log.w(TAG, "shouldn't be able to toggle when in transition");
                return;
            }

            final boolean nowOn = !(mState == State.On);
            onToggle(nowOn);
            changeStateFromPress(nowOn);
        }

        public boolean isEnabled() {
            return !mState.inTransition();
        }

        /**
         * Implementations may override this if their state can be in on of the intermediate
         * states until some notification is received (e.g airplane mode is 'turning off' until
         * we know the wireless connections are back online
         * @param buttonOn Whether the button was turned on or off
         */
        protected void changeStateFromPress(boolean buttonOn) {
            mState = buttonOn ? State.On : State.Off;
        }

        abstract void onToggle(boolean on);

        public void updateState(State state) {
            mState = state;
        }
    }

    private final class SilentModeToggleAction extends ToggleAction {
        public SilentModeToggleAction() {
            super(R.drawable.ic_audio_vol_mute,
                    R.drawable.ic_audio_vol,
                    R.string.global_action_toggle_silent_mode,
                    R.string.global_action_silent_mode_on_status,
                    R.string.global_action_silent_mode_off_status);
        }

        void onToggle(boolean on) {
            if (on) {
                mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_SILENT);
            } else {
                mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
            }
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }
    }

    private final class SilentModeTriStateAction implements Action, View.OnClickListener {

        private final int[] ITEM_IDS = { R.id.option1, R.id.option2, R.id.option3, R.id.option4, R.id.option5 };

        private final AudioManager mAudioManager;
        private final Handler mHandler;
        private final Context mContext;

        SilentModeTriStateAction(Context context, AudioManager audioManager, Handler handler) {
            mAudioManager = audioManager;
            mHandler = handler;
            mContext = context;
        }

        private int indexToRingerMode(int index) {
            if (index == 2) {
                return AudioManager.RINGER_MODE_SILENT;
            }
            if (index == 3) {
                if (mHasVibrator) {
                    return AudioManager.RINGER_MODE_VIBRATE;
                } else {
                    return AudioManager.RINGER_MODE_NORMAL;
                }
            }
            return AudioManager.RINGER_MODE_NORMAL;
        }

        @Override
        public CharSequence getLabelForAccessibility(Context context) {
            return null;
        }

        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            View v = inflater.inflate(R.layout.global_actions_silent_mode, parent, false);

            int ringerMode = mAudioManager.getRingerModeInternal();
            int zenMode = Global.getInt(mContext.getContentResolver(), Global.ZEN_MODE, Global.ZEN_MODE_OFF);
            int selectedIndex = 0;
            if (zenMode != Global.ZEN_MODE_OFF) {
                if (zenMode == Global.ZEN_MODE_NO_INTERRUPTIONS) {
                    selectedIndex = 0;
                } else if (zenMode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
                    selectedIndex = 1;
                }
            } else if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                selectedIndex = 2;
            } else if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                selectedIndex = 3;
            } else if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                selectedIndex = 4;
            }

            for (int i = 0; i < ITEM_IDS.length; i++) {
                View itemView = v.findViewById(ITEM_IDS[i]);
                // TODO dont hardcode 3 here
                if (!mHasVibrator && i == 3) {
                    itemView.setVisibility(View.GONE);
                    continue;
                }
                itemView.setSelected(selectedIndex == i);
                // Set up click handler
                itemView.setTag(i);
                itemView.setOnClickListener(this);
            }
            return v;
        }

        public void onPress() {
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }

        public boolean isEnabled() {
            return true;
        }

        void willCreate() {
        }

        public void onClick(View v) {
            if (!(v.getTag() instanceof Integer)) return;

            int index = (Integer) v.getTag();
            if (index == 0 || index == 1) {
                int zenMode = index == 0
                            ? Global.ZEN_MODE_NO_INTERRUPTIONS
                            : Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
                Global.putInt(mContext.getContentResolver(), Global.ZEN_MODE, zenMode);
            } else {
                Global.putInt(mContext.getContentResolver(), Global.ZEN_MODE, Global.ZEN_MODE_OFF);
            }

            // must be after zen mode!
            if (index == 2 || index == 3 || index == 4) {
                int ringerMode = indexToRingerMode(index);
                mAudioManager.setRingerModeInternal(ringerMode);
            }
            mAdapter.notifyDataSetChanged();
            mHandler.sendEmptyMessageDelayed(MESSAGE_DISMISS, DIALOG_DISMISS_DELAY);
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                String reason = intent.getStringExtra(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY);
                if (!PhoneWindowManager.SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS.equals(reason)) {
                    mHandler.sendEmptyMessage(MESSAGE_DISMISS);
                }
            } else if (TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED.equals(action)) {
                // Airplane mode can be changed after ECM exits if airplane toggle button
                // is pressed during ECM mode
                if (!(intent.getBooleanExtra("PHONE_IN_ECM_STATE", false)) &&
                        mIsWaitingForEcmExit) {
                    mIsWaitingForEcmExit = false;
                    changeAirplaneModeSystemSetting(true);
                }
            }
        }
    };

    private BroadcastReceiver mThemeChangeReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            mUiContext = null;
        }
    };

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            if (!mHasTelephony || mAirplaneModeOn == null || mAdapter == null) return;
            final boolean inAirplaneMode = serviceState.getState() == ServiceState.STATE_POWER_OFF;
            mAirplaneState = inAirplaneMode ? ToggleAction.State.On : ToggleAction.State.Off;
            mAirplaneModeOn.updateState(mAirplaneState);
            mAdapter.notifyDataSetChanged();
        }
    };

    private BroadcastReceiver mRingerModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                mHandler.sendEmptyMessage(MESSAGE_REFRESH);
            }
        }
    };

    private ContentObserver mAirplaneModeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            onAirplaneModeChanged();
        }
    };

    private static final int MESSAGE_DISMISS = 0;
    private static final int MESSAGE_REFRESH = 1;
    private static final int MESSAGE_SHOW = 2;
    private static final int DIALOG_DISMISS_DELAY = 300; // ms

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_DISMISS:
                if (mDialog != null) {
                    mDialog.dismiss();
                    mDialog = null;
                }
                break;
            case MESSAGE_REFRESH:
                mAdapter.notifyDataSetChanged();
                break;
            case MESSAGE_SHOW:
                handleShow();
                break;
            }
        }
    };

    private void onAirplaneModeChanged() {
        // Let the service state callbacks handle the state.
        if (mHasTelephony) return;

        boolean airplaneModeOn = Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON,
                0) == 1;
        mAirplaneState = airplaneModeOn ? ToggleAction.State.On : ToggleAction.State.Off;
        mAirplaneModeOn.updateState(mAirplaneState);
    }

    /**
     * Change the airplane mode system setting
     */
    private void changeAirplaneModeSystemSetting(boolean on) {
        Settings.Global.putInt(
                mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON,
                on ? 1 : 0);
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("state", on);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        if (!mHasTelephony) {
            mAirplaneState = on ? ToggleAction.State.On : ToggleAction.State.Off;
        }
    }

    private void startQuickBoot() {

        Intent intent = new Intent("org.codeaurora.action.QUICKBOOT");
        intent.putExtra("mode", 0);
        try {
            mContext.startActivityAsUser(intent,UserHandle.CURRENT);
        } catch (ActivityNotFoundException e) {
        }
    }

    private static final class GlobalActionsDialog extends Dialog implements DialogInterface {
        private final Context mContext;
        private final int mWindowTouchSlop;
        private final AlertController mAlert;
        private final MyAdapter mAdapter;

        private EnableAccessibilityController mEnableAccessibilityController;

        private boolean mIntercepted;
        private boolean mCancelOnUp;

        public GlobalActionsDialog(Context context, AlertParams params) {
            super(context, getDialogTheme(context));
            mContext = context;
            mAlert = new AlertController(mContext, this, getWindow());
            mAdapter = (MyAdapter) params.mAdapter;
            mWindowTouchSlop = ViewConfiguration.get(context).getScaledWindowTouchSlop();
            params.apply(mAlert);
        }

        private static int getDialogTheme(Context context) {
            TypedValue outValue = new TypedValue();
            context.getTheme().resolveAttribute(com.android.internal.R.attr.alertDialogTheme,
                    outValue, true);
            return outValue.resourceId;
        }

        @Override
        protected void onStart() {
            // If global accessibility gesture can be performed, we will take care
            // of dismissing the dialog on touch outside. This is because the dialog
            // is dismissed on the first down while the global gesture is a long press
            // with two fingers anywhere on the screen.
            if (EnableAccessibilityController.canEnableAccessibilityViaGesture(mContext)) {
                mEnableAccessibilityController = new EnableAccessibilityController(mContext,
                        new Runnable() {
                    @Override
                    public void run() {
                        dismiss();
                    }
                });
                super.setCanceledOnTouchOutside(false);
            } else {
                mEnableAccessibilityController = null;
                super.setCanceledOnTouchOutside(true);
            }

            super.onStart();
        }

        @Override
        protected void onStop() {
            if (mEnableAccessibilityController != null) {
                mEnableAccessibilityController.onDestroy();
            }
            super.onStop();
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (mEnableAccessibilityController != null) {
                final int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    View decor = getWindow().getDecorView();
                    final int eventX = (int) event.getX();
                    final int eventY = (int) event.getY();
                    if (eventX < -mWindowTouchSlop
                            || eventY < -mWindowTouchSlop
                            || eventX >= decor.getWidth() + mWindowTouchSlop
                            || eventY >= decor.getHeight() + mWindowTouchSlop) {
                        mCancelOnUp = true;
                    }
                }
                try {
                    if (!mIntercepted) {
                        mIntercepted = mEnableAccessibilityController.onInterceptTouchEvent(event);
                        if (mIntercepted) {
                            final long now = SystemClock.uptimeMillis();
                            event = MotionEvent.obtain(now, now,
                                    MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
                            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                            mCancelOnUp = true;
                        }
                    } else {
                        return mEnableAccessibilityController.onTouchEvent(event);
                    }
                } finally {
                    if (action == MotionEvent.ACTION_UP) {
                        if (mCancelOnUp) {
                            cancel();
                        }
                        mCancelOnUp = false;
                        mIntercepted = false;
                    }
                }
            }
            return super.dispatchTouchEvent(event);
        }

        public ListView getListView() {
            return mAlert.getListView();
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mAlert.installContent();
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                for (int i = 0; i < mAdapter.getCount(); ++i) {
                    CharSequence label =
                            mAdapter.getItem(i).getLabelForAccessibility(getContext());
                    if (label != null) {
                        event.getText().add(label);
                    }
                }
            }
            return super.dispatchPopulateAccessibilityEvent(event);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (mAlert.onKeyDown(keyCode, event)) {
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (mAlert.onKeyUp(keyCode, event)) {
                return true;
            }
            return super.onKeyUp(keyCode, event);
        }
    }
}
