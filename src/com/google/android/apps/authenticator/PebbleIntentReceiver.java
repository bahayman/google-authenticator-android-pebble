package com.google.android.apps.authenticator;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;
import android.view.KeyEvent;

import com.google.android.apps.authenticator.AccountDb.OtpType;
import com.google.android.apps.authenticator.testability.DependencyInjector;

/**
 * Receiver that allows the Pebble Music application to display the codes from Authenticator.
 *
 * @author bahayman@gmail.com (Baruch Hayman)
 */
public class PebbleIntentReceiver extends BroadcastReceiver {
	private static boolean mIsRefreshing = false;
	private static int mCurrentUserIndex = 0;

	private static Timer mRefreshTimer = null;
	private static Timer mAutoStopRefreshTimer = null;

	private static AccountDb mAccountDb = null;
	private static OtpSource mOtpProvider;

	private static PinInfo[] mUsers = {};
	private static TotpCountdownTask mTotpCountdownTask;
	private static TotpCounter mTotpCounter;
	private static TotpClock mTotpClock;
	private static long mTotpCountdownMillis;

	private static final long TOTP_COUNTDOWN_REFRESH_PERIOD = 500;
	private static final String CURRENT_USER_INDEX_PREFERENCE_KEY = "pebble_current_user_index"; 

	@Override
	public void onReceive(Context context, Intent intent) {
		if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
			KeyEvent event = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);

			if (event == null) {
				return;
			}

			int keycode = event.getKeyCode();
			int action = event.getAction();

			if (action == KeyEvent.ACTION_DOWN) {
				if (keycode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
					keycode == KeyEvent.KEYCODE_MEDIA_NEXT ||
					keycode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {

					mCurrentUserIndex = PreferenceManager.getDefaultSharedPreferences(context)
							.getInt(CURRENT_USER_INDEX_PREFERENCE_KEY, 0);

					if (keycode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
						startStopRefreshNowPlaying(context);
					} else if (keycode == KeyEvent.KEYCODE_MEDIA_NEXT ||
							   keycode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
						int newIndex = mCurrentUserIndex +
								(keycode == KeyEvent.KEYCODE_MEDIA_NEXT ? 1 : -1);

						if (newIndex > (mUsers.length - 1)) {
							newIndex = 0;
						} else if (newIndex < 0) {
							newIndex = mUsers.length - 1;
						}
						mCurrentUserIndex = newIndex;

						PreferenceManager.getDefaultSharedPreferences(context).edit()
								.putInt(CURRENT_USER_INDEX_PREFERENCE_KEY, mCurrentUserIndex).commit();

						if (!mIsRefreshing) {
							startStopRefreshNowPlaying(context);
						} else {
							restartAutoStopRefreshTimer(context);
						}
					}

					if (isOrderedBroadcast()) {
						abortBroadcast();
					}
				}
			}
		}
	}

	private static void updateCodesAndStartTotpCountdownTask() {
		if (mAccountDb == null) {
			mAccountDb = DependencyInjector.getAccountDb();
		}

		if (mOtpProvider == null) {
			mOtpProvider = DependencyInjector.getOtpProvider();
			mTotpCounter = mOtpProvider.getTotpCounter();
			mTotpClock = mOtpProvider.getTotpClock();
		}

		stopTotpCountdownTask();

		mTotpCountdownTask = new TotpCountdownTask(mTotpCounter, mTotpClock,
				TOTP_COUNTDOWN_REFRESH_PERIOD);
		mTotpCountdownTask.setListener(new TotpCountdownTask.Listener() {
			@Override
			public void onTotpCountdown(long millisRemaining) {
				mTotpCountdownMillis = millisRemaining;
			}

			@Override
			public void onTotpCounterValueChanged() {
				ArrayList<String> usernames = new ArrayList<String>();
				mAccountDb.getNames(usernames);

				int userCount = usernames.size();

				if (userCount > 0) {
					boolean newListRequired = mUsers.length != userCount;
					if (newListRequired) {
						mUsers = new PinInfo[userCount];
					}

					for (int i = 0; i < userCount; ++i) {
						String user = usernames.get(i);
						try {
							PinInfo currentPin;
							if (mUsers[i] != null) {
								currentPin = mUsers[i];
							} else {
								currentPin = new PinInfo();
								currentPin.pin = null;
							}

							OtpType type = mAccountDb.getType(user);
							currentPin.isHotp = (type == OtpType.HOTP);
							currentPin.user = user;

							if (!currentPin.isHotp) {
								currentPin.pin = mOtpProvider.getNextCode(user);
							}

							mUsers[i] = currentPin;
						} catch (OtpSourceException ignored) { }
					}
				}
			}
		});

		mTotpCountdownTask.startAndNotifyListener();
	}

	private static void stopTotpCountdownTask() {
		if (mTotpCountdownTask != null) {
			mTotpCountdownTask.stop();
			mTotpCountdownTask = null;
		}
	}

	private static void startStopRefreshNowPlaying(final Context context) {
		if (mIsRefreshing) {
			stopTotpCountdownTask();
			mIsRefreshing = false;
			mRefreshTimer.cancel();
			mRefreshTimer = null;
		} else {
			updateCodesAndStartTotpCountdownTask();
			mRefreshTimer = new Timer();
			mRefreshTimer.scheduleAtFixedRate(new TimerTask() {
				@Override
				public void run() {
					Intent nowPlayingIntent = new Intent("com.getpebble.action.NOW_PLAYING");
					nowPlayingIntent.setComponent(
							new ComponentName(
									"com.getpebble.android",
									"com.getpebble.android.receivers.MusicNowPlayingReceiver"));
					nowPlayingIntent.putExtra("album", "Authenticator: "
							+ Utilities.millisToSeconds(mTotpCountdownMillis));
					nowPlayingIntent.putExtra("artist", mUsers[mCurrentUserIndex].user);
					nowPlayingIntent.putExtra("track", mUsers[mCurrentUserIndex].pin);
					context.sendBroadcast(nowPlayingIntent);
				}
			}, 0, TOTP_COUNTDOWN_REFRESH_PERIOD);
			mIsRefreshing = true;

			restartAutoStopRefreshTimer(context);
		}
	}

	private static void restartAutoStopRefreshTimer(final Context context) {
		if (mAutoStopRefreshTimer != null) {
			mAutoStopRefreshTimer.cancel();
			mAutoStopRefreshTimer = null;
		}
		mAutoStopRefreshTimer = new Timer();
		mAutoStopRefreshTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				if (mIsRefreshing) {
					startStopRefreshNowPlaying(context);
				}
			}
		}, (long) (Utilities.secondsToMillis(mTotpCounter.getTimeStep()) * 1.5));
	}

	private static class PinInfo {
		private String pin;
		private String user;
		private boolean isHotp = false;
	}
}