package jp.sensordeck;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.*;
import android.hardware.*;
import android.location.*;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private long lastWeatherFetch;
    private String weatherNewsUrl = "https://weathernews.jp/";
    private final float[] accel = new float[3], magnetic = new float[3];

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        dashboard = new Dashboard(this,
                () -> startActivity(new Intent(this, MapActivity.class)),
                this::openWeatherNews,
                () -> startActivity(new Intent(this, FishingActivity.class)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        float density = getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, (int)(32 * density));
        content.addView(dashboard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(1640 * density)));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        sensors = (SensorManager)getSystemService(SENSOR_SERVICE);
        register(Sensor.TYPE_PRESSURE); register(Sensor.TYPE_ACCELEROMETER);
        register(Sensor.TYPE_GYROSCOPE); register(Sensor.TYPE_MAGNETIC_FIELD);
        register(Sensor.TYPE_LIGHT); register(Sensor.TYPE_PROXIMITY);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 10);
        else startLocation();
    }

    private void register(int type) {
        Sensor s = sensors.getDefaultSensor(type);
        dashboard.available.put(type, s != null);
        if (s != null) sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
    }

    private void startLocation() {
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
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
        dashboard.invalidate();
    }
    @Override public void onAccuracyChanged(Sensor s,int a) {}
    @Override public void onLocationChanged(Location l) {
        dashboard.location=l;
        dashboard.invalidate();
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
        dashboard.invalidate();
        double lat = location.getLatitude(), lon = location.getLongitude();
        fetchPlaceAndWeatherNews(lat,lon);
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String endpoint = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
                        + "&current=temperature_2m,apparent_temperature,weather_code,is_day"
                        + "&hourly=temperature_2m,weather_code,precipitation_probability"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                        + "&timezone=auto&forecast_days=2", lat, lon);
                connection = (HttpURLConnection)new URL(endpoint).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONObject root = new JSONObject(body.toString());
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
                String currentTime = current.getString("time");
                int start = 0;
                while (start < times.length()-1
                        && times.getString(start).compareTo(currentTime) < 0) start++;
                int forecastCount=Math.min(24,times.length()-start);
                String[] nextTimes = new String[forecastCount];
                float[] nextTemps = new float[forecastCount];
                int[] nextCodes = new int[forecastCount], nextRain = new int[forecastCount];
                for (int i=0;i<forecastCount;i++) {
                    int index=Math.min(start+i,times.length()-1);
                    nextTimes[i]=times.getString(index);
                    nextTemps[i]=(float)hourlyTemps.getDouble(index);
                    nextCodes[i]=hourlyCodes.getInt(index);
                    nextRain[i]=hourlyRain.getInt(index);
                }
                String result = String.format(Locale.JAPAN,
                        "現在 %.1f℃  %s\n今日 %.0f〜%.0f℃  降水%d%%\n明日 %.0f〜%.0f℃  %s  降水%d%%",
                        current.getDouble("temperature_2m"),
                        weatherName(current.getInt("weather_code")),
                        min.getDouble(0), max.getDouble(0), rain.getInt(0),
                        min.getDouble(1), max.getDouble(1),
                        weatherName(codes.getInt(1)), rain.getInt(1));
                runOnUiThread(() -> {
                    dashboard.weather=result;
                    dashboard.currentTemp=(float)current.optDouble("temperature_2m",Float.NaN);
                    dashboard.apparentTemp=(float)current.optDouble("apparent_temperature",Float.NaN);
                    dashboard.currentCode=current.optInt("weather_code",0);
                    dashboard.isDay=current.optInt("is_day",1)==1;
                    dashboard.todayMax=(float)max.optDouble(0,Float.NaN);
                    dashboard.todayMin=(float)min.optDouble(0,Float.NaN);
                    dashboard.hourTimes=nextTimes;dashboard.hourTemps=nextTemps;
                    dashboard.hourCodes=nextCodes;dashboard.hourRain=nextRain;
                    dashboard.hourOffset=0;
                    getSharedPreferences("weather_widget",MODE_PRIVATE).edit()
                            .putString("place",dashboard.placeName)
                            .putString("condition",weatherName(current.optInt("weather_code",0)))
                            .putFloat("temp",(float)current.optDouble("temperature_2m",Float.NaN))
                            .putFloat("max",(float)max.optDouble(0,Float.NaN))
                            .putFloat("min",(float)min.optDouble(0,Float.NaN))
                            .putLong("updated",System.currentTimeMillis()).apply();
                    WeatherWidgetProvider.updateAll(this);
                    dashboard.invalidate();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dashboard.weather = "天気予報を取得できませんでした";
                    dashboard.invalidate();
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
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
                    dashboard.placeName=place;weatherNewsUrl=url;dashboard.invalidate();
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
    @Override protected void onPause(){
        super.onPause();
        sensors.unregisterListener(this);
        if(locationManager!=null) locationManager.removeUpdates(this);
    }
    @Override protected void onResume(){
        super.onResume();
        if(sensors!=null){
            register(Sensor.TYPE_PRESSURE);register(Sensor.TYPE_ACCELEROMETER);
            register(Sensor.TYPE_GYROSCOPE);register(Sensor.TYPE_MAGNETIC_FIELD);
            register(Sensor.TYPE_LIGHT);register(Sensor.TYPE_PROXIMITY);
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) startLocation();
        }
    }

    static class Dashboard extends View {
        final Paint p = new Paint(3); final Map<Integer,float[]> values=new HashMap<>();
        final Map<Integer,Boolean> available=new HashMap<>(); final ArrayDeque<Float> history=new ArrayDeque<>();
        Location location; float heading; String weather="GPS測位後に予報を表示";
        String placeName="現在地を測位中";
        float currentTemp=Float.NaN,apparentTemp=Float.NaN,todayMax=Float.NaN,todayMin=Float.NaN;
        int currentCode; boolean isDay=true;
        String[] hourTimes; float[] hourTemps; int[] hourCodes,hourRain;
        final Runnable mapAction, weatherAction, fishingAction; int pressedCard,hourOffset;
        float hourSlide;
        ValueAnimator hourAnimator;
        float touchDownX,touchDownY;
        final int bg=Color.rgb(7,17,31), card=Color.rgb(16,31,48), mint=Color.rgb(0,212,170), white=Color.rgb(238,246,252), muted=Color.rgb(143,163,180);
        Dashboard(Context c,Runnable mapAction,Runnable weatherAction,Runnable fishingAction){
            super(c);this.mapAction=mapAction;this.weatherAction=weatherAction;
            this.fishingAction=fishingAction;
            p.setTypeface(Typeface.create("sans",Typeface.NORMAL));
            setBackgroundColor(bg);setClickable(true);
            setContentDescription("GPS地図と現在地の天気予報");
        }
        void addPressure(float v){if(history.size()>=80)history.removeFirst();history.addLast(v);}
        String val(int t,int i,String unit){float[]v=values.get(t);return v==null?"計測中…":String.format(Locale.JAPAN,"%.2f %s",v[i],unit);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float density=getResources().getDisplayMetrics().density;c.save();c.scale(density,density);float w=getWidth()/density, pad=24, y;
            weatherHero(c,w);
            y=570;text(c,"SENSOR DECK",pad,y,28,white,true);text(c,"Galaxy S25 • LIVE",pad,y+28,13,mint,false);y+=70;
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
            box(c,pad,y,w-pad,y+82);text(c,"本体非搭載",pad+16,y+28,13,muted,false);text(c,"温度・湿度・心拍・水深",pad+16,y+58,15,white,false);c.restore();
        }
        void weatherHero(Canvas c,float w){
            LinearGradient gradient=new LinearGradient(0,0,0,540,
                    isDay?Color.rgb(18,71,145):Color.rgb(8,26,75),
                    Color.rgb(7,17,31),Shader.TileMode.CLAMP);
            p.setShader(gradient);c.drawRect(0,0,w,540,p);p.setShader(null);
            text(c,"⌖  "+placeName,24,52,17,white,true);
            String temp=Float.isNaN(currentTemp)?"--°":String.format(Locale.JAPAN,"%.0f°",currentTemp);
            text(c,temp,24,150,72,white,false);
            text(c,weatherName(currentCode),28,196,25,white,true);
            String range=Float.isNaN(todayMax)?"予報を取得中…":
                    String.format(Locale.JAPAN,"↑ %.0f° / ↓ %.0f°    体感 %.0f°",todayMax,todayMin,apparentTemp);
            text(c,range,28,232,16,white,true);
            p.setColor(Color.argb(185,8,35,67));c.drawRoundRect(18,260,w-18,520,24,24,p);
            text(c,"1時間予報  •  左右スワイプ  •  タップでウェザーニュース",34,294,13,mint,true);
            if(hourTimes==null){text(c,"GPS測位後に表示します",34,350,16,white,false);return;}
            float left=34,right=w-34,col=(right-left)/6f,min=Float.MAX_VALUE,max=-Float.MAX_VALUE;
            int count=Math.min(6,hourTemps.length-hourOffset);
            for(int i=0;i<count;i++){float v=hourTemps[hourOffset+i];min=Math.min(min,v);max=Math.max(max,v);}
            if(max-min<1){max+=.5f;min-=.5f;}
            Path line=new Path();
            for(int i=0;i<count;i++){
                int dataIndex=hourOffset+i;
                float x=left+col*(i+.5f)+hourSlide;
                String time=hourTimes[dataIndex].length()>=16?hourTimes[dataIndex].substring(11,16):hourTimes[dataIndex];
                text(c,time,x-17,330,12,muted,false);
                text(c,weatherSymbol(hourCodes[dataIndex]),x-13,370,22,white,false);
                text(c,String.format(Locale.JAPAN,"%.0f°",hourTemps[dataIndex]),x-14,402,16,white,true);
                text(c,"💧"+hourRain[dataIndex]+"%",x-20,490,11,muted,false);
                float gy=458-(hourTemps[dataIndex]-min)/(max-min)*30;
                if(i==0)line.moveTo(x,gy);else line.lineTo(x,gy);
                p.setColor(white);c.drawCircle(x,gy,4,p);
            }
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(Color.rgb(210,225,238));c.drawPath(line,p);p.setStyle(Paint.Style.FILL);
            text(c,(hourOffset+1)+"–"+(hourOffset+count)+" / "+hourTemps.length+"時間",w-116,510,11,muted,false);
        }
        String weatherSymbol(int code){
            if(code==0)return "☀";if(code<=3)return "☁";if(code<=57)return "♨";
            if(code<=67)return "☂";if(code<=77)return "❄";if(code<=86)return "☔";return "⚡";
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            float y=event.getY()/getResources().getDisplayMetrics().density;
            int card=y>=1218&&y<=1346?1:(y>=260&&y<=520?2:(y>=1362&&y<=1450?3:0));
            if(event.getAction()==MotionEvent.ACTION_DOWN&&card>0){
                pressedCard=card;touchDownX=event.getX();touchDownY=event.getY();invalidate();return true;
            }
            if(event.getAction()==MotionEvent.ACTION_UP){
                int activate=pressedCard==card?card:0;pressedCard=0;invalidate();
                if(activate==1){super.performClick();mapAction.run();}
                if(activate==2){
                    float dx=event.getX()-touchDownX;
                    if(Math.abs(dx)>50&&hourTimes!=null){
                        slideOneHour(dx<0?1:-1);
                    }else{super.performClick();weatherAction.run();}
                }
                if(activate==3){super.performClick();fishingAction.run();}
                return true;
            }
            if(event.getAction()==MotionEvent.ACTION_CANCEL){pressedCard=0;invalidate();return true;}
            return true;
        }
        void slideOneHour(int direction){
            int maxOffset=Math.max(0,hourTimes.length-6);
            int next=Math.max(0,Math.min(maxOffset,hourOffset+direction));
            if(next==hourOffset)return;
            if(hourAnimator!=null)hourAnimator.cancel();
            hourOffset=next;
            float column=(getWidth()/getResources().getDisplayMetrics().density-68)/6f;
            hourAnimator=ValueAnimator.ofFloat(direction>0?column:-column,0f);
            hourAnimator.setDuration(420);
            hourAnimator.addUpdateListener(a->{hourSlide=(float)a.getAnimatedValue();invalidate();});
            hourAnimator.start();
        }
        @Override public boolean performClick(){super.performClick();return true;}
        String vector(int t,String unit){float[]v=values.get(t);return v==null?"計測中…":String.format(Locale.JAPAN,"X %.1f\nY %.1f  Z %.1f %s",v[0],v[1],v[2],unit);}
        void sensorBox(Canvas c,float l,float t,float r,float b,String title,String value){box(c,l,t,r,b);text(c,title,l+14,t+25,13,muted,false);multi(c,value,l+14,t+55,14,white);}
        void box(Canvas c,float l,float t,float r,float b){p.setColor(card);c.drawRoundRect(l,t,r,b,22,22,p);}
        void gpsBox(Canvas c,float l,float t,float r,float b){p.setColor(pressedCard==1?Color.rgb(24,61,78):card);c.drawRoundRect(l,t,r,b,22,22,p);}
        void weatherBox(Canvas c,float l,float t,float r,float b){p.setColor(pressedCard==2?Color.rgb(24,61,78):card);c.drawRoundRect(l,t,r,b,22,22,p);}
        void fishingBox(Canvas c,float l,float t,float r,float b){p.setColor(pressedCard==3?Color.rgb(24,61,78):card);c.drawRoundRect(l,t,r,b,22,22,p);}
        void text(Canvas c,String s,float x,float y,float size,int color,boolean bold){p.setTextSize(size);p.setColor(color);p.setTypeface(Typeface.create("sans",bold?Typeface.BOLD:Typeface.NORMAL));c.drawText(s,x,y,p);}
        void multi(Canvas c,String s,float x,float y,float size,int color){for(String line:s.split("\n")){text(c,line,x,y,size,color,false);y+=22;}}
        void graph(Canvas c,float l,float t,float r,float b){if(history.size()<2)return;float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;for(float v:history){min=Math.min(min,v);max=Math.max(max,v);}if(max-min<.2f){max+=.1f;min-=.1f;}Path path=new Path();int i=0,n=history.size();for(float v:history){float x=l+(r-l)*i/(n-1),y=b-(v-min)/(max-min)*(b-t);if(i++==0)path.moveTo(x,y);else path.lineTo(x,y);}p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);p.setColor(mint);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);}
        String dir(float d){String[]a={"北","北東","東","南東","南","南西","西","北西"};return a[Math.round(d/45)%8];}
    }
}
