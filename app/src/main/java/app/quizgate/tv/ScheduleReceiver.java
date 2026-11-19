package app.quizgate.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 当天答题时段开始的精确闹钟；只在 Android 11（API &lt; 31）使用。 */
public class ScheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Schedules.onAlarm(context);
    }
}
