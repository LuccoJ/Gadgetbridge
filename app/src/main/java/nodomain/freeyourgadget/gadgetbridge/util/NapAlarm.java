/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.NapAlarmWidget;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.SleepState;

/**
 * A nap alarm that wakes the user after a given amount of <em>actual</em> sleep, as reported by
 * the device's fell asleep / woke up events (see GBDeviceEventSleepStateDetection).
 * <p>
 * While the user is asleep, a single-shot alarm is set on the device for the moment the remaining
 * sleep time runs out. When the device reports the user woke up before that, the time slept so far
 * is accumulated, and the device alarm slot is restored to the user's own alarm until they fall
 * asleep again. Only one nap can be active at a time; its state is kept in the global preferences
 * so that it survives the app being restarted.
 */
public final class NapAlarm {
    private static final Logger LOG = LoggerFactory.getLogger(NapAlarm.class);

    private static final String PREF_DEVICE = "nap_alarm_device";
    private static final String PREF_TARGET_MS = "nap_alarm_target_ms";
    private static final String PREF_SLEPT_MS = "nap_alarm_slept_ms";
    private static final String PREF_ASLEEP_SINCE = "nap_alarm_asleep_since";
    private static final String PREF_WAKE_AT = "nap_alarm_wake_at";
    private static final String PREF_SLOT = "nap_alarm_slot";

    public enum Status {
        INACTIVE,
        /** Armed, waiting for the user to fall asleep (also after a brief awakening). */
        WAITING,
        /** User is asleep, an alarm is set on the device. */
        ASLEEP,
    }

    private NapAlarm() {
    }

    private static SharedPreferences prefs() {
        return GBApplication.getPrefs().getPreferences();
    }

    public static Status getStatus() {
        final SharedPreferences prefs = prefs();
        if (prefs.getString(PREF_DEVICE, "").isEmpty()) {
            return Status.INACTIVE;
        }
        return prefs.getLong(PREF_ASLEEP_SINCE, 0) > 0 ? Status.ASLEEP : Status.WAITING;
    }

    public static long getTargetMillis() {
        return prefs().getLong(PREF_TARGET_MS, 0);
    }

    public static long getSleptMillis() {
        return prefs().getLong(PREF_SLEPT_MS, 0);
    }

    public static long getWakeAtMillis() {
        return prefs().getLong(PREF_WAKE_AT, 0);
    }

    /**
     * Arms a new nap, replacing any nap currently in progress.
     */
    public static void start(final Context context, @Nullable final GBDevice device, final int minutes) {
        if (device == null || !device.isInitialized()) {
            GB.toast(context, context.getString(R.string.appwidget_not_connected), Toast.LENGTH_SHORT, GB.WARN);
            return;
        }
        final int slot = getAlarmSlot(device);
        if (slot < 0) {
            GB.toast(context, context.getString(R.string.nap_alarm_no_slot), Toast.LENGTH_LONG, GB.WARN);
            return;
        }

        cancel(context, false);

        LOG.info("Starting nap alarm for {} minutes of sleep on {}, using alarm slot {}", minutes, device, slot);
        prefs().edit()
                .putString(PREF_DEVICE, device.getAddress())
                .putLong(PREF_TARGET_MS, TimeUnit.MINUTES.toMillis(minutes))
                .putLong(PREF_SLEPT_MS, 0)
                .putLong(PREF_ASLEEP_SINCE, 0)
                .putLong(PREF_WAKE_AT, 0)
                .putInt(PREF_SLOT, slot)
                .apply();

        GB.toast(context, context.getString(R.string.nap_alarm_started, minutes), Toast.LENGTH_LONG, GB.INFO);
        NapAlarmWidget.updateAllWidgets(context);
    }

    /**
     * Cancels the nap in progress, if any, giving the device alarm slot back to the user's alarm.
     */
    public static void cancel(final Context context, final boolean notify) {
        if (getStatus() == Status.INACTIVE) {
            return;
        }
        LOG.info("Cancelling nap alarm");
        if (getWakeAtMillis() > 0) {
            restoreDeviceAlarm(getDevice());
        }
        clear();
        if (notify) {
            GB.toast(context, context.getString(R.string.nap_alarm_cancelled), Toast.LENGTH_SHORT, GB.INFO);
        }
        NapAlarmWidget.updateAllWidgets(context);
    }

