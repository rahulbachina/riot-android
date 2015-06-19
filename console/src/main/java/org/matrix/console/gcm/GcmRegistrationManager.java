/**
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.console.gcm;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.android.gms.iid.InstanceID;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.console.Matrix;
import org.matrix.console.R;

import java.io.IOException;
import java.util.ArrayList;


/**
 * Helper class to store the GCM registration ID in {@link SharedPreferences}
 */
public final class GcmRegistrationManager {
    private static String LOG_TAG = "GcmRegistrationManager";

    public static final String PREFS_GCM = "org.matrix.console.gcm.GcmRegistrationManager";
    public static final String PREFS_KEY_REG_ID_PREFIX = "REG_ID-";

    public static final String PREFS_PUSHER_APP_ID_KEY = "org.matrix.console.gcm.GcmRegistrationManager.pusherAppId";
    public static final String PREFS_SENDER_ID_KEY = "org.matrix.console.gcm.GcmRegistrationManager.senderId";
    public static final String PREFS_PUSHER_URL_KEY = "org.matrix.console.gcm.GcmRegistrationManager.pusherUrl";
    public static final String PREFS_PUSHER_FILE_TAG_KEY = "org.matrix.console.gcm.GcmRegistrationManager.pusherFileTag";

    // TODO: Make this configurable at build time
    private static String DEFAULT_PUSHER_APP_ID = "org.matrix.console.android";
    private static String DEFAULT_PUSHER_URL = "http://matrix.org/_matrix/push/v1/notify";
    private static String DEFAULT_PUSHER_FILE_TAG = "mobile";

    /**
     * GCM registration interface
     */
    public interface GcmRegistrationIdListener {
        void onPusherRegistered();
        void onPusherRegistrationFailed();
    }

    /**
     * 3rd party server registation interface
     */
    public interface GcmSessionRegistration {
        void onSessionRegistred();
        void onSessionRegistrationFailed();

        void onSessionUnregistred();
        void onSessionUnregistrationFailed();
    }

    // theses both entries can be updated from the settings page in debug mode
    private String mPusherAppId = null;
    private String mPusherUrl = null;
    private String mPusherFileTag = null;

    private String mPusherAppName = null;
    private String mPusherLang = null;

    private enum RegistrationState {
        UNREGISTRATED,
        GCM_REGISTRATING,
        GCM_REGISTRED,
        SERVER_REGISTRATING,
        SERVER_REGISTERED
    };

    private static String mBasePusherDeviceName = Build.MODEL.trim();

    private Context mContext;
    private RegistrationState mRegistrationState = RegistrationState.UNREGISTRATED;

    private String mPushKey = null;

    public GcmRegistrationManager(Context appContext) {
        mContext = appContext.getApplicationContext();

        try {
            PackageInfo pInfo = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0);
            mPusherAppName = pInfo.packageName;
            mPusherLang = mContext.getResources().getConfiguration().locale.getLanguage();
        } catch (Exception e) {
            mPusherAppName = "Matrix Console";
            mPusherLang = "en";
        }

