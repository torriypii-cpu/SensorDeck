package jp.sensordeck;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.RemoteViews;
import java.util.Locale;

public class WeatherWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
    }

    @Override public void onAppWidgetOptionsChanged(Context context,
            AppWidgetManager manager,int id,Bundle options) {
        update(context,manager,id);
    }

    static void updateAll(Context context) {
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        ComponentName component=new ComponentName(context,WeatherWidgetProvider.class);
        int[] ids=manager.getAppWidgetIds(component);
        for(int id:ids) update(context,manager,id);
    }

    private static void update(Context context,AppWidgetManager manager,int id) {
        SharedPreferences p=context.getSharedPreferences("weather_widget",Context.MODE_PRIVATE);
        Bundle options=manager.getAppWidgetOptions(id);
        int minWidth=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,110);
        int minHeight=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,50);
        boolean compact=minWidth<220||minHeight<100;
        RemoteViews views=new RemoteViews(context.getPackageName(),
                compact?R.layout.weather_widget_compact:R.layout.weather_widget);
        String place=p.getString("place","SensorDeckを開いて更新");
        float temp=p.getFloat("temp",Float.NaN);
        float max=p.getFloat("max",Float.NaN),min=p.getFloat("min",Float.NaN);
        views.setTextViewText(R.id.widget_place,"⌖  "+place);
        views.setTextViewText(R.id.widget_temp,Float.isNaN(temp)?"--°":
                String.format(Locale.JAPAN,"%.0f°",temp));
        views.setTextViewText(R.id.widget_condition,p.getString("condition","現在地の天気"));
        if(!compact) {
            views.setTextViewText(R.id.widget_range,Float.isNaN(max)?"アプリでGPS予報を更新":
                    String.format(Locale.JAPAN,"↑ %.0f°  ↓ %.0f°",max,min));
        }
        Intent open=new Intent(context,MainActivity.class);
        PendingIntent pending=PendingIntent.getActivity(context,0,open,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root,pending);
        manager.updateAppWidget(id,views);
    }
}
