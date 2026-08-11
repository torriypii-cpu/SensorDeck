package jp.sensordeck;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class FishingActivity extends Activity implements LocationListener {
    private final int navy=Color.rgb(7,17,31),card=Color.rgb(16,31,48);
    private final int mint=Color.rgb(0,212,170),white=Color.rgb(238,246,252);
    private final int muted=Color.rgb(143,163,180);
    private LocationManager locationManager;
    private TextView locationText,status,wave,wind,water,period,tide;
    private TideChart chart;
    private boolean loading;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(18),dp(20),dp(36));
        root.setBackgroundColor(navy);

        Button back=new Button(this);back.setText("← 戻る");
        back.setOnClickListener(v->finish());
        root.addView(back,new LinearLayout.LayoutParams(dp(100),dp(48)));
        root.addView(label("FISHING DECK",30,white,true,dp(12)));
        locationText=label("GPS測位中…",15,mint,true,dp(4));root.addView(locationText);
        status=label("海洋予報を準備しています",13,muted,false,dp(18));root.addView(status);

        LinearLayout row1=row();
        wave=metric("波高","-- m");wind=metric("風速","-- m/s");
        row1.addView(wave,weighted());row1.addView(wind,weighted());root.addView(row1);
        LinearLayout row2=row();
        water=metric("海水温","-- ℃");period=metric("波周期","-- 秒");
        row2.addView(water,weighted());row2.addView(period,weighted());root.addView(row2);

        tide=label("潮位を取得中…",17,white,true,dp(14));
        tide.setBackground(cardBackground());tide.setPadding(dp(16),dp(16),dp(16),dp(16));
        root.addView(tide,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(label("今後24時間の潮位変化",16,mint,true,dp(18)));
        chart=new TideChart(this);
        root.addView(chart,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(230)));
        root.addView(label("※ 約8km格子の海洋モデルによる目安です。沿岸では誤差があり、航海・安全判断には使用できません。",
                12,muted,false,dp(12)));
        scroll.addView(root);setContentView(scroll);
        locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);
    }

    private LinearLayout row(){
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0,0,0,dp(12));return r;
    }
    private LinearLayout.LayoutParams weighted(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(112),1);
        p.setMargins(dp(5),0,dp(5),0);return p;
    }
    private TextView metric(String title,String value){
        TextView v=label(title+"\n"+value,16,white,true,0);
        v.setLineSpacing(dp(6),1);v.setPadding(dp(16),dp(16),dp(12),dp(12));
        v.setBackground(cardBackground());return v;
    }
    private TextView label(String text,float size,int color,boolean bold,int bottom){
        TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);
        v.setTypeface(Typeface.create("sans",bold?Typeface.BOLD:Typeface.NORMAL));
        v.setPadding(0,0,0,bottom);return v;
    }
    private GradientDrawable cardBackground(){
        GradientDrawable d=new GradientDrawable();d.setColor(card);d.setCornerRadius(dp(20));
        d.setStroke(dp(1),Color.rgb(42,68,89));return d;
    }
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density);}

    @Override protected void onResume(){
        super.onResume();
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
            Location last=locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if(last!=null)load(last);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000,5,this);
        }else status.setText("位置情報の許可が必要です");
    }
    @Override protected void onPause(){
        super.onPause();if(locationManager!=null)locationManager.removeUpdates(this);
    }
    @Override public void onLocationChanged(Location location){if(!loading)load(location);}

    private void load(Location location){
        loading=true;
        locationText.setText(String.format(Locale.JAPAN,"⌖ %.4f, %.4f",
                location.getLatitude(),location.getLongitude()));
        status.setText("最寄りの海面格子を取得中…");
        double lat=location.getLatitude(),lon=location.getLongitude();
        new Thread(()->{
            try{
                String marineUrl=String.format(Locale.US,
                        "https://marine-api.open-meteo.com/v1/marine?latitude=%.5f&longitude=%.5f"
                        +"&current=wave_height,wave_direction,wave_period,sea_surface_temperature,sea_level_height_msl"
                        +"&hourly=sea_level_height_msl&forecast_hours=24&timezone=auto&cell_selection=sea",lat,lon);
                String windUrl=String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
                        +"&current=wind_speed_10m,wind_direction_10m",lat,lon);
                JSONObject marine=new JSONObject(read(marineUrl));
                JSONObject mc=marine.getJSONObject("current");
                JSONObject wc=new JSONObject(read(windUrl)).getJSONObject("current");
                JSONObject hourly=marine.getJSONObject("hourly");
                JSONArray levels=hourly.getJSONArray("sea_level_height_msl");
                JSONArray times=hourly.getJSONArray("time");
                float[] values=new float[levels.length()];String[] labels=new String[times.length()];
                int hi=0,lo=0;
                for(int i=0;i<values.length;i++){
                    values[i]=(float)levels.optDouble(i,Float.NaN);
                    labels[i]=times.getString(i);
                    if(!Float.isNaN(values[i])){
                        if(Float.isNaN(values[hi])||values[i]>values[hi])hi=i;
                        if(Float.isNaN(values[lo])||values[i]<values[lo])lo=i;
                    }
                }
                boolean rising=values.length>1&&values[1]>=values[0];
                double waveHeight=mc.optDouble("wave_height",Double.NaN);
                double waveDir=mc.optDouble("wave_direction",Double.NaN);
                double wavePeriod=mc.optDouble("wave_period",Double.NaN);
                double sst=mc.optDouble("sea_surface_temperature",Double.NaN);
                double windKmh=wc.optDouble("wind_speed_10m",Double.NaN);
                double windDir=wc.optDouble("wind_direction_10m",Double.NaN);
                int high=hi,low=lo;
                runOnUiThread(()->{
                    wave.setText("波高\n"+number(waveHeight,"%.1f m")+"  "+direction(waveDir));
                    wind.setText("風速\n"+number(windKmh/3.6,"%.1f m/s")+"  "+direction(windDir));
                    water.setText("海水温\n"+number(sst,"%.1f ℃"));
                    period.setText("波周期\n"+number(wavePeriod,"%.1f 秒"));
                    tide.setText((rising?"↑ 上げ潮":"↓ 下げ潮")
                            +"\n24h最高 "+number(values[high],"%.2f m")+"  "+hour(labels[high])
                            +"\n24h最低 "+number(values[low],"%.2f m")+"  "+hour(labels[low]));
                    chart.setData(values,labels);status.setText("GPS周辺の海洋予報 • 1時間更新");
                });
            }catch(Exception e){
                runOnUiThread(()->{status.setText("海洋予報を取得できませんでした");loading=false;});
            }
        }).start();
    }
    private static String read(String target)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(target).openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(10000);
        StringBuilder b=new StringBuilder();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()))){
            String line;while((line=r.readLine())!=null)b.append(line);
        }finally{c.disconnect();}
        return b.toString();
    }
    private static String number(double v,String format){
        return Double.isNaN(v)?"--":String.format(Locale.JAPAN,format,v);
    }
    private static String hour(String iso){return iso.length()>=16?iso.substring(11,16):iso;}
    private static String direction(double d){
        if(Double.isNaN(d))return "";
        String[] names={"北","北東","東","南東","南","南西","西","北西"};
        return names[(int)Math.round(d/45)%8];
    }

    static class TideChart extends View{
        final Paint p=new Paint(3);float[] values;String[] times;
        TideChart(Context c){super(c);setBackgroundColor(Color.rgb(16,31,48));}
        void setData(float[] v,String[] t){values=v;times=t;invalidate();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);if(values==null||values.length<2)return;
            float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;
            for(float v:values)if(!Float.isNaN(v)){min=Math.min(min,v);max=Math.max(max,v);}
            if(max<=min)return;
            float l=24,t=28,r=getWidth()-24,b=getHeight()-42;Path path=new Path();
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(Color.rgb(50,81,105));
            for(int i=0;i<4;i++){float y=t+(b-t)*i/3;c.drawLine(l,y,r,y,p);}
            p.setStrokeWidth(5);p.setColor(Color.rgb(0,212,170));int drawn=0;
            for(int i=0;i<values.length;i++)if(!Float.isNaN(values[i])){
                float x=l+(r-l)*i/(values.length-1),y=b-(values[i]-min)/(max-min)*(b-t);
                if(drawn++==0)path.moveTo(x,y);else path.lineTo(x,y);
            }
            c.drawPath(path,p);p.setStyle(Paint.Style.FILL);p.setTextSize(26);
            p.setColor(Color.rgb(143,163,180));
            for(int i=0;i<values.length;i+=6){
                float x=l+(r-l)*i/(values.length-1);
                c.drawText(hour(times[i]),x-22,getHeight()-14,p);
            }
        }
    }
}
