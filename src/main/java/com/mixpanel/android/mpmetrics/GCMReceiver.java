package com.mixpanel.android.mpmetrics;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.util.Log;

import com.mixpanel.android.mpmetrics.MixpanelAPI.InstanceProcessor;

import java.util.HashMap;
import java.util.Map;

/**
* BroadcastReciever for handling Google Cloud Messaging intents.
*
* <p>You can use GCMReciever to report Google Cloud Messaging registration identifiers
* to Mixpanel, and to display incoming notifications from Mixpanel to
* the device status bar. Together with {@link MixpanelAPI.People#initPushHandling(String) }
* this is the simplest way to get up and running with notifications from Mixpanel.
*
* <p>To enable GCMReciever in your application, add a clause like the following
* to the &lt;application&gt; tag of your AndroidManifest.xml. (Be sure to replace "YOUR APPLICATION PACKAGE NAME"
* in the snippet with the actual package name of your app.)
*
*<pre>
*{@code
*
* <receiver android:name="com.mixpanel.android.mpmetrics.GCMReceiver"
* android:permission="com.google.android.c2dm.permission.SEND" >
* <intent-filter>
* <action android:name="com.google.android.c2dm.intent.RECEIVE" />
* <action android:name="com.google.android.c2dm.intent.REGISTRATION" />
* <category android:name="YOUR APPLICATION PACKAGE NAME" />
* </intent-filter>
* </receiver>
*
*}
*</pre>
*
* <p>In addition, GCMReciever will also need the following permissions configured
* in your AndroidManifest.xml file:
*
* <pre>
* {@code
*
* <!-- Be sure to change YOUR_PACKAGE_NAME to the real name of your application package -->
* <permission android:name="YOUR_PACKAGE_NAME.permission.C2D_MESSAGE" android:protectionLevel="signature" />
* <uses-permission android:name="YOUR_PACKAGE_NAME.permission.C2D_MESSAGE" />
*
* <uses-permission android:name="android.permission.INTERNET" />
* <uses-permission android:name="android.permission.GET_ACCOUNTS" />
* <uses-permission android:name="com.google.android.c2dm.permission.RECEIVE" />
* <uses-permission android:name="android.permission.WAKE_LOCK" />
*
* }
* </pre>
*
* <p>Once the GCMReciever is configured, the only thing you have to do to
* get set up Mixpanel messages is call {@link MixpanelAPI.People#identify(String) }
* with a distinct id for your user, and call {@link MixpanelAPI.People#initPushHandling(String) }
* with the your Google API project identifier.
* <pre>
* {@code
*
* MixpanelAPI.People people = mMixpanelAPI.getPeople();
* people.identify("A USER DISTINCT ID");
* people.initPushHandling("123456789123");
*
* }
* </pre>
*
* <p>If you would prefer to handle either sending a registration id to Mixpanel yourself
* but allow GCMReciever to handle displaying Mixpanel messages, remove the
* REGISTRATION intent from the GCMReciever {@code <reciever> } tag, and call
* {@link MixpanelAPI.People#setPushRegistrationId(String)}
* in your own REGISTRATION handler.
*
* @see MixpanelAPI#getPeople()
* @see MixpanelAPI.People#initPushHandling(String)
* @see <a href="https://mixpanel.com/docs/people-analytics/android-push">Getting Started with Android Push Notifications</a>
*/
public class GCMReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if ("com.google.android.c2dm.intent.REGISTRATION".equals(action)) {
            handleRegistrationIntent(intent);
        } else if ("com.google.android.c2dm.intent.RECEIVE".equals(action)) {
            handleNotificationIntent(context, intent);
        }
    }

    private void handleRegistrationIntent(Intent intent) {
        final String registration = intent.getStringExtra("registration_id");
        if (intent.getStringExtra("error") != null) {
            Log.e(LOGTAG, "Error when registering for GCM: " + intent.getStringExtra("error"));
        } else if (registration != null) {
            if (MPConfig.DEBUG) Log.d(LOGTAG, "Registering GCM ID: " + registration);
            MixpanelAPI.allInstances(new InstanceProcessor() {
                @Override
                public void process(MixpanelAPI api) {
                    api.getPeople().setPushRegistrationId(registration);
                }
            });
        } else if (intent.getStringExtra("unregistered") != null) {
            if (MPConfig.DEBUG) Log.d(LOGTAG, "Unregistering from GCM");
            MixpanelAPI.allInstances(new InstanceProcessor() {
                @Override
                public void process(MixpanelAPI api) {
                    api.getPeople().clearPushRegistrationId();
                }
            });
        }
    }

    private void handleNotificationIntent(Context context, Intent intent) {
        final String message = intent.getStringExtra("mp_message");
        final String iconName = intent.getStringExtra("mp_icon_name");
        CharSequence notificationTitle = intent.getStringExtra("mp_title");

        if (message == null) return;
        if (MPConfig.DEBUG) Log.d(LOGTAG, "MP GCM notification received: " + message);

        final PackageManager manager = context.getPackageManager();
        final Intent appIntent = manager.getLaunchIntentForPackage(context.getPackageName());
        int notificationIcon = -1;

        if (null != iconName) {
            final ResourceIds drawableIds = new ResourceReader.Drawables(context);
            if (drawableIds.knownIdName(iconName)) {
                notificationIcon = drawableIds.idFromName(iconName);
            }
        }

        ApplicationInfo appInfo;
        try {
            appInfo = manager.getApplicationInfo(context.getPackageName(), 0);
        } catch (final NameNotFoundException e) {
            appInfo = null;
        }

        if (null == notificationTitle && null != appInfo) {
            notificationTitle = manager.getApplicationLabel(appInfo);
        }

        if (notificationIcon == -1 && null != appInfo) {
            notificationIcon = appInfo.icon;
        }

        if (notificationIcon == -1) {
            notificationIcon = android.R.drawable.sym_def_app_icon;
        }

        if (null == notificationTitle) {
            notificationTitle = "A Message For You";
        }

        final PendingIntent contentIntent = PendingIntent.getActivity(
            context.getApplicationContext(),
            0,
            appIntent, // add this pass null to intent
            PendingIntent.FLAG_UPDATE_CURRENT
        );

        final NotificationManager notificationManager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        final Notification notification;
        if (Build.VERSION.SDK_INT >= 16) {
            notification = makeNotificationSDK16OrHigher(context, contentIntent, notificationIcon, notificationTitle, message);
        } else if (Build.VERSION.SDK_INT >= 11) {
            notification = makeNotificationSDK11OrHigher(context, contentIntent, notificationIcon, notificationTitle, message);
        } else {
            notification = makeNotificationSDKLessThan11(context, contentIntent, notificationIcon, notificationTitle, message);
        }

        notificationManager.notify(0, notification);
    }

    @SuppressWarnings("deprecation")
    @TargetApi(9)
    private Notification makeNotificationSDKLessThan11(Context context, PendingIntent intent, int notificationIcon, CharSequence title, CharSequence message) {
        final Notification n = new Notification(notificationIcon, message, System.currentTimeMillis());
        n.flags |= Notification.FLAG_AUTO_CANCEL;
        n.setLatestEventInfo(context, title, message, intent);
        return n;
    }

    @SuppressWarnings("deprecation")
    @TargetApi(11)
    private Notification makeNotificationSDK11OrHigher(Context context, PendingIntent intent, int notificationIcon, CharSequence title, CharSequence message) {
        final Notification.Builder builder = new Notification.Builder(context).
                setSmallIcon(notificationIcon).
                setTicker(message).
                setWhen(System.currentTimeMillis()).
                setContentTitle(title).
                setContentText(message).
                setContentIntent(intent);

        final Notification n = builder.getNotification();
        n.flags |= Notification.FLAG_AUTO_CANCEL;
        return n;
    }

    @SuppressLint("NewApi")
    @TargetApi(16)
    private Notification makeNotificationSDK16OrHigher(Context context, PendingIntent intent, int notificationIcon, CharSequence title, CharSequence message) {
        final Notification.Builder builder = new Notification.Builder(context).
                setSmallIcon(notificationIcon).
                setTicker(message).
                setWhen(System.currentTimeMillis()).
                setContentTitle(title).
                setContentText(message).
                setContentIntent(intent).
                setStyle(new Notification.BigTextStyle().bigText(message));

        final Notification n = builder.build();
        n.flags |= Notification.FLAG_AUTO_CANCEL;
        return n;
    }

    @SuppressWarnings("unused")
    private static final String LOGTAG = "MixpanelAPI.GCMReceiver";
}
