/*  Copyright (C) 2022-2026 José Rebelo, Martin Braun

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.


    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Objects;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.huami.HuamiCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.huami.zeppos.ZeppOsCoordinator;
import nodomain.freeyourgadget.gadgetbridge.entities.Alarm;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;

public class DeviceAlarmReceiver extends BroadcastReceiver {
    private static final Logger LOG = LoggerFactory.getLogger(DeviceAlarmReceiver.class);

    private static final String macAddrPattern = "^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$";

    public static final String COMMAND_SET_ALARM = "nodomain.freeyourgadget.gadgetbridge.command.SET_ALARM";
    public static final String COMMAND_DISMISS_ALARM = "nodomain.freeyourgadget.gadgetbridge.command.DISMISS_ALARM";

    public static final String EXTRA_MAC_ADDR = "device";
    public static final String EXTRA_DAYS = "days";
    public static final String EXTRA_HOUR = "hour";
    public static final String EXTRA_MINUTES = "minutes";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ALARM_SEARCH_MODE = "mode";
    public static final String ALARM_SEARCH_MODE_ALL = "all";
    public static final String ALARM_SEARCH_MODE_TIME = "time";
    public static final String ALARM_SEARCH_MODE_TITLE = "title";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String action = intent.getAction();

        if (!COMMAND_SET_ALARM.equals(action) && !COMMAND_DISMISS_ALARM.equals(action)) {
            LOG.warn("Unexpected action {}", intent.getAction());
        }
        LOG.debug("Got third party alarm action: {}", action);

        final String deviceAddress = intent.getStringExtra(EXTRA_MAC_ADDR);

        if (deviceAddress == null) {
            LOG.warn("Missing device address");
            return;
        }

        if (!deviceAddress.matches(macAddrPattern)) {
            LOG.warn("Device address '{}' does not match '{}'", deviceAddress, macAddrPattern);
            return;
        }

        final GBDevice targetDevice = GBApplication.app()
                .getDeviceManager()
                .getDeviceByAddress(deviceAddress);

        if (targetDevice == null) {
            LOG.warn("Unknown device {}", deviceAddress);
            return;
        }

        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(targetDevice.getAddress());

        if (!prefs.getBoolean("third_party_apps_set_alarms", false)) {
            LOG.warn("Setting alarms from 3rd party apps not allowed for {}", deviceAddress);
            return;
        }

        // TODO: sync alarms back from device to DB to avoid losing device-exclusive changes
        // (this requires a common interface for back-syncing alarms first)
        final ArrayList<Alarm> alarms = (ArrayList<Alarm>) DBHelper.getAlarmsWithDefaults(targetDevice);
        if (alarms.isEmpty()) {
            LOG.error("Alarms are not supported on this device");
            return;
        }

        boolean changed = false;
        final ArrayList<Alarm> touched = new ArrayList<>();
        final String mode = intent.getStringExtra(EXTRA_ALARM_SEARCH_MODE);
        final String title = intent.getStringExtra(EXTRA_TITLE);
        if (COMMAND_SET_ALARM.equals(action)) {
            final int hour = intent.getIntExtra(EXTRA_HOUR, -1);
            final int minute = intent.getIntExtra(EXTRA_MINUTES, -1);
            if ((hour == -1 || minute == -1)) {
                LOG.error("Both hour and minutes have to be provided when creating an alarm");
                return;
            }
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                LOG.error("Invalid time provided");
                return;
            }
            final ArrayList<Integer> days = intent.getIntegerArrayListExtra(EXTRA_DAYS); // Calendar.SUNDAY, Calendar.MONDAY, Cal..;
            int repetition = convertRepetitionMask(days);
            // find the next free slot: preferably an unused one, so the user's own alarms are
            // left alone; otherwise a dismissed alarm without any title
            // (this way the user can protect dismissed alarms by naming them)
            Alarm free = null;
            for (Alarm alarm : alarms) {
                if (alarm.getUnused()) {
                    free = alarm;
                    break;
                }
            }
            if (free == null) {
                for (Alarm alarm : alarms) {
                    if (!alarm.getEnabled() && (
                            alarm.getTitle() == null || alarm.getTitle().isEmpty())) {
                        free = alarm;
                        break;
                    }
                }
            }
            if (free != null) {
                updateAlarm(free, true, hour, minute, repetition, title);
                DBHelper.store(free);
                touched.add(free);
                changed = true;
            }
            if (!changed) {
                LOG.error("No free alarm slot was found; A slot is free when it's disabled and has no title");
                return;
            }
        } else if (COMMAND_DISMISS_ALARM.equals(action)) {
            if (!Objects.equals(mode, ALARM_SEARCH_MODE_TIME) &&
                    !Objects.equals(mode, ALARM_SEARCH_MODE_ALL) &&
                    !Objects.equals(mode, ALARM_SEARCH_MODE_TITLE)) {
                LOG.error("Unknown mode");
                return;
            }
            final int hour = intent.getIntExtra(EXTRA_HOUR, -1);
            final int minutes = intent.getIntExtra(EXTRA_MINUTES, -1);
            if (Objects.equals(mode, ALARM_SEARCH_MODE_TIME) && hour == -1) {
                LOG.error("Hour has to be provided when dismissing an alarm by time");
                return;
            }
            if (Objects.equals(mode, ALARM_SEARCH_MODE_TITLE)
                    && (title == null || title.isEmpty())) {
                LOG.error("Title has to be provided when dismissing an alarm by title");
                return;
            }
            for (Alarm alarm : alarms) {
                if (Objects.equals(mode, ALARM_SEARCH_MODE_ALL) ||
                        (Objects.equals(mode, ALARM_SEARCH_MODE_TITLE)
                                && alarm.getTitle() != null
                                && alarm.getTitle().contains(title)
                        ) ||
                        (Objects.equals(mode, ALARM_SEARCH_MODE_TIME)
                                && alarm.getHour() == hour
                                && (minutes == -1 || alarm.getMinute() == minutes)
                        )
                ) {
                    // dismiss the alarm and clear its title, so it can be set again;
                    // its slot is free again, like deleting it in the alarm list
                    updateAlarm(alarm, false, alarm.getHour(), alarm.getMinute(), 0, "");
                    alarm.setUnused(true);
                    DBHelper.store(alarm);
                    touched.add(alarm);
                    changed = true;
                }
            }
            if (!changed) {
                LOG.warn("No alarm to dismiss was found");
            }
        } else {
            LOG.error("Unknown action");
            return;
        }

        if (changed) {
            LocalBroadcastManager.getInstance(context).sendBroadcast(
                    new Intent(DeviceService.ACTION_SAVE_ALARMS)
            );
            // Send only the alarms changed here where the device writes each slot by itself, so
            // alarms changed on the watch alone aren't overwritten by stale copies from the DB
            final boolean slotBySlot = writesAlarmSlotsSeparately(targetDevice.getDeviceCoordinator());
            GBApplication.deviceService(targetDevice).onSetAlarms(slotBySlot ? touched : alarms);
        }
    }

    /**
     * Whether sending a subset of the alarms leaves the others on the device untouched.
     * True for the pre-Zepp OS Huami devices, whose HuamiSupport.onSetAlarms writes one
     * message per given alarm to that alarm's slot; other protocols may treat the list as
     * the complete set, so they keep getting all alarms.
     */
    private static boolean writesAlarmSlotsSeparately(final DeviceCoordinator coordinator) {
        return coordinator instanceof HuamiCoordinator && !(coordinator instanceof ZeppOsCoordinator);
    }

    private static int convertRepetitionMask(ArrayList<Integer> days) {
        int repetitionMask = 0;
        if (days != null && !days.isEmpty()) {
            for (int day : days) {
                switch (day) {
                    case Calendar.MONDAY:
                        repetitionMask |= Alarm.ALARM_MON;
                        break;
                    case Calendar.TUESDAY:
                        repetitionMask |= Alarm.ALARM_TUE;
                        break;
                    case Calendar.WEDNESDAY:
                        repetitionMask |= Alarm.ALARM_WED;
                        break;
                    case Calendar.THURSDAY:
                        repetitionMask |= Alarm.ALARM_THU;
                        break;
                    case Calendar.FRIDAY:
                        repetitionMask |= Alarm.ALARM_FRI;
                        break;
                    case Calendar.SATURDAY:
                        repetitionMask |= Alarm.ALARM_SAT;
                        break;
                    case Calendar.SUNDAY:
                        repetitionMask |= Alarm.ALARM_SUN;
                        break;
                }
            }
        }
        return repetitionMask;
    }

    private static void updateAlarm(Alarm alarm, boolean enable, int hour, int minute, int repetition, String title) {
        alarm.setHour(hour);
        alarm.setMinute(minute);
        alarm.setRepetition(repetition);
        alarm.setTitle(title);
        alarm.setEnabled(enable);
        if (enable) {
            // Like switching an alarm on in the alarm list: otherwise a slot the device
            // reported as unused is sent to it disabled
            alarm.setUnused(false);
        }
    }

    public IntentFilter buildFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(COMMAND_SET_ALARM);
        intentFilter.addAction(COMMAND_DISMISS_ALARM);
        return intentFilter;
    }
}