    /**
     * To be called whenever a device reports that the user fell asleep or woke up.
     */
    public static void onSleepStateChanged(final Context context, final GBDevice device, final SleepState sleepState) {
        if (getStatus() == Status.INACTIVE || !device.getAddress().equals(prefs().getString(PREF_DEVICE, ""))) {
            return;
        }

        final long now = System.currentTimeMillis();
        final long wakeAt = getWakeAtMillis();
        if (wakeAt > 0 && now >= wakeAt) {
            // The device alarm has already gone off, so this event belongs to a later sleep
            LOG.info("Nap alarm finished at {}, ignoring {}", new Date(wakeAt), sleepState);
            finish(context);
            return;
        }

        final SharedPreferences prefs = prefs();
        final long asleepSince = prefs.getLong(PREF_ASLEEP_SINCE, 0);
        final long target = getTargetMillis();
        long slept = getSleptMillis();

        switch (sleepState) {
            case ASLEEP:
                if (asleepSince > 0) {
                    return;
                }
                // Device alarms only have minute resolution, so round up to the next full minute
                final long remaining = target - slept;
                final long newWakeAt = roundUpToMinute(now + remaining);
                LOG.info("Fell asleep during nap, {} ms left, waking at {}", remaining, new Date(newWakeAt));
                prefs.edit()
                        .putLong(PREF_ASLEEP_SINCE, now)
                        .putLong(PREF_WAKE_AT, newWakeAt)
                        .apply();
                setDeviceAlarm(device, newWakeAt);
                break;
            case AWAKE:
                if (asleepSince == 0) {
                    return;
                }
                slept += now - asleepSince;
                LOG.info("Woke up during nap, slept {} of {} ms so far", slept, target);
                if (slept >= target) {
                    finish(context);
                    return;
                }
                prefs.edit()
                        .putLong(PREF_SLEPT_MS, slept)
                        .putLong(PREF_ASLEEP_SINCE, 0)
                        .putLong(PREF_WAKE_AT, 0)
                        .apply();
                restoreDeviceAlarm(device);
                break;
            default:
                return;
        }

        NapAlarmWidget.updateAllWidgets(context);
    }

    /**
     * Whether a nap whose alarm already went off is still recorded as active. The widget uses
     * this to show the idle state, since no further event may arrive from the device.
     */
    public static boolean isFinished() {
        final long wakeAt = getWakeAtMillis();
        return wakeAt > 0 && System.currentTimeMillis() >= wakeAt;
    }

    private static void finish(final Context context) {
        LOG.info("Nap alarm completed");
        restoreDeviceAlarm(getDevice());
        clear();
        NapAlarmWidget.updateAllWidgets(context);
    }

    private static void clear() {
        prefs().edit()
                .remove(PREF_DEVICE)
                .remove(PREF_TARGET_MS)
                .remove(PREF_SLEPT_MS)
                .remove(PREF_ASLEEP_SINCE)
                .remove(PREF_WAKE_AT)
                .remove(PREF_SLOT)
                .apply();
    }

    @Nullable
    private static GBDevice getDevice() {
        final String address = prefs().getString(PREF_DEVICE, "");
        if (address.isEmpty()) {
            return null;
        }
        return GBApplication.app().getDeviceManager().getDeviceByAddress(address);
    }

    /**
     * The nap uses the last alarm slot available to the user, leaving out any slots reserved for
     * calendar events, so that it does not clash with the first alarms, which are the ones more
     * likely to be in use.
     */
    private static int getAlarmSlot(final GBDevice device) {
        final int slots = device.getDeviceCoordinator().getAlarmSlotCount(device);
        final int reserved = GBApplication.getDevicePrefs(device).getInt(DeviceSettingsPreferenceConst.PREF_RESERVER_ALARMS_CALENDAR, 0);
        return slots - reserved - 1;
    }

    private static void setDeviceAlarm(final GBDevice device, final long wakeAt) {
        if (!device.isInitialized()) {
            LOG.warn("Device not connected, unable to set nap alarm");
            return;
        }
        final GregorianCalendar calendar = new GregorianCalendar();
        calendar.setTimeInMillis(wakeAt);
        final Alarm alarm = AlarmUtils.createSingleShot(prefs().getInt(PREF_SLOT, 0), false, false, calendar);
        final ArrayList<Alarm> alarms = new ArrayList<>(1);
        alarms.add(alarm);
        GBApplication.deviceService(device).onSetAlarms(alarms);
    }

    /**
     * Sends the user's own alarm for the nap slot back to the device, or a disabled one if the user
     * has none configured there.
     */
    private static void restoreDeviceAlarm(@Nullable final GBDevice device) {
        if (device == null || !device.isInitialized()) {
            LOG.warn("Device not connected, unable to restore alarm slot");
            return;
        }
        final int slot = prefs().getInt(PREF_SLOT, 0);
        Alarm userAlarm = null;
        final List<nodomain.freeyourgadget.gadgetbridge.entities.Alarm> dbAlarms = DBHelper.getAlarms(device);
        for (final nodomain.freeyourgadget.gadgetbridge.entities.Alarm dbAlarm : dbAlarms) {
            if (dbAlarm.getPosition() == slot) {
                userAlarm = dbAlarm;
                break;
            }
        }
        if (userAlarm == null) {
            userAlarm = AlarmUtils.createDefaultAlarm(device, slot);
        }
        if (userAlarm == null) {
            return;
        }
        final ArrayList<Alarm> alarms = new ArrayList<>(1);
        alarms.add(userAlarm);
        GBApplication.deviceService(device).onSetAlarms(alarms);
    }

    private static long roundUpToMinute(final long millis) {
        final long minute = TimeUnit.MINUTES.toMillis(1);
        return ((millis + minute - 1) / minute) * minute;
    }

    public static String formatTime(final Context context, final long millis) {
        return DateFormat.getTimeFormat(context).format(new Date(millis));
    }
}
