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
package nodomain.freeyourgadget.gadgetbridge;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.NapAlarm;
import nodomain.freeyourgadget.gadgetbridge.util.WidgetPreferenceStorage;

/**
 * Widget that starts a {@link NapAlarm}: the device wakes the user once they have actually slept
 * for the configured number of minutes. Tapping the widget while a nap is active cancels it.
 */
public class NapAlarmWidget extends AppWidgetProvider {
    public static final String ACTION_CLICK =
            "nodomain.freeyourgadget.gadgetbridge.NAP_ALARM_WIDGET_CLICK";

    private static final String PREF_MINUTES_PREFIX = "nap_alarm_widget_minutes_";
    public static final int DEFAULT_MINUTES = 20;

    public static void saveMinutes(final int appWidgetId, final int minutes) {
        GBApplication.getPrefs().getPreferences().edit()
                .putInt(PREF_MINUTES_PREFIX + appWidgetId, minutes)
                .apply();
    }

    private static int getMinutes(final int appWidgetId) {
        return GBApplication.getPrefs().getInt(PREF_MINUTES_PREFIX + appWidgetId, DEFAULT_MINUTES);
    }

    public static void updateAllWidgets(final Context context) {
        final AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        final int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(context, NapAlarmWidget.class));
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    static void updateAppWidget(Context context, AppWidgetManager appWidgetManager,
                                int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.nap_alarm_widget);
        views.setTextViewText(R.id.napalarmwidget_text, getText(context, appWidgetId));

        Intent intent = new Intent(context, NapAlarmWidget.class);
        intent.setPackage(BuildConfig.APPLICATION_ID);
        intent.setAction(ACTION_CLICK);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        PendingIntent clickPI = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.napalarmwidget_text, clickPI);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    private static String getText(final Context context, final int appWidgetId) {
        if (NapAlarm.isFinished()) {
            return context.getString(R.string.nap_alarm_widget_idle, getMinutes(appWidgetId));
        }
        final long targetMinutes = TimeUnit.MILLISECONDS.toMinutes(NapAlarm.getTargetMillis());
        switch (NapAlarm.getStatus()) {
            case WAITING:
                return context.getString(R.string.nap_alarm_widget_waiting,
                        TimeUnit.MILLISECONDS.toMinutes(NapAlarm.getSleptMillis()), targetMinutes);
            case ASLEEP:
                return context.getString(R.string.nap_alarm_widget_asleep,
                        NapAlarm.formatTime(context, NapAlarm.getWakeAtMillis()));
            default:
                return context.getString(R.string.nap_alarm_widget_idle, getMinutes(appWidgetId));
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            GBApplication.getPrefs().getPreferences().edit().remove(PREF_MINUTES_PREFIX + appWidgetId).apply();
            new WidgetPreferenceStorage().removeWidgetById(context, appWidgetId);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (!ACTION_CLICK.equals(intent.getAction())) {
            return;
        }

        Bundle extras = intent.getExtras();
        int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        if (extras != null) {
            appWidgetId = extras.getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        if (NapAlarm.getStatus() != NapAlarm.Status.INACTIVE && !NapAlarm.isFinished()) {
            NapAlarm.cancel(context, true);
            return;
        }

        GBDevice deviceForWidget = new WidgetPreferenceStorage().getDeviceForWidget(appWidgetId);
        NapAlarm.start(context, deviceForWidget, getMinutes(appWidgetId));
    }
}