        loadGcmData();
    }

    /**
     * reset the Registration
     */
    public void reset() {
        unregisterSessions(null);

        // remove the customized keys
        getSharedPreferences().
                edit().
                remove(PREFS_PUSHER_APP_ID_KEY).
                remove(PREFS_SENDER_ID_KEY).
                remove(PREFS_PUSHER_URL_KEY).
                remove(PREFS_PUSHER_FILE_TAG_KEY).
                commit();

        loadGcmData();
    }

    public String pusherUrl() {
        return mPusherUrl;
    }

    public void setPusherUrl(String pusherUrl) {
        if (!TextUtils.isEmpty(pusherUrl) && !pusherUrl.equals(mPusherUrl)) {
            mPusherUrl = pusherUrl;
            SaveGCMData();
        }
    }

    public String pusherFileTag() {
        return mPusherFileTag;
    }

    public void setPusherFileTag(String pusherFileTag) {
        if (!TextUtils.isEmpty(pusherFileTag) && !pusherFileTag.equals(mPusherFileTag)) {
            mPusherFileTag = pusherFileTag;
            SaveGCMData();
        }
    }

    /**
     * Force to retrieve the
     * @param appContext
     * @param registrationListener
     */
    public void refreshPushToken(final Context appContext, final GcmRegistrationIdListener registrationListener) {
        setStoredPushKey(null);
        mRegistrationState = RegistrationState.UNREGISTRATED;
        registerPusher(appContext, registrationListener);
    }

    /**
     * Register to the GCM.
     * @param registrationListener the events listener.
     */
    public void registerPusher(final Context appContext, final GcmRegistrationIdListener registrationListener) {
        // already registred
        if (mRegistrationState == RegistrationState.GCM_REGISTRED) {
            if (null != registrationListener) {
                registrationListener.onPusherRegistered();
            }
        } else if (mRegistrationState != RegistrationState.UNREGISTRATED) {
            if (null != registrationListener) {
                registrationListener.onPusherRegistrationFailed();
            }
        } else {

            mRegistrationState = RegistrationState.GCM_REGISTRATING;

            new AsyncTask<Void, Void, String>() {
                @Override
                protected String doInBackground(Void... voids) {
                    String pushKey = null;

                    pushKey = getPushKey(appContext);

                    if (pushKey != null) {
                        mPushKey = pushKey;
                    }

                    return mPushKey;
                }

                @Override
                protected void onPostExecute(String pushKey) {
                    // succeed to retrieve the push key
                    if (pushKey != null) {
                        mRegistrationState = RegistrationState.GCM_REGISTRED;

                        setStoredPushKey(pushKey);

                        // register the sessions to the 3rd party server
                        if (useGCM()) {
                            registerSessions(null);
                        }
                    } else {
                        // fail to retrieve the push key
                        // assume that a full registration is required.
                        setStoredPushKey(null);
                        mRegistrationState = RegistrationState.UNREGISTRATED;
                    }

                    // warn the listener
                    if (null != registrationListener) {
                        try {
                            if (pushKey != null) {
                                registrationListener.onPusherRegistered();
                            } else {
                                registrationListener.onPusherRegistrationFailed();
                            }
                        } catch (Exception e) {
                        }
                    }
                }
            }.execute();
        }
    }

    /**
     * @return true if use GCM
     */
    public Boolean useGCM() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        return preferences.getBoolean(mContext.getString(R.string.settings_key_use_google_cloud_messaging), true);
    }

    public Boolean isGCMRegistred() {
        return (mRegistrationState == RegistrationState.GCM_REGISTRED) || (mRegistrationState == RegistrationState.SERVER_REGISTRATING) || (mRegistrationState == RegistrationState.SERVER_REGISTERED);
    }

    public Boolean is3rdPartyServerRegistred() {
        return mRegistrationState == RegistrationState.SERVER_REGISTERED;
    }

    private String getPushKey(Context appContext) {
        String pushKey = getStoredPushKey();

        if (pushKey == null) {
            try {
                InstanceID instanceID = InstanceID.getInstance(appContext);
                pushKey = instanceID.getToken(appContext.getString(R.string.gcm_defaultSenderId),
                        GoogleCloudMessaging.INSTANCE_ID_SCOPE, null);
                // [END get_token]
                Log.i(LOG_TAG, "GCM Registration Token: " + pushKey);

                //setStoredRegistrationId(registrationId);
            } catch (IOException e) {
                pushKey = null;
            }
        }
        return pushKey;
    }

    /**
     * Register the session to the 3rd-party app server
     * @param session the session to register.
     * @param listener the registration listener
     */
    public void registerSession(final MXSession session, final GcmSessionRegistration listener) {
        session.getPushersRestClient()
                .addHttpPusher(mPushKey, mPusherAppId, mPusherFileTag + "_" + session.getMyUser().userId,
                        mPusherLang, mPusherAppName, mBasePusherDeviceName,
                        mPusherUrl, new ApiCallback<Void>() {
                            @Override
                            public void onSuccess(Void info) {
                                Log.d(LOG_TAG, "registerPusher succeeded");

                                if (null != listener) {
                                    try {
                                        listener.onSessionRegistred();
                                    } catch (Exception e) {
                                    }
                                }
                            }

                            private void onError() {
                                if (null != listener) {
                                    try {
                                        listener.onSessionRegistrationFailed();
                                    } catch (Exception e) {
                                    }
                                }
                            }

                            @Override
                            public void onNetworkError(Exception e) {
                                Log.e(LOG_TAG, "registerPusher onNetworkError " + e.getMessage());
                                onError();
                            }

                            @Override
                            public void onMatrixError(MatrixError e) {
                                Log.e(LOG_TAG, "registerPusher onMatrixError " + e.errcode);
                                onError();
                            }

                            @Override
                            public void onUnexpectedError(Exception e) {
                                Log.e(LOG_TAG, "registerPusher onUnexpectedError " + e.getMessage());
                                onError();
                            }
                        });
    }

    /**
     * Register the current sessions to the 3rd party GCM server
     * @param listener the registration listener.
     */
    public void registerSessions(final GcmSessionRegistration listener) {
        if (mRegistrationState != RegistrationState.GCM_REGISTRED) {
            if (null != listener) {
                try {
                    listener.onSessionRegistrationFailed();
                } catch (Exception e) {
                }
            }
        } else {
            mRegistrationState = RegistrationState.SERVER_REGISTRATING;
            registerSessions(new ArrayList<MXSession>(Matrix.getInstance(mContext).getSessions()), 0, listener);
        }
    }

    /**
     * Recursive method to register a MXSessions list.
     * @param sessions the sessions list.
     * @param index the index of the MX sessions to register.
     * @param listener the registration listener.
     */
    private void registerSessions(final ArrayList<MXSession> sessions, final int index, final GcmSessionRegistration listener) {
        // reach this end of the list ?
        if (index >= sessions.size()) {
            mRegistrationState = RegistrationState.SERVER_REGISTERED;

            if (null != listener) {
                try {
                    listener.onSessionRegistred();
                } catch (Exception e) {
                }
            }
            return;
        }

        MXSession session = sessions.get(index);

        registerSession(session, new GcmSessionRegistration() {
            @Override
            public void onSessionRegistred() {
                registerSessions(sessions, index + 1, listener);
            }

            @Override
            public void onSessionRegistrationFailed() {
                if (null != listener) {
                    try {
                        mRegistrationState = RegistrationState.GCM_REGISTRED;
                        listener.onSessionRegistrationFailed();
                    } catch (Exception e) {
                    }
                }
            }

            @Override
            public void onSessionUnregistred() {
            }

            @Override
            public void onSessionUnregistrationFailed() {
            }
        });
    }

    /**
     * Unregister the user identified from his matrix Id from the 3rd-party app server
     * @param session
     */
    public void unregisterSession(final MXSession session, final GcmSessionRegistration listener) {
        session.getPushersRestClient()
                .removeHttpPusher(mPushKey, mPusherAppId, mPusherFileTag + "_" + session.getMyUser().userId,
                        mPusherLang, mPusherAppName, mBasePusherDeviceName,
                        mPusherUrl, new ApiCallback<Void>() {
                            @Override
                            public void onSuccess(Void info) {
                                Log.d(LOG_TAG, "unregisterSession succeeded");

                                if (null != listener) {
                                    try {
                                        listener.onSessionUnregistred();
                                    } catch (Exception e) {
                                    }
                                }
                            }

                            private void onError() {
                                if (null != listener) {
                                    try {
                                        listener.onSessionUnregistrationFailed();
                                    } catch (Exception e) {
                                    }
                                }
                            }

                            @Override
                            public void onNetworkError(Exception e) {
                                Log.e(LOG_TAG, "unregisterSession onNetworkError " + e.getMessage());
                                onError();
                            }

                            @Override
                            public void onMatrixError(MatrixError e) {
                                Log.e(LOG_TAG, "unregisterSession onMatrixError " + e.errcode);
                                onError();
                            }

                            @Override
                            public void onUnexpectedError(Exception e) {
                                Log.e(LOG_TAG, "unregisterSession onUnexpectedError " + e.getMessage());
                                onError();
                            }
                        });
    }

    /**
     * Unregister the current sessions from the 3rd party GCM server
     * @param listener the registration listener.
     */
    public void unregisterSessions(final GcmSessionRegistration listener) {
        if (mRegistrationState != RegistrationState.SERVER_REGISTERED) {
            if (null != listener) {
                try {
                    listener.onSessionUnregistrationFailed();
                } catch (Exception e) {
                }
            }
        } else {
            mRegistrationState = RegistrationState.GCM_REGISTRED;
            unregisterSessions(new ArrayList<MXSession>(Matrix.getInstance(mContext).getSessions()), 0, listener);
        }
    }

    /**
     * Recursive method to unregister a MXSessions list.
     * @param sessions the sessions list.
     * @param index the index of the MX sessions to register.
     * @param listener the registration listener.
     */
    private void unregisterSessions(final ArrayList<MXSession> sessions, final int index, final GcmSessionRegistration listener) {
        // reach this end of the list ?
        if (index >= sessions.size()) {
            if (null != listener) {
                try {
                    listener.onSessionUnregistred();
                } catch (Exception e) {
                }
            }
            return;
        }

        MXSession session = sessions.get(index);

        unregisterSession(session , new GcmSessionRegistration() {
            @Override
            public void onSessionRegistred() {
            }

            @Override
            public void onSessionRegistrationFailed() {
            }

            @Override
            public void onSessionUnregistred() {
                unregisterSessions(sessions, index+1, listener);
            }

            @Override
            public void onSessionUnregistrationFailed() {
                if (null != listener) {
                    try {
                        listener.onSessionUnregistrationFailed();
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    /**
     * @return the GCM registration stored for this version of the app or null if none is stored.
     */
    private String getStoredPushKey() {
        return getSharedPreferences().getString(getPushKeyKey(), null);
    }

    /**
     * Set the GCM registration for the currently-running version of this app.
     * @param pushKey
     */
    private void setStoredPushKey(String pushKey) {
        String key = getPushKeyKey();
        if (key == null) {
            Log.e(LOG_TAG, "Failed to store registration ID");
            return;
        }

        Log.d(LOG_TAG, "Saving push key " + pushKey + " under key " + key);
        getSharedPreferences().edit()
                .putString(key, pushKey)
                .commit();
    }

    private SharedPreferences getSharedPreferences() {
        return mContext.getSharedPreferences(PREFS_GCM, Context.MODE_PRIVATE);
    }

    private String getPushKeyKey() {
        try {
            PackageInfo packageInfo = mContext.getPackageManager()
                    .getPackageInfo(mContext.getPackageName(), 0);
            return PREFS_KEY_REG_ID_PREFIX + Integer.toString(packageInfo.versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Save the GCM info to the preferences
     */
    private void SaveGCMData() {
        try {
            SharedPreferences preferences = getSharedPreferences();
            SharedPreferences.Editor editor = preferences.edit();

            editor.putString(PREFS_PUSHER_APP_ID_KEY, mPusherAppId);
            editor.putString(PREFS_PUSHER_URL_KEY, mPusherUrl);
            editor.putString(PREFS_PUSHER_FILE_TAG_KEY, mPusherFileTag);

            editor.commit();
        } catch (Exception e) {

        }
    }

    /**
     * Load the GCM info from the preferences
     */
    private void loadGcmData() {
        try {
            SharedPreferences preferences = getSharedPreferences();

            String pusherAppId = preferences.getString(PREFS_PUSHER_APP_ID_KEY, null);
            mPusherAppId = TextUtils.isEmpty(pusherAppId) ? DEFAULT_PUSHER_APP_ID : pusherAppId;

            String pusherUrl = preferences.getString(PREFS_PUSHER_URL_KEY, null);
            mPusherUrl = TextUtils.isEmpty(pusherUrl) ? DEFAULT_PUSHER_URL : pusherUrl;

            String pusherFileTag = preferences.getString(PREFS_PUSHER_FILE_TAG_KEY, null);
            mPusherFileTag = TextUtils.isEmpty(pusherFileTag) ? DEFAULT_PUSHER_FILE_TAG : pusherFileTag;

        } catch (Exception e) {

        }
    }
}