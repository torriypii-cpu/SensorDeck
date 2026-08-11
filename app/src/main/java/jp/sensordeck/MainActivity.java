package jp.sensordeck;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.*;
import android.location.*;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.*;

public class MainActivity extends Activity implements SensorEventListener, LocationListener {
    private SensorManager sensors;
    private Dashboard dashboard;
    private LocationManager locationManager;
    private final float[] accel = new float[3], magnetic = new float[3];

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        dashboard = new Dashboard(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        float density = getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, (int)(32 * density));
        content.addView(dashboard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(1040 * density)));

        Button mapButton = new Button(this);
        mapButton.setText("GPS地図を開く");
        mapButton.setTextSize(17);
        mapButton.setTextColor(Color.rgb(7,17,31));
        mapButton.setBackgroundColor(Color.rgb(0,212,170));
        mapButton.setOnClickListener(v -> startActivity(new Intent(this, MapActivity.class)));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(64 * density));
        buttonParams.setMargins((int)(24*density), (int)(8*density),
                (int)(24*density), (int)(20*density));
        content.addView(mapButton, buttonParams);
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
        Location location; float heading;
        final int bg=Color.rgb(7,17,31), card=Color.rgb(16,31,48), mint=Color.rgb(0,212,170), white=Color.rgb(238,246,252), muted=Color.rgb(143,163,180);
        Dashboard(Context c){super(c);p.setTypeface(Typeface.create("sans",Typeface.NORMAL));setBackgroundColor(bg);}
        void addPressure(float v){if(history.size()>=80)history.removeFirst();history.addLast(v);}
        String val(int t,int i,String unit){float[]v=values.get(t);return v==null?"計測中…":String.format(Locale.JAPAN,"%.2f %s",v[i],unit);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float density=getResources().getDisplayMetrics().density;c.save();c.scale(density,density);float w=getWidth()/density, pad=24, y=56;
            text(c,"SENSOR DECK",pad,y,28,white,true); text(c,"Galaxy S25 • LIVE",pad,y+28,13,mint,false); y+=70;
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
            box(c,pad,y,w-pad,y+128);text(c,"GPS / QZSS",pad+16,y+28,13,muted,false);multi(c,gps,pad+16,y+58,16,white);y+=144;
            box(c,pad,y,w-pad,y+82);text(c,"本体非搭載",pad+16,y+28,13,muted,false);text(c,"温度・湿度・心拍・水深",pad+16,y+58,15,white,false);c.restore();
        }
        String vector(int t,String unit){float[]v=values.get(t);return v==null?"計測中…":String.format(Locale.JAPAN,"X %.1f\nY %.1f  Z %.1f %s",v[0],v[1],v[2],unit);}
        void sensorBox(Canvas c,float l,float t,float r,float b,String title,String value){box(c,l,t,r,b);text(c,title,l+14,t+25,13,muted,false);multi(c,value,l+14,t+55,14,white);}
        void box(Canvas c,float l,float t,float r,float b){p.setColor(card);c.drawRoundRect(l,t,r,b,22,22,p);}
        void text(Canvas c,String s,float x,float y,float size,int color,boolean bold){p.setTextSize(size);p.setColor(color);p.setTypeface(Typeface.create("sans",bold?Typeface.BOLD:Typeface.NORMAL));c.drawText(s,x,y,p);}
        void multi(Canvas c,String s,float x,float y,float size,int color){for(String line:s.split("\n")){text(c,line,x,y,size,color,false);y+=22;}}
        void graph(Canvas c,float l,float t,float r,float b){if(history.size()<2)return;float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;for(float v:history){min=Math.min(min,v);max=Math.max(max,v);}if(max-min<.2f){max+=.1f;min-=.1f;}Path path=new Path();int i=0,n=history.size();for(float v:history){float x=l+(r-l)*i/(n-1),y=b-(v-min)/(max-min)*(b-t);if(i++==0)path.moveTo(x,y);else path.lineTo(x,y);}p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);p.setColor(mint);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);}
        String dir(float d){String[]a={"北","北東","東","南東","南","南西","西","北西"};return a[Math.round(d/45)%8];}
    }
}
