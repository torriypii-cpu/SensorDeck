package jp.sensordeck;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.*;
import android.hardware.*;
import android.location.*;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.ScrollView;
import android.widget.FrameLayout;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity implements SensorEventListener, LocationListener {
    private SensorManager sensors;
    private Dashboard dashboard;
    private LocationManager locationManager;
    private Location lastWeatherLocation;
    private Location cachedWeatherLocation;
    private long lastWeatherFetch;
    private boolean locationUpdatesActive;
    private String weatherNewsUrl = "https://weathernews.jp/";
    private final float[] accel = new float[3], magnetic = new float[3];
    private final Handler uiHandler=new Handler(Looper.getMainLooper());
    private BatteryManager batteryManager;
    private ChargingOverlay chargingOverlay;
    private Intent lastBatteryIntent;
    private boolean batteryReceiverRegistered,batteryInitialized,lastCharging;
    private float filteredChargeCurrent=Float.NaN;

    private final BroadcastReceiver batteryReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            String action=intent.getAction();
            if(Intent.ACTION_POWER_DISCONNECTED.equals(action)){
                lastCharging=false;filteredChargeCurrent=Float.NaN;
                if(chargingOverlay!=null)chargingOverlay.hide();return;
            }
            boolean connected=Intent.ACTION_POWER_CONNECTED.equals(action);
            Intent battery=Intent.ACTION_BATTERY_CHANGED.equals(action)?intent:
                    registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            updateChargingData(battery,connected);
        }
    };

    private final Runnable batteryPoll=new Runnable(){
        @Override public void run(){
            if(chargingOverlay!=null&&lastCharging){
                Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                updateChargingData(battery,false);
            }
            uiHandler.postDelayed(this,750);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        dashboard = new Dashboard(this,
                () -> startActivity(new Intent(this, MapActivity.class)),
                this::openWeatherNews,
                () -> startActivity(new Intent(this, FishingActivity.class)),
                () -> startActivity(new Intent(this, SignalFinderActivity.class)),
                this::showChargingMonitor);
        restoreCachedWeather();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        float density = getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, (int)(32 * density));
        content.addView(dashboard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(2080 * density)));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout root=new FrameLayout(this);root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        chargingOverlay=new ChargingOverlay(this);root.addView(chargingOverlay,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        batteryManager=(BatteryManager)getSystemService(BATTERY_SERVICE);
        IntentFilter batteryFilter=new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        batteryFilter.addAction(Intent.ACTION_POWER_CONNECTED);batteryFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(batteryReceiver,batteryFilter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(batteryReceiver,batteryFilter);
        batteryReceiverRegistered=true;
        sensors = (SensorManager)getSystemService(SENSOR_SERVICE);
        register(Sensor.TYPE_PRESSURE); register(Sensor.TYPE_ACCELEROMETER);
        register(Sensor.TYPE_GYROSCOPE); register(Sensor.TYPE_MAGNETIC_FIELD);
        register(Sensor.TYPE_LIGHT); register(Sensor.TYPE_PROXIMITY);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 10);
        else startLocation();
    }

    private void updateChargingData(Intent battery,boolean forceShow){
        if(battery==null||batteryManager==null||chargingOverlay==null)return;
        lastBatteryIntent=battery;
        int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,0);
        int scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,100);
        int percent=scale>0?Math.max(0,Math.min(100,Math.round(level*100f/scale))):0;
        int status=battery.getIntExtra(BatteryManager.EXTRA_STATUS,BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plugged=battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0);
        boolean charging=plugged!=0&&(status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL);
        float voltage=battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE,0)/1000f;
        if(voltage<2.5f||voltage>6f)voltage=Float.NaN;
        int rawCurrent=batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if(rawCurrent==Integer.MIN_VALUE||Math.abs((long)rawCurrent)<10_000L)
            rawCurrent=batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
        float current=Float.NaN;
        if(rawCurrent!=Integer.MIN_VALUE){
            float measured=Math.abs(rawCurrent)/1_000_000f;
            if(measured>=.01f&&measured<=15f){
                filteredChargeCurrent=Float.isNaN(filteredChargeCurrent)?measured:filteredChargeCurrent*.66f+measured*.34f;
                current=filteredChargeCurrent;
            }
        }
        if(!charging)filteredChargeCurrent=Float.NaN;
        String source=plugged==BatteryManager.BATTERY_PLUGGED_WIRELESS?"ワイヤレス充電":
                plugged==BatteryManager.BATTERY_PLUGGED_USB?"USB充電":
                plugged==BatteryManager.BATTERY_PLUGGED_AC?"有線充電":"未接続";
        chargingOverlay.setChargingData(percent,current,voltage,source,charging);
        boolean first=!batteryInitialized,justConnected=charging&&!lastCharging;
        batteryInitialized=true;lastCharging=charging;
        if(charging&&(forceShow||first||justConnected))chargingOverlay.showFor(9000);
        else if(!charging)chargingOverlay.hide();
    }

    private void showChargingMonitor(){
        Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        updateChargingData(battery,false);chargingOverlay.showFor(30_000);
    }

    private void register(int type) {
        Sensor s = sensors.getDefaultSensor(type);
        dashboard.available.put(type, s != null);
        if (s != null) sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
    }

    private void startLocation() {
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        if(locationUpdatesActive)return;
        Location quickest=null;
        try {
            Location gps=locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network=locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            quickest=newerLocation(gps,network);
            if(quickest==null)quickest=cachedWeatherLocation;
            if(quickest!=null){dashboard.location=quickest;dashboard.invalidateSensors();fetchWeather(quickest);}
            if(locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,1000,0,this);
            if(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this);
            locationUpdatesActive=true;
        } catch(SecurityException ignored) {locationUpdatesActive=false;}
    }

    private static Location newerLocation(Location first,Location second){
        if(first==null)return second;if(second==null)return first;
        return first.getTime()>=second.getTime()?first:second;
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r,p,g); if (r == 10 && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startLocation();
    }

    @Override public void onSensorChanged(SensorEvent e) {
        dashboard.values.put(e.sensor.getType(), e.values.clone());
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) System.arraycopy(e.values,0,accel,0,3);
        if (e.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) System.arraycopy(e.values,0,magnetic,0,3);
        if (e.sensor.getType() == Sensor.TYPE_PRESSURE) dashboard.addPressure(e.values[0]);
        float[] r = new float[9], o = new float[3];
        if (SensorManager.getRotationMatrix(r,null,accel,magnetic)) {
            SensorManager.getOrientation(r,o); dashboard.heading = (float)((Math.toDegrees(o[0])+360)%360);
        }
        dashboard.invalidateSensors();
    }
    @Override public void onAccuracyChanged(Sensor s,int a) {}
    @Override public void onLocationChanged(Location l) {
        dashboard.location=l;
        dashboard.invalidateSensors();
        fetchWeather(l);
    }

    private void fetchWeather(Location location) {
        long now = System.currentTimeMillis();
        if (lastWeatherLocation != null
                && lastWeatherLocation.distanceTo(location) < 5000
                && now - lastWeatherFetch < 30 * 60 * 1000) return;
        lastWeatherLocation = new Location(location);
        lastWeatherFetch = now;
        dashboard.weather = "現在地の予報を取得中…";
        dashboard.invalidateWeather();
        double lat = location.getLatitude(), lon = location.getLongitude();
        fetchPlaceAndWeatherNews(lat,lon);
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String endpoint = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
                        + "&current=temperature_2m,apparent_temperature,weather_code,is_day"
                        + "&hourly=temperature_2m,apparent_temperature,weather_code,precipitation_probability,precipitation"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum"
                        + "&timezone=auto&forecast_days=4", lat, lon);
                connection = (HttpURLConnection)new URL(endpoint).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                String response=body.toString();
                JSONObject root = new JSONObject(response);
                JSONObject current = root.getJSONObject("current");
                JSONObject daily = root.getJSONObject("daily");
                JSONObject hourly = root.getJSONObject("hourly");
                JSONArray codes = daily.getJSONArray("weather_code");
                JSONArray max = daily.getJSONArray("temperature_2m_max");
                JSONArray min = daily.getJSONArray("temperature_2m_min");
                JSONArray rain = daily.getJSONArray("precipitation_probability_max");
                JSONArray times = hourly.getJSONArray("time");
                JSONArray hourlyTemps = hourly.getJSONArray("temperature_2m");
                JSONArray hourlyCodes = hourly.getJSONArray("weather_code");
                JSONArray hourlyRain = hourly.getJSONArray("precipitation_probability");
                JSONArray hourlyPrecipitation = hourly.getJSONArray("precipitation");
                JSONArray dailyPrecipitation = daily.getJSONArray("precipitation_sum");
                JSONArray dailyTimes = daily.getJSONArray("time");
                String currentTime = current.getString("time");
                int start = 0;
                while (start < times.length()-1
                        && times.getString(start).compareTo(currentTime) < 0) start++;
                int forecastCount=Math.min(72,times.length()-start);
                String[] nextTimes = new String[forecastCount];
                float[] nextTemps = new float[forecastCount], nextPrecipitation = new float[forecastCount];
                int[] nextCodes = new int[forecastCount], nextRain = new int[forecastCount];
                for (int i=0;i<forecastCount;i++) {
                    int index=Math.min(start+i,times.length()-1);
                    nextTimes[i]=times.getString(index);
                    nextTemps[i]=(float)hourlyTemps.getDouble(index);
                    nextCodes[i]=hourlyCodes.getInt(index);
                    nextRain[i]=hourlyRain.getInt(index);
                    nextPrecipitation[i]=(float)hourlyPrecipitation.optDouble(index,0);
                }
                int dayCount=Math.min(4,dailyTimes.length());
                String[] nextDays=new String[dayCount];float[] dayMax=new float[dayCount],dayMin=new float[dayCount],dayPrecipitation=new float[dayCount];
                int[] dayCodes=new int[dayCount],dayRain=new int[dayCount];
                for(int i=0;i<dayCount;i++){
                    nextDays[i]=dailyTimes.getString(i);dayMax[i]=(float)max.getDouble(i);dayMin[i]=(float)min.getDouble(i);
                    dayCodes[i]=codes.getInt(i);dayRain[i]=rain.getInt(i);
                    dayPrecipitation[i]=(float)dailyPrecipitation.optDouble(i,0);
                }
                String result = String.format(Locale.JAPAN,
                        "現在 %.1f℃  %s\n今日 %.0f〜%.0f℃  降水%d%%\n明日 %.0f〜%.0f℃  %s  降水%d%%",
                        current.getDouble("temperature_2m"),
                        weatherName(current.getInt("weather_code")),
                        min.getDouble(0), max.getDouble(0), rain.getInt(0),
                        min.getDouble(1), max.getDouble(1),
                        weatherName(codes.getInt(1)), rain.getInt(1));
                runOnUiThread(() -> {
                    applyWeather(result,(float)current.optDouble("temperature_2m",Float.NaN),
                            (float)current.optDouble("apparent_temperature",Float.NaN),
                            current.optInt("weather_code",0),current.optInt("is_day",1)==1,
                            (float)max.optDouble(0,Float.NaN),(float)min.optDouble(0,Float.NaN),
                            nextTimes,nextTemps,nextCodes,nextRain,nextPrecipitation,
                            nextDays,dayMax,dayMin,dayCodes,dayRain,dayPrecipitation);
                    getSharedPreferences("weather_cache",MODE_PRIVATE).edit()
                            .putString("response",response).putLong("updated",System.currentTimeMillis())
                            .putString("place",dashboard.placeName).putString("weather_news_url",weatherNewsUrl)
                            .putLong("latitude",Double.doubleToRawLongBits(lat))
                            .putLong("longitude",Double.doubleToRawLongBits(lon)).apply();
                    getSharedPreferences("weather_widget",MODE_PRIVATE).edit()
                            .putString("place",dashboard.placeName)
                            .putString("condition",weatherName(current.optInt("weather_code",0)))
                            .putFloat("temp",(float)current.optDouble("temperature_2m",Float.NaN))
                            .putFloat("max",(float)max.optDouble(0,Float.NaN))
                            .putFloat("min",(float)min.optDouble(0,Float.NaN))
                            .putLong("updated",System.currentTimeMillis()).apply();
                    WeatherWidgetProvider.updateAll(this);
                    dashboard.invalidateWeather();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dashboard.weather = "天気予報を取得できませんでした";
                    dashboard.invalidateWeather();
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void restoreCachedWeather(){
        android.content.SharedPreferences cache=getSharedPreferences("weather_cache",MODE_PRIVATE);
        android.content.SharedPreferences widget=getSharedPreferences("weather_widget",MODE_PRIVATE);
        String response=cache.getString("response",null);
        dashboard.placeName=cache.getString("place",widget.getString("place","現在地を測位中"));
        if(widget.contains("temp")){
            dashboard.currentTemp=widget.getFloat("temp",Float.NaN);dashboard.todayMax=widget.getFloat("max",Float.NaN);
            dashboard.todayMin=widget.getFloat("min",Float.NaN);dashboard.currentCode=weatherCode(widget.getString("condition",""));
            Calendar now=Calendar.getInstance();dashboard.isDay=now.get(Calendar.HOUR_OF_DAY)>=6&&now.get(Calendar.HOUR_OF_DAY)<18;
        }
        weatherNewsUrl=cache.getString("weather_news_url",weatherNewsUrl);
        long latBits=cache.getLong("latitude",Long.MIN_VALUE),lonBits=cache.getLong("longitude",Long.MIN_VALUE);
        if(latBits!=Long.MIN_VALUE&&lonBits!=Long.MIN_VALUE){
            cachedWeatherLocation=new Location("weather-cache");
            cachedWeatherLocation.setLatitude(Double.longBitsToDouble(latBits));
            cachedWeatherLocation.setLongitude(Double.longBitsToDouble(lonBits));
            cachedWeatherLocation.setTime(cache.getLong("updated",0));
        }
        if(response==null||response.isEmpty())return;
        try{
            JSONObject root=new JSONObject(response),current=root.getJSONObject("current");
            JSONObject daily=root.getJSONObject("daily"),hourly=root.getJSONObject("hourly");
            JSONArray codes=daily.getJSONArray("weather_code"),max=daily.getJSONArray("temperature_2m_max");
            JSONArray min=daily.getJSONArray("temperature_2m_min"),rain=daily.getJSONArray("precipitation_probability_max");
            JSONArray times=hourly.getJSONArray("time"),hourlyTemps=hourly.getJSONArray("temperature_2m");
            JSONArray hourlyApparent=hourly.optJSONArray("apparent_temperature");
            JSONArray hourlyCodes=hourly.getJSONArray("weather_code"),hourlyRain=hourly.getJSONArray("precipitation_probability");
            JSONArray hourlyPrecipitation=hourly.optJSONArray("precipitation"),dailyPrecipitation=daily.optJSONArray("precipitation_sum");
            String currentTime=current.getString("time");int start=0;
            while(start<times.length()-1&&times.getString(start).compareTo(currentTime)<0)start++;
            long updated=cache.getLong("updated",System.currentTimeMillis());
            int elapsedHours=(int)Math.max(0,(System.currentTimeMillis()-updated)/(60*60*1000L));
            start=Math.min(times.length()-1,start+elapsedHours);
            JSONArray dailyTimes=daily.getJSONArray("time");String targetDate=times.getString(start).substring(0,10);
            int dayIndex=0;
            while(dayIndex<dailyTimes.length()-1&&!dailyTimes.getString(dayIndex).equals(targetDate))dayIndex++;
            int tomorrowIndex=Math.min(dayIndex+1,dailyTimes.length()-1);
            int count=Math.min(72,times.length()-start);String[] nextTimes=new String[count];
            float[] nextTemps=new float[count],nextPrecipitation=new float[count];
            int[] nextCodes=new int[count],nextRain=new int[count];
            for(int i=0;i<count;i++){int index=start+i;nextTimes[i]=times.getString(index);
                nextTemps[i]=(float)hourlyTemps.getDouble(index);nextCodes[i]=hourlyCodes.getInt(index);nextRain[i]=hourlyRain.getInt(index);
                nextPrecipitation[i]=hourlyPrecipitation==null?0f:(float)hourlyPrecipitation.optDouble(index,0);}
            int dayCount=Math.min(4,dailyTimes.length()-dayIndex);String[] nextDays=new String[dayCount];
            float[] dayMax=new float[dayCount],dayMin=new float[dayCount],dayPrecipitation=new float[dayCount];
            int[] dayCodes=new int[dayCount],dayRain=new int[dayCount];
            for(int i=0;i<dayCount;i++){int index=dayIndex+i;nextDays[i]=dailyTimes.getString(index);
                dayMax[i]=(float)max.getDouble(index);dayMin[i]=(float)min.getDouble(index);
                dayCodes[i]=codes.getInt(index);dayRain[i]=rain.getInt(index);
                dayPrecipitation[i]=dailyPrecipitation==null?0f:(float)dailyPrecipitation.optDouble(index,0);}
            String result=String.format(Locale.JAPAN,
                    "現在 %.1f℃  %s\n今日 %.0f〜%.0f℃  降水%d%%\n明日 %.0f〜%.0f℃  %s  降水%d%%",
                    hourlyTemps.getDouble(start),weatherName(hourlyCodes.getInt(start)),
                    min.getDouble(dayIndex),max.getDouble(dayIndex),rain.getInt(dayIndex),
                    min.getDouble(tomorrowIndex),max.getDouble(tomorrowIndex),
                    weatherName(codes.getInt(tomorrowIndex)),rain.getInt(tomorrowIndex));
            int localHour=Integer.parseInt(times.getString(start).substring(11,13));
            applyWeather(result,(float)hourlyTemps.optDouble(start,Float.NaN),
                    hourlyApparent==null?(float)current.optDouble("apparent_temperature",Float.NaN):
                    (float)hourlyApparent.optDouble(start,Float.NaN),hourlyCodes.optInt(start,0),
                    localHour>=6&&localHour<18,(float)max.optDouble(dayIndex,Float.NaN),(float)min.optDouble(dayIndex,Float.NaN),
                    nextTimes,nextTemps,nextCodes,nextRain,nextPrecipitation,
                    nextDays,dayMax,dayMin,dayCodes,dayRain,dayPrecipitation);
        }catch(Exception ignored){}
    }

    private void applyWeather(String summary,float currentTemp,float apparentTemp,int currentCode,boolean isDay,
                              float todayMax,float todayMin,String[] times,float[] temps,int[] codes,int[] rain,
                              float[] precipitation,String[] days,float[] dayMax,float[] dayMin,int[] dayCodes,
                              int[] dayRain,float[] dayPrecipitation){
        dashboard.weather=summary;dashboard.currentTemp=currentTemp;dashboard.apparentTemp=apparentTemp;
        dashboard.currentCode=currentCode;dashboard.isDay=isDay;dashboard.todayMax=todayMax;dashboard.todayMin=todayMin;
        dashboard.hourTimes=times;dashboard.hourTemps=temps;dashboard.hourCodes=codes;dashboard.hourRain=rain;
        dashboard.hourPrecipitation=precipitation;
        dashboard.dayTimes=days;dashboard.dayMax=dayMax;dashboard.dayMin=dayMin;
        dashboard.dayCodes=dayCodes;dashboard.dayRain=dayRain;dashboard.dayPrecipitation=dayPrecipitation;
        dashboard.hourScroller.forceFinished(true);dashboard.hourOffset=0;dashboard.hourPosition=0;dashboard.invalidateWeather();
    }

    private void fetchPlaceAndWeatherNews(double lat,double lon) {
        new Thread(() -> {
            HttpURLConnection connection=null;
            try {
                String endpoint=String.format(Locale.US,
                        "https://mreversegeocoder.gsi.go.jp/reverse-geocoder/LonLatToAddress"
                        +"?lat=%.6f&lon=%.6f",lat,lon);
                connection=(HttpURLConnection)new URL(endpoint).openConnection();
                connection.setConnectTimeout(6000);connection.setReadTimeout(6000);
                StringBuilder body=new StringBuilder();
                try(BufferedReader reader=new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))){
                    String line;while((line=reader.readLine())!=null)body.append(line);
                }
                JSONObject result=new JSONObject(body.toString()).getJSONObject("results");
                String municipality=result.getString("muniCd");
                String place=result.optString("lv01Nm","現在地");
                int prefecture=Integer.parseInt(municipality.substring(0,2));
                String slug=prefectureSlug(prefecture);
                String url=slug==null?"https://weathernews.jp/":
                        "https://weathernews.jp/onebox/tenki/"+slug+"/"+municipality+"/";
                runOnUiThread(() -> {
                    dashboard.placeName=place;weatherNewsUrl=url;dashboard.invalidateWeather();
                    getSharedPreferences("weather_cache",MODE_PRIVATE).edit()
                            .putString("place",place).putString("weather_news_url",url).apply();
                    getSharedPreferences("weather_widget",MODE_PRIVATE).edit()
                            .putString("place",place).apply();
                    WeatherWidgetProvider.updateAll(this);
                });
            } catch(Exception ignored) {
            } finally {
                if(connection!=null)connection.disconnect();
            }
        }).start();
    }

    private void openWeatherNews() {
        startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(weatherNewsUrl)));
    }

    private static String prefectureSlug(int code) {
        String[] slugs={null,"hokkaido","aomori","iwate","miyagi","akita","yamagata",
                "fukushima","ibaraki","tochigi","gunma","saitama","chiba","tokyo",
                "kanagawa","niigata","toyama","ishikawa","fukui","yamanashi","nagano",
                "gifu","shizuoka","aichi","mie","shiga","kyoto","osaka","hyogo","nara",
                "wakayama","tottori","shimane","okayama","hiroshima","yamaguchi",
                "tokushima","kagawa","ehime","kochi","fukuoka","saga","nagasaki",
                "kumamoto","oita","miyazaki","kagoshima","okinawa"};
        return code>=1&&code<slugs.length?slugs[code]:null;
    }

    private static String weatherName(int code) {
        if (code == 0) return "快晴";
        if (code <= 3) return "晴れ／曇り";
        if (code == 45 || code == 48) return "霧";
        if (code <= 57) return "霧雨";
        if (code <= 67) return "雨";
        if (code <= 77) return "雪";
        if (code <= 82) return "にわか雨";
        if (code <= 86) return "にわか雪";
        return "雷雨";
    }
    private static int weatherCode(String name){
        if(name.contains("雷"))return 95;if(name.contains("雪"))return 71;if(name.contains("霧雨"))return 51;
        if(name.contains("雨"))return 61;if(name.contains("霧"))return 45;if(name.contains("晴")||name.contains("曇"))return 2;return 0;
    }
    @Override protected void onPause(){
        super.onPause();
        uiHandler.removeCallbacks(batteryPoll);
        sensors.unregisterListener(this);
        if(locationManager!=null) locationManager.removeUpdates(this);locationUpdatesActive=false;
    }
    @Override protected void onResume(){
        super.onResume();
        uiHandler.removeCallbacks(batteryPoll);uiHandler.post(batteryPoll);
        if(sensors!=null){
            register(Sensor.TYPE_PRESSURE);register(Sensor.TYPE_ACCELEROMETER);
            register(Sensor.TYPE_GYROSCOPE);register(Sensor.TYPE_MAGNETIC_FIELD);
            register(Sensor.TYPE_LIGHT);register(Sensor.TYPE_PROXIMITY);
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) startLocation();
        }
    }

    @Override protected void onDestroy(){
        uiHandler.removeCallbacks(batteryPoll);
        if(batteryReceiverRegistered){unregisterReceiver(batteryReceiver);batteryReceiverRegistered=false;}
        super.onDestroy();
    }

    @Override public void onBackPressed(){
        if(chargingOverlay!=null&&chargingOverlay.getVisibility()==View.VISIBLE){chargingOverlay.hide();return;}
        super.onBackPressed();
    }

    static class ChargingOverlay extends View {
        final Paint paint=new Paint(3);
        final Typeface normal=Typeface.create("sans",Typeface.NORMAL),bold=Typeface.create("sans",Typeface.BOLD);
        final Runnable hideTask=this::hide;
        int percent;float currentAmps=Float.NaN,voltage=Float.NaN;String source="充電中";
        boolean charging;long shownAt;

        ChargingOverlay(Context context){
            super(context);setVisibility(GONE);setClickable(true);setOnClickListener(v->hide());
            setContentDescription("充電電力表示。タップで閉じる");
        }
        void setChargingData(int percent,float current,float voltage,String source,boolean charging){
            this.percent=percent;currentAmps=current;this.voltage=voltage;this.source=source;this.charging=charging;
            if(getVisibility()==VISIBLE)postInvalidateOnAnimation();
        }
        void showFor(long duration){
            removeCallbacks(hideTask);animate().cancel();setAlpha(1f);shownAt=SystemClock.elapsedRealtime();
            setVisibility(VISIBLE);bringToFront();postDelayed(hideTask,duration);postInvalidateOnAnimation();
        }
        void hide(){
            removeCallbacks(hideTask);if(getVisibility()!=VISIBLE)return;
            animate().alpha(0f).setDuration(240).withEndAction(()->{setVisibility(GONE);setAlpha(1f);}).start();
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float d=getResources().getDisplayMetrics().density;
            canvas.save();canvas.scale(d,d);float w=getWidth()/d,h=getHeight()/d,cx=w/2,cy=Math.min(330,h*.38f);
            LinearGradient background=new LinearGradient(0,0,0,h,Color.rgb(2,8,18),Color.rgb(0,20,25),Shader.TileMode.CLAMP);
            paint.setShader(background);canvas.drawRect(0,0,w,h,paint);paint.setShader(null);
            long elapsed=SystemClock.elapsedRealtime()-shownAt;
            float entrance=Math.min(1f,elapsed/950f);entrance=1-(1-entrance)*(1-entrance)*(1-entrance);
            float pulse=(float)((Math.sin(elapsed/330.0)+1)/2.0),radius=78;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);
            for(int i=3;i>=1;i--){paint.setStrokeWidth(8+i*7);paint.setColor(Color.argb((int)(11+pulse*8),40,255,202));
                canvas.drawCircle(cx,cy,radius+i*3,paint);}
            paint.setStrokeWidth(8);paint.setColor(Color.argb(60,150,180,190));canvas.drawCircle(cx,cy,radius,paint);
            float sweep=360f*Math.max(.02f,percent/100f)*entrance;
            paint.setStrokeWidth(9);paint.setColor(Color.rgb(31,236,183));
            canvas.drawArc(cx-radius,cy-radius,cx+radius,cy+radius,-90,sweep,false,paint);
            double dotAngle=Math.toRadians(-90+sweep);paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);canvas.drawCircle(cx+(float)Math.cos(dotAngle)*radius,cy+(float)Math.sin(dotAngle)*radius,5+pulse*2,paint);
            centered(canvas,percent+"%",cx,cy+18,44,Color.WHITE,true);
            centered(canvas,source,cx,cy+122,16,Color.rgb(127,241,211),true);
            if(!charging){
                centered(canvas,"充電器を接続してください",cx,cy+168,22,Color.WHITE,true);
                centered(canvas,"接続するとW・A・Vを表示します",cx,cy+199,15,Color.rgb(205,222,230),false);
            }else if(!Float.isNaN(currentAmps)&&!Float.isNaN(voltage)&&voltage>0){
                centered(canvas,String.format(Locale.JAPAN,"推定 %.1f W",currentAmps*voltage),cx,cy+168,28,Color.WHITE,true);
                centered(canvas,String.format(Locale.JAPAN,"%.2f A   •   %.2f V",currentAmps,voltage),cx,cy+199,17,Color.rgb(205,222,230),false);
            }else{
                centered(canvas,"電力を計測中…",cx,cy+168,24,Color.WHITE,true);
                if(!Float.isNaN(voltage)&&voltage>0)centered(canvas,String.format(Locale.JAPAN,"-- A   •   %.2f V",voltage),cx,cy+199,17,Color.rgb(205,222,230),false);
            }
            centered(canvas,"電池側の瞬間電流から計算した推定値",cx,cy+240,12,Color.rgb(127,153,165),false);
            centered(canvas,"タップで閉じる",cx,h-42,12,Color.rgb(105,132,144),false);
            paint.setStrokeCap(Paint.Cap.BUTT);canvas.restore();
            if(getVisibility()==VISIBLE)postInvalidateOnAnimation();
        }
        void centered(Canvas canvas,String text,float x,float y,float size,int color,boolean isBold){
            paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(size);paint.setTypeface(isBold?bold:normal);paint.setColor(color);
            canvas.drawText(text,x,y,paint);paint.setTextAlign(Paint.Align.LEFT);
        }
    }

    static class Dashboard extends View {
        final Paint p = new Paint(3); final Map<Integer,float[]> values=new HashMap<>();
        final Map<Integer,Boolean> available=new HashMap<>(); final ArrayDeque<Float> history=new ArrayDeque<>();
        final Rect drawClip=new Rect();
        final Typeface normalTypeface=Typeface.create("sans",Typeface.NORMAL);
        final Typeface boldTypeface=Typeface.create("sans",Typeface.BOLD);
        Location location; float heading; String weather="GPS測位後に予報を表示";
        String placeName="現在地を測位中";
        float currentTemp=Float.NaN,apparentTemp=Float.NaN,todayMax=Float.NaN,todayMin=Float.NaN;
        int currentCode; boolean isDay=true;
        String[] hourTimes,dayTimes; float[] hourTemps,hourPrecipitation,dayMax,dayMin,dayPrecipitation;
        int[] hourCodes,hourRain,dayCodes,dayRain;
        final Runnable mapAction, weatherAction, fishingAction, signalAction,chargingAction; int pressedCard,hourOffset;
        float hourPosition,touchStartHourPosition;
        boolean draggingHours,pendingSensorRedraw;
        final OverScroller hourScroller;
        final int minimumFlingVelocity,maximumFlingVelocity;
        VelocityTracker velocityTracker;
        float touchDownX,touchDownY;
        final int bg=Color.rgb(7,17,31), card=Color.rgb(16,31,48), mint=Color.rgb(0,212,170), white=Color.rgb(238,246,252), muted=Color.rgb(143,163,180);
        Dashboard(Context c,Runnable mapAction,Runnable weatherAction,Runnable fishingAction,Runnable signalAction,Runnable chargingAction){
            super(c);this.mapAction=mapAction;this.weatherAction=weatherAction;
            this.fishingAction=fishingAction;this.signalAction=signalAction;this.chargingAction=chargingAction;
            p.setTypeface(normalTypeface);
            hourScroller=new OverScroller(c);
            hourScroller.setFriction(ViewConfiguration.getScrollFriction()*1.35f);
            ViewConfiguration configuration=ViewConfiguration.get(c);
            minimumFlingVelocity=configuration.getScaledMinimumFlingVelocity();
            maximumFlingVelocity=configuration.getScaledMaximumFlingVelocity();
            setBackgroundColor(bg);setClickable(true);
            setContentDescription("GPS地図と現在地の天気予報");
        }
        void addPressure(float v){if(history.size()>=80)history.removeFirst();history.addLast(v);}
        String val(int t,int i,String unit){float[]v=values.get(t);return v==null?"計測中…":String.format(Locale.JAPAN,"%.2f %s",v[i],unit);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float density=getResources().getDisplayMetrics().density;
            c.getClipBounds(drawClip);boolean weatherOnly=drawClip.bottom<=(int)(770*density),sensorsOnly=drawClip.top>=(int)(750*density);
            c.save();c.scale(density,density);float w=getWidth()/density, pad=24, y;
            if(!sensorsOnly)weatherHero(c,w);
            if(weatherOnly){c.restore();return;}
            y=790;text(c,"SENSOR DECK",pad,y,28,white,true);text(c,"Galaxy S25 • LIVE",pad,y+28,13,mint,false);y+=70;
            float pressure=values.containsKey(Sensor.TYPE_PRESSURE)?values.get(Sensor.TYPE_PRESSURE)[0]:Float.NaN;
            box(c,pad,y,w-pad,y+190);text(c,"気圧",pad+18,y+30,14,muted,false);
            text(c,Float.isNaN(pressure)?"計測中…":String.format(Locale.JAPAN,"%.1f hPa",pressure),pad+18,y+72,32,white,true);
            if(!Float.isNaN(pressure))text(c,String.format(Locale.JAPAN,"推定高度  %.0f m",SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE,pressure)),pad+18,y+100,15,mint,false);
            graph(c,pad+18,y+116,w-pad-18,y+172);y+=206;
            float gap=12,cw=(w-pad*2-gap)/2,ch=112;
            sensorBox(c,pad,y,pad+cw,y+ch,"方角",String.format(Locale.JAPAN,"%.0f°  %s",heading,dir(heading)));
            sensorBox(c,pad+cw+gap,y,w-pad,y+ch,"照度",val(Sensor.TYPE_LIGHT,0,"lux"));y+=ch+gap;
            sensorBox(c,pad,y,pad+cw,y+ch,"加速度",vector(Sensor.TYPE_ACCELEROMETER,"m/s²"));
            sensorBox(c,pad+cw+gap,y,w-pad,y+ch,"ジャイロ",vector(Sensor.TYPE_GYROSCOPE,"rad/s"));y+=ch+gap;
            sensorBox(c,pad,y,pad+cw,y+ch,"磁場",vector(Sensor.TYPE_MAGNETIC_FIELD,"µT"));
            sensorBox(c,pad+cw+gap,y,w-pad,y+ch,"近接",val(Sensor.TYPE_PROXIMITY,0,"cm"));y+=ch+gap;
            String gps=location==null?"測位中…":String.format(Locale.JAPAN,"%.5f, %.5f\n高度 %.0fm  速度 %.1fkm/h",location.getLatitude(),location.getLongitude(),location.getAltitude(),location.getSpeed()*3.6);
            gpsBox(c,pad,y,w-pad,y+128);text(c,"GPS / QZSS  •  タップで地図",pad+16,y+28,13,mint,false);multi(c,gps,pad+16,y+58,16,white);y+=144;
            fishingBox(c,pad,y,w-pad,y+88);text(c,"釣りモード  •  タップで開く",pad+16,y+30,13,mint,false);text(c,"波・風・海水温・潮の満ち引き",pad+16,y+62,16,white,true);y+=104;
            signalBox(c,pad,y,w-pad,y+88);text(c,"電波ファインダー  •  タップで開く",pad+16,y+30,13,mint,false);text(c,"Bluetooth・Wi-Fiから物を探す",pad+16,y+62,16,white,true);y+=104;
            chargingBox(c,pad,y,w-pad,y+88);text(c,"充電モニター  •  タップで開く",pad+16,y+30,13,mint,false);text(c,"推定W・A・Vをリアルタイム表示",pad+16,y+62,16,white,true);y+=104;
            box(c,pad,y,w-pad,y+82);text(c,"本体非搭載",pad+16,y+28,13,muted,false);text(c,"温度・湿度・心拍・水深",pad+16,y+58,15,white,false);c.restore();
        }
        void weatherHero(Canvas c,float w){
            int top=skyTop();
            LinearGradient gradient=new LinearGradient(0,0,0,760,
                    new int[]{top,skyMiddle(),bg},new float[]{0f,.58f,1f},Shader.TileMode.CLAMP);
            p.setShader(gradient);c.drawRect(0,0,w,760,p);p.setShader(null);
            drawSkyDecoration(c,w);
            String shownPlace=placeName.length()>7?placeName.substring(0,7)+"…":placeName;
            text(c,"現在地  •  "+shownPlace,24,42,16,white,true);
            p.setColor(pressedCard==4?Color.argb(150,255,255,255):Color.argb(72,255,255,255));
            c.drawRoundRect(w-112,20,w-20,58,19,19,p);text(c,"詳細  ›",w-88,45,13,white,true);
            String temp=Float.isNaN(currentTemp)?"--°":String.format(Locale.JAPAN,"%.0f°",currentTemp);
            text(c,temp,24,130,74,white,false);
            drawWeatherIcon(c,w-82,123,1.05f,currentCode,isDay);
            text(c,weatherName(currentCode),28,176,24,white,true);
            String range=Float.isNaN(todayMax)?weather:Float.isNaN(apparentTemp)?
                    String.format(Locale.JAPAN,"最高 %.0f°  最低 %.0f°",todayMax,todayMin):
                    String.format(Locale.JAPAN,"最高 %.0f°  最低 %.0f°   体感 %.0f°",todayMax,todayMin,apparentTemp);
            text(c,range,28,207,15,Color.argb(225,255,255,255),true);

            p.setColor(pressedCard==2?Color.argb(118,10,42,76):Color.argb(76,8,25,55));
            c.drawRoundRect(18,234,w-18,494,26,26,p);
            text(c,"時間ごとの天気",34,267,14,white,true);
            text(c,"指でスライド",w-116,267,11,Color.argb(190,255,255,255),false);
            if(hourTimes==null){text(c,"GPS測位後に予報を表示します",34,320,15,white,false);}
            else drawHourlyForecast(c,w);

            p.setColor(Color.argb(76,8,25,55));c.drawRoundRect(18,512,w-18,744,26,26,p);
            text(c,"4日間の天気",34,545,14,white,true);
            if(dayTimes==null){text(c,"最新の予報を取得しています",34,585,15,white,false);}
            else drawDailyForecast(c,w);
        }
        void drawHourlyForecast(Canvas c,float w){
            float left=34,right=w-34,col=(right-left)/6f,min=Float.MAX_VALUE,max=-Float.MAX_VALUE;
            int count=Math.min(6,hourTemps.length-hourOffset);
            for(float v:hourTemps){min=Math.min(min,v);max=Math.max(max,v);}
            if(max-min<1){max+=.5f;min-=.5f;}
            int first=Math.max(0,(int)Math.floor(hourPosition));
            int last=Math.min(hourTemps.length-1,(int)Math.ceil(hourPosition)+6);Path line=new Path();
            c.save();c.clipRect(24,278,w-24,484);
            for(int dataIndex=first;dataIndex<=last;dataIndex++){
                float x=left+col*(dataIndex-hourPosition+.5f);
                String time=dataIndex==0?"今":hourTimes[dataIndex].substring(11,16);
                boolean newDay=dataIndex>0&&!hourTimes[dataIndex].substring(0,10).equals(hourTimes[dataIndex-1].substring(0,10));
                if(newDay){String date=hourTimes[dataIndex].substring(5,10).replace('-', '/');text(c,date,x-15,288,9,white,true);}
                text(c,time,x-(dataIndex==0?7:15),309,12,Color.argb(210,255,255,255),false);
                int localHour=Integer.parseInt(hourTimes[dataIndex].substring(11,13));
                drawWeatherIcon(c,x,345,.34f,hourCodes[dataIndex],localHour>=6&&localHour<18);
                text(c,String.format(Locale.JAPAN,"%.0f°",hourTemps[dataIndex]),x-13,383,16,white,true);
                float gy=425-(hourTemps[dataIndex]-min)/(max-min)*24;
                if(dataIndex==first)line.moveTo(x,gy);else line.lineTo(x,gy);
                p.setColor(white);c.drawCircle(x,gy,3,p);
                text(c,hourRain[dataIndex]+"%  "+formatRainMm(hourPrecipitation[dataIndex]),x-25,463,9,Color.rgb(142,210,255),true);
            }
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(Color.argb(185,255,255,255));c.drawPath(line,p);p.setStyle(Paint.Style.FILL);
            c.restore();
            text(c,(hourOffset+1)+"–"+(hourOffset+count)+" / "+hourTemps.length+"時間",w-111,484,10,Color.argb(175,255,255,255),false);
        }
        void drawDailyForecast(Canvas c,float w){
            int count=Math.min(4,dayTimes.length);
            for(int i=0;i<count;i++){
                float y=577+i*40;
                if(i>0){p.setColor(Color.argb(38,255,255,255));c.drawRect(34,y-21,w-34,y-20,p);}
                text(c,dayLabel(dayTimes[i],i),40,y,14,white,i<2);
                drawWeatherIcon(c,126,y-5,.25f,dayCodes[i],true);
                text(c,dayRain[i]+"%  "+formatRainMm(dayPrecipitation[i]),153,y,10,Color.rgb(142,210,255),true);
                text(c,String.format(Locale.JAPAN,"%.0f°",dayMin[i]),w-104,y,14,Color.argb(190,255,255,255),false);
                text(c,String.format(Locale.JAPAN,"%.0f°",dayMax[i]),w-58,y,14,white,true);
            }
        }
        String dayLabel(String iso,int index){
            if(index==0)return "今日";if(index==1)return "明日";
            try{int month=Integer.parseInt(iso.substring(5,7)),day=Integer.parseInt(iso.substring(8,10));
                Calendar calendar=Calendar.getInstance();calendar.set(Integer.parseInt(iso.substring(0,4)),month-1,day);
                String[] week={"日","月","火","水","木","金","土"};
                return month+"/"+day+"（"+week[calendar.get(Calendar.DAY_OF_WEEK)-1]+"）";
            }catch(Exception e){return iso;}
        }
        int skyTop(){
            if(!isDay)return Color.rgb(19,34,83);
            if(currentCode==0)return Color.rgb(54,145,238);
            if(currentCode<=3)return Color.rgb(75,128,184);
            if(currentCode>=51&&currentCode<=82)return Color.rgb(53,79,111);
            return Color.rgb(65,103,145);
        }
        int skyMiddle(){
            if(!isDay)return Color.rgb(10,28,60);
            if(currentCode==0)return Color.rgb(24,82,145);
            if(currentCode>=51&&currentCode<=82)return Color.rgb(35,61,89);
            return Color.rgb(31,70,112);
        }
        String formatRainMm(float amount){
            if(amount<.05f)return "0mm";
            return amount<10f?String.format(Locale.JAPAN,"%.1fmm",amount):String.format(Locale.JAPAN,"%.0fmm",amount);
        }
        void drawSkyDecoration(Canvas c,float w){
            p.setColor(Color.argb(isDay?24:34,255,255,255));c.drawCircle(w-32,88,78,p);c.drawCircle(30,205,55,p);
            if(!isDay){for(int i=0;i<11;i++){float x=18+(i*73)%Math.max(40,(int)w),y=72+(i*47)%125;
                p.setColor(Color.argb(95+(i%3)*35,255,255,255));c.drawCircle(x,y,1.2f+(i%2),p);}}
        }
        void drawWeatherIcon(Canvas c,float cx,float cy,float scale,int code,boolean day){
            boolean fog=code==45||code==48,snow=(code>=71&&code<=77)||(code>=85&&code<=86);
            boolean rain=(code>=51&&code<=67)||(code>=80&&code<=82)||code>=95;
            boolean cloud=code>0;
            if(code<=2){
                p.setColor(day?Color.rgb(255,213,72):Color.rgb(235,242,255));c.drawCircle(cx+(cloud?17:0)*scale,cy-12*scale,19*scale,p);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3*scale);
                for(int i=0;i<8;i++){double a=i*Math.PI/4;float x1=cx+(cloud?17:0)*scale+(float)Math.cos(a)*25*scale;
                    float y1=cy-12*scale+(float)Math.sin(a)*25*scale;float x2=cx+(cloud?17:0)*scale+(float)Math.cos(a)*31*scale;
                    float y2=cy-12*scale+(float)Math.sin(a)*31*scale;c.drawLine(x1,y1,x2,y2,p);}p.setStyle(Paint.Style.FILL);
            }
            if(cloud){
                int cloudColor=rain||snow||fog?Color.rgb(205,216,229):Color.rgb(244,248,252);p.setColor(cloudColor);
                c.drawCircle(cx-18*scale,cy+5*scale,17*scale,p);c.drawCircle(cx+2*scale,cy-2*scale,23*scale,p);
                c.drawCircle(cx+23*scale,cy+7*scale,16*scale,p);c.drawRoundRect(cx-35*scale,cy+3*scale,cx+39*scale,cy+22*scale,10*scale,10*scale,p);
            }
            if(fog){p.setColor(Color.argb(220,235,243,250));p.setStrokeWidth(3*scale);
                c.drawLine(cx-29*scale,cy+31*scale,cx+27*scale,cy+31*scale,p);c.drawLine(cx-20*scale,cy+40*scale,cx+35*scale,cy+40*scale,p);}
            if(rain){p.setColor(Color.rgb(104,194,255));p.setStrokeWidth(4*scale);
                for(int i=-1;i<=1;i++)c.drawLine(cx+i*20*scale,cy+30*scale,cx+(i*20-5)*scale,cy+43*scale,p);
                if(code>=95){p.setColor(Color.rgb(255,209,73));Path bolt=new Path();bolt.moveTo(cx+3*scale,cy+25*scale);
                    bolt.lineTo(cx-7*scale,cy+43*scale);bolt.lineTo(cx+2*scale,cy+41*scale);bolt.lineTo(cx-4*scale,cy+56*scale);bolt.lineTo(cx+15*scale,cy+34*scale);bolt.lineTo(cx+6*scale,cy+35*scale);bolt.close();c.drawPath(bolt,p);}
            }
            if(snow){p.setColor(Color.WHITE);p.setStrokeWidth(2.5f*scale);
                for(int i=-1;i<=1;i++){float x=cx+i*20*scale,y=cy+38*scale;c.drawLine(x-5*scale,y,x+5*scale,y,p);c.drawLine(x,y-5*scale,x,y+5*scale,p);}}
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            float density=getResources().getDisplayMetrics().density;
            float x=event.getX()/density,y=event.getY()/density,w=getWidth()/density;
            boolean detailButton=y>=20&&y<=62&&x>=w-122;
            int card=detailButton?4:(y>=1438&&y<=1566?1:(y>=234&&y<=494?2:
                    (y>=1582&&y<=1670?3:(y>=1686&&y<=1774?5:(y>=1790&&y<=1878?6:0)))));
            if(event.getAction()==MotionEvent.ACTION_DOWN&&card>0){
                pressedCard=card;touchDownX=event.getX();touchDownY=event.getY();
                if(card==2){
                    hourScroller.forceFinished(true);
                    if(velocityTracker==null)velocityTracker=VelocityTracker.obtain();else velocityTracker.clear();
                    velocityTracker.addMovement(event);
                    touchStartHourPosition=hourPosition;draggingHours=false;
                }
                if(card==2||card==4)invalidateWeather();else invalidate();return true;
            }
            if(event.getAction()==MotionEvent.ACTION_MOVE&&pressedCard==2&&hourTimes!=null){
                if(velocityTracker!=null)velocityTracker.addMovement(event);
                float dx=event.getX()-touchDownX,dy=event.getY()-touchDownY;
                if(draggingHours||(Math.abs(dx)>6*density&&Math.abs(dx)>Math.abs(dy))){
                    draggingHours=true;getParent().requestDisallowInterceptTouchEvent(true);
                    float columnPx=(getWidth()-68*density)/6f;
                    float max=Math.max(0,hourTimes.length-6);
                    hourPosition=Math.max(0,Math.min(max,touchStartHourPosition-dx/columnPx));
                    hourOffset=Math.max(0,Math.min((int)Math.floor(hourPosition),hourTimes.length-6));
                    invalidateWeather();
                    return true;
                }
            }
            if(event.getAction()==MotionEvent.ACTION_UP){
                if(velocityTracker!=null)velocityTracker.addMovement(event);
                getParent().requestDisallowInterceptTouchEvent(false);
                int activate=pressedCard==2&&draggingHours?2:(pressedCard==card?card:0);
                int releasedCard=pressedCard;pressedCard=0;
                if(releasedCard==2||releasedCard==4)invalidateWeather();else invalidate();
                if(activate==1){super.performClick();mapAction.run();}
                if(activate==2){
                    boolean wasDragging=draggingHours;
                    draggingHours=false;
                    if(wasDragging&&velocityTracker!=null&&hourTimes!=null){
                        velocityTracker.computeCurrentVelocity(1000,maximumFlingVelocity);
                        float fingerVelocity=velocityTracker.getXVelocity();
                        if(Math.abs(fingerVelocity)>=minimumFlingVelocity){
                            float columnPx=(getWidth()-68*density)/6f;
                            int flingVelocity=(int)Math.max(-12000,Math.min(12000,-fingerVelocity/columnPx*1000));
                            hourScroller.fling((int)(hourPosition*1000),0,flingVelocity,0,
                                    0,Math.max(0,(hourTimes.length-6)*1000),0,0);
                            invalidateWeather();
                        }
                    }
                }
                if(activate==4){super.performClick();weatherAction.run();}
                if(activate==3){super.performClick();fishingAction.run();}
                if(activate==5){super.performClick();signalAction.run();}
                if(activate==6){super.performClick();chargingAction.run();}
                recycleVelocityTracker();
                flushPendingSensors();
                return true;
            }
            if(event.getAction()==MotionEvent.ACTION_CANCEL){
                if(velocityTracker!=null)velocityTracker.addMovement(event);
                getParent().requestDisallowInterceptTouchEvent(false);
                int cancelledCard=pressedCard;pressedCard=0;
                if(draggingHours){
                    draggingHours=false;
                    invalidateWeather();
                }
                else if(cancelledCard==2||cancelledCard==4)invalidateWeather();else invalidate();
                recycleVelocityTracker();
                flushPendingSensors();
                return true;
            }
            return true;
        }
        void invalidateWeather(){
            float density=getResources().getDisplayMetrics().density;
            postInvalidateOnAnimation(0,0,getWidth(),(int)(762*density));
        }
        void invalidateSensors(){
            if(pressedCard==2||draggingHours||!hourScroller.isFinished()){
                pendingSensorRedraw=true;return;
            }
            float density=getResources().getDisplayMetrics().density;
            postInvalidateOnAnimation(0,(int)(758*density),getWidth(),getHeight());
        }
        void recycleVelocityTracker(){
            if(velocityTracker!=null){velocityTracker.recycle();velocityTracker=null;}
        }
        void flushPendingSensors(){
            if(pendingSensorRedraw&&pressedCard!=2&&!draggingHours&&hourScroller.isFinished()){
                pendingSensorRedraw=false;invalidateSensors();
            }
        }
        @Override public void computeScroll(){
            super.computeScroll();
            if(hourScroller.computeScrollOffset()&&hourTimes!=null){
                hourPosition=hourScroller.getCurrX()/1000f;
                hourOffset=Math.max(0,Math.min((int)Math.floor(hourPosition),hourTimes.length-6));
                invalidateWeather();
            }else flushPendingSensors();
        }
        @Override public boolean performClick(){super.performClick();return true;}
        String vector(int t,String unit){float[]v=values.get(t);return v==null?"計測中…":String.format(Locale.JAPAN,"X %.1f\nY %.1f  Z %.1f %s",v[0],v[1],v[2],unit);}
        void sensorBox(Canvas c,float l,float t,float r,float b,String title,String value){box(c,l,t,r,b);text(c,title,l+14,t+25,13,muted,false);multi(c,value,l+14,t+55,14,white);}
        void box(Canvas c,float l,float t,float r,float b){p.setColor(card);c.drawRoundRect(l,t,r,b,22,22,p);}
        void gpsBox(Canvas c,float l,float t,float r,float b){p.setColor(pressedCard==1?Color.rgb(24,61,78):card);c.drawRoundRect(l,t,r,b,22,22,p);}
        void weatherBox(Canvas c,float l,float t,float r,float b){p.setColor(pressedCard==2?Color.rgb(24,61,78):card);c.drawRoundRect(l,t,r,b,22,22,p);}
        void fishingBox(Canvas c,float l,float t,float r,float b){p.setColor(pressedCard==3?Color.rgb(24,61,78):card);c.drawRoundRect(l,t,r,b,22,22,p);}
        void signalBox(Canvas c,float l,float t,float r,float b){p.setColor(pressedCard==5?Color.rgb(24,61,78):card);c.drawRoundRect(l,t,r,b,22,22,p);}
        void chargingBox(Canvas c,float l,float t,float r,float b){p.setColor(pressedCard==6?Color.rgb(24,61,78):card);c.drawRoundRect(l,t,r,b,22,22,p);}
        void text(Canvas c,String s,float x,float y,float size,int color,boolean bold){p.setTextSize(size);p.setColor(color);p.setTypeface(bold?boldTypeface:normalTypeface);c.drawText(s,x,y,p);}
        void multi(Canvas c,String s,float x,float y,float size,int color){for(String line:s.split("\n")){text(c,line,x,y,size,color,false);y+=22;}}
        void graph(Canvas c,float l,float t,float r,float b){if(history.size()<2)return;float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;for(float v:history){min=Math.min(min,v);max=Math.max(max,v);}if(max-min<.2f){max+=.1f;min-=.1f;}Path path=new Path();int i=0,n=history.size();for(float v:history){float x=l+(r-l)*i/(n-1),y=b-(v-min)/(max-min)*(b-t);if(i++==0)path.moveTo(x,y);else path.lineTo(x,y);}p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);p.setColor(mint);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);}
        String dir(float d){String[]a={"北","北東","東","南東","南","南西","西","北西"};return a[Math.round(d/45)%8];}
    }
}
