package jp.sensordeck;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class SignalFinderActivity extends Activity implements SensorEventListener {
    private static final int REQUEST_RADIO_PERMISSIONS=40;
    private static final int MODE_BLUETOOTH=1,MODE_WIFI=2;
    private final int bg=Color.rgb(7,17,31),card=Color.rgb(16,31,48);
    private final int mint=Color.rgb(0,212,170),white=Color.rgb(238,246,252),muted=Color.rgb(143,163,180);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final HashMap<String,RadioItem> items=new HashMap<>();
    private final long[] headingTimes=new long[2048];
    private final float[] headingValues=new float[2048];
    private int mode=MODE_BLUETOOTH;
    private int headingWriteIndex,headingHistorySize;
    private String selectedKey;
    private float heading;
    private LinearLayout list;
    private TextView status,bluetoothTab,wifiTab;
    private FinderView finder;
    private BluetoothLeScanner bluetoothScanner;
    private WifiManager wifiManager;
    private SensorManager sensorManager;
    private boolean receiverRegistered,renderPending;

    private final ScanCallback bluetoothCallback=new ScanCallback(){
        @Override public void onScanResult(int callbackType,android.bluetooth.le.ScanResult result){
            runOnUiThread(()->consumeBluetooth(result));
        }
        @Override public void onBatchScanResults(List<android.bluetooth.le.ScanResult> results){
            runOnUiThread(()->{for(android.bluetooth.le.ScanResult result:results)consumeBluetooth(result);});
        }
        @Override public void onScanFailed(int errorCode){
            runOnUiThread(()->status.setText("Bluetoothスキャンに失敗しました（"+errorCode+"）"));
        }
    };

    private final BroadcastReceiver wifiReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){consumeWifiResults();}
    };

    private final Runnable wifiScanTask=new Runnable(){
        @Override public void run(){
            if(mode!=MODE_WIFI||!hasPermissions())return;
            scanWifi();handler.postDelayed(this,10000);
        }
    };

    private final Runnable connectedWifiSampleTask=new Runnable(){
        @Override public void run(){
            if(mode!=MODE_WIFI||selectedKey==null||!hasPermissions())return;
            try{
                WifiInfo info=wifiManager.getConnectionInfo();
                RadioItem selected=items.get(selectedKey);String bssid=info==null?null:info.getBSSID();
                if(selected!=null&&selectedKey.equalsIgnoreCase(bssid)){
                    updateItem(new RadioItem(selected.key,selected.name,info.getRssi(),selected.type,SystemClock.elapsedRealtimeNanos()));
                    status.setText(selected.name+" を高精度追跡中 • 同じ場所で水平に持って一周");
                }
            }catch(SecurityException ignored){}
            handler.postDelayed(this,700);
        }
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        sensorManager=(SensorManager)getSystemService(SENSOR_SERVICE);
        wifiManager=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        buildUi();
        IntentFilter filter=new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(wifiReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(wifiReceiver,filter);
        receiverRegistered=true;
        if(hasPermissions())startMode();else requestRadioPermissions();
    }

    private void buildUi(){
        float d=getResources().getDisplayMetrics().density;
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(bg);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(20*d),(int)(18*d),(int)(20*d),(int)(36*d));
        Button back=new Button(this);back.setText("← 戻る");back.setAllCaps(false);back.setTextColor(mint);
        back.setTextSize(14);back.setGravity(Gravity.CENTER_VERTICAL);back.setBackground(panel(card,18));
        back.setOnClickListener(v->finish());root.addView(back,new LinearLayout.LayoutParams(-1,(int)(48*d)));
        TextView title=text("電波ファインダー",30,white,true);title.setPadding(2,(int)(22*d),0,0);root.addView(title);
        TextView note=text("Bluetooth / Wi-Fiの強さを測定\n同じ場所で水平に持ち、ゆっくり一周すると精度が上がります",14,muted,false);
        note.setPadding(2,(int)(8*d),0,(int)(16*d));root.addView(note);

        LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);
        bluetoothTab=tab("Bluetooth",true);wifiTab=tab("Wi-Fi",false);
        tabs.addView(bluetoothTab,new LinearLayout.LayoutParams(0,(int)(48*d),1));
        LinearLayout.LayoutParams wifiParams=new LinearLayout.LayoutParams(0,(int)(48*d),1);wifiParams.setMarginStart((int)(10*d));
        tabs.addView(wifiTab,wifiParams);root.addView(tabs);
        bluetoothTab.setOnClickListener(v->switchMode(MODE_BLUETOOTH));
        wifiTab.setOnClickListener(v->switchMode(MODE_WIFI));

        status=text("権限を確認しています…",13,muted,false);status.setPadding(2,(int)(14*d),0,(int)(10*d));root.addView(status);
        finder=new FinderView(this);root.addView(finder,new LinearLayout.LayoutParams(-1,(int)(300*d)));
        Button reset=new Button(this);reset.setText("↻ 方向測定をやり直す");reset.setAllCaps(false);reset.setTextColor(white);
        reset.setTextSize(14);reset.setBackground(panel(card,18));
        reset.setOnClickListener(v->{finder.resetMeasurements();if(selectedKey!=null)status.setText("測定をリセットしました • 同じ場所で水平に持って一周してください");});
        LinearLayout.LayoutParams resetParams=new LinearLayout.LayoutParams(-1,(int)(48*d));resetParams.topMargin=(int)(10*d);root.addView(reset,resetParams);
        TextView choose=text("検出した電波（強い順）",16,white,true);choose.setPadding(2,(int)(18*d),0,(int)(10*d));root.addView(choose);
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);root.addView(list);
        renderList();scroll.addView(root,new ViewGroup.LayoutParams(-1,-2));setContentView(scroll);
    }

    private TextView tab(String label,boolean selected){
        TextView view=text(label,15,selected?Color.rgb(5,31,37):white,true);view.setGravity(Gravity.CENTER);
        view.setBackground(panel(selected?mint:card,18));return view;
    }

    private void switchMode(int next){
        if(mode==next)return;stopScans();mode=next;items.clear();selectedKey=null;finder.clearTarget();
        bluetoothTab.setTextColor(mode==MODE_BLUETOOTH?Color.rgb(5,31,37):white);
        wifiTab.setTextColor(mode==MODE_WIFI?Color.rgb(5,31,37):white);
        bluetoothTab.setBackground(panel(mode==MODE_BLUETOOTH?mint:card,18));
        wifiTab.setBackground(panel(mode==MODE_WIFI?mint:card,18));
        renderList();startMode();
    }

    private void startMode(){
        if(!hasPermissions()){requestRadioPermissions();return;}
        stopScans();
        if(mode==MODE_BLUETOOTH)startBluetooth();
        else{
            status.setText("Wi-Fiを検索中（更新は約10秒ごと）");wifiScanTask.run();
            if(selectedKey!=null)connectedWifiSampleTask.run();
        }
    }

    private void startBluetooth(){
        try{
            BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null){status.setText("この端末はBluetoothに対応していません");return;}
            if(!adapter.isEnabled()){status.setText("BluetoothをONにしてください");return;}
            bluetoothScanner=adapter.getBluetoothLeScanner();
            if(bluetoothScanner==null){status.setText("Bluetoothスキャナーを開始できません");return;}
            ScanSettings settings=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            bluetoothScanner.startScan(null,settings,bluetoothCallback);
            status.setText("Bluetooth機器を検索中 • 機器をタップして追跡");
        }catch(SecurityException e){status.setText("Bluetoothの権限が必要です");}
    }

    private void consumeBluetooth(android.bluetooth.le.ScanResult result){
        if(mode!=MODE_BLUETOOTH)return;
        String key=result.getDevice().getAddress(),name=null;
        try{name=result.getDevice().getName();}catch(SecurityException ignored){}
        if((name==null||name.trim().isEmpty())&&result.getScanRecord()!=null)name=result.getScanRecord().getDeviceName();
        if(name==null||name.trim().isEmpty())name="名前なしのBluetooth機器";
        updateItem(new RadioItem(key,name,result.getRssi(),"Bluetooth",result.getTimestampNanos()));
    }

    private void scanWifi(){
        try{
            if(!wifiManager.isWifiEnabled()){status.setText("Wi-FiをONにしてください");return;}
            wifiManager.startScan();consumeWifiResults();
        }catch(SecurityException e){status.setText("Wi-Fiと位置情報の権限が必要です");}
    }

    private void consumeWifiResults(){
        if(mode!=MODE_WIFI||!hasPermissions())return;
        try{
            List<android.net.wifi.ScanResult> results=wifiManager.getScanResults();
            for(android.net.wifi.ScanResult result:results){
                String name=result.SSID;
                if(name==null||name.trim().isEmpty())name="非公開Wi-Fi";
                updateItem(new RadioItem(result.BSSID,name,result.level,"Wi-Fi",result.timestamp*1000L));
            }
            if(selectedKey==null)status.setText("Wi-Fiを検索中 • アクセスポイントをタップして追跡");
        }catch(SecurityException e){status.setText("Wi-Fiと位置情報の権限が必要です");}
    }

    private void updateItem(RadioItem measurement){
        RadioItem item=items.get(measurement.key);
        boolean freshMeasurement=item==null||measurement.measuredAtNanos>item.measuredAtNanos;
        if(item==null){item=measurement;items.put(item.key,item);}
        else if(freshMeasurement)item.addMeasurement(measurement.rssi,measurement.measuredAtNanos);
        if(freshMeasurement&&item.key.equals(selectedKey)){
            finder.updateSignal(measurement.rssi,headingAt(measurement.measuredAtNanos),measurement.measuredAtNanos);
        }
        if(!renderPending){renderPending=true;handler.postDelayed(()->{renderPending=false;renderList();},450);}
    }

    private void renderList(){
        if(list==null)return;list.removeAllViews();float d=getResources().getDisplayMetrics().density;
        ArrayList<RadioItem> sorted=new ArrayList<>(items.values());
        Collections.sort(sorted,Comparator.comparingDouble((RadioItem item)->item.filteredRssi).reversed());
        if(sorted.isEmpty()){
            TextView empty=text(mode==MODE_BLUETOOTH?"まだ見つかっていません\n対象機器の電源・探索モードを確認してください":"まだWi-Fiが見つかっていません",14,muted,false);
            empty.setPadding((int)(16*d),(int)(18*d),(int)(16*d),(int)(18*d));empty.setBackground(panel(card,18));list.addView(empty);return;
        }
        int count=Math.min(30,sorted.size());
        for(int i=0;i<count;i++){
            RadioItem item=sorted.get(i);int shownRssi=Math.round(item.filteredRssi);
            TextView row=text(item.name+"\n"+strength(shownRssi)+"   "+shownRssi+" dBm",15,white,i==0);
            row.setPadding((int)(16*d),(int)(13*d),(int)(16*d),(int)(13*d));
            row.setBackground(panel(item.key.equals(selectedKey)?Color.rgb(24,68,73):card,18));
            row.setOnClickListener(v->selectItem(item));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=(int)(8*d);list.addView(row,params);
        }
    }

    private void selectItem(RadioItem item){
        selectedKey=item.key;finder.setTarget(item.name,item.type);finder.setCurrentSignal(Math.round(item.filteredRssi));
        status.setText(item.name+" を追跡中 • 同じ場所で水平に持ってゆっくり一周");
        handler.removeCallbacks(connectedWifiSampleTask);if(mode==MODE_WIFI)connectedWifiSampleTask.run();renderList();
    }

    private static String strength(int rssi){
        if(rssi>=-50)return "かなり近い";if(rssi>=-60)return "近い";
        if(rssi>=-72)return "中くらい";if(rssi>=-85)return "遠い";return "かなり遠い";
    }

    private boolean hasPermissions(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return false;
        if(Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED))return false;
        return Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)==PackageManager.PERMISSION_GRANTED;
    }

    private void requestRadioPermissions(){
        ArrayList<String> permissions=new ArrayList<>();permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(Build.VERSION.SDK_INT>=31){permissions.add(Manifest.permission.BLUETOOTH_SCAN);permissions.add(Manifest.permission.BLUETOOTH_CONNECT);}
        if(Build.VERSION.SDK_INT>=33)permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        requestPermissions(permissions.toArray(new String[0]),REQUEST_RADIO_PERMISSIONS);
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==REQUEST_RADIO_PERMISSIONS){if(hasPermissions())startMode();else status.setText("Bluetooth・Wi-Fi・位置情報の権限を許可してください");}
    }

    @Override protected void onResume(){
        super.onResume();Sensor rotation=sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if(rotation!=null)sensorManager.registerListener(this,rotation,SensorManager.SENSOR_DELAY_UI);
        if(hasPermissions())startMode();
    }

    @Override protected void onPause(){super.onPause();sensorManager.unregisterListener(this);stopScans();}

    @Override protected void onDestroy(){
        stopScans();if(receiverRegistered){unregisterReceiver(wifiReceiver);receiverRegistered=false;}super.onDestroy();
    }

    private void stopScans(){
        handler.removeCallbacks(wifiScanTask);handler.removeCallbacks(connectedWifiSampleTask);
        if(bluetoothScanner!=null){try{bluetoothScanner.stopScan(bluetoothCallback);}catch(SecurityException ignored){}bluetoothScanner=null;}
    }

    @Override public void onSensorChanged(SensorEvent event){
        if(event.sensor.getType()!=Sensor.TYPE_ROTATION_VECTOR)return;
        float[] matrix=new float[9],orientation=new float[3];
        SensorManager.getRotationMatrixFromVector(matrix,event.values);SensorManager.getOrientation(matrix,orientation);
        heading=(float)((Math.toDegrees(orientation[0])+360)%360);
        headingTimes[headingWriteIndex]=event.timestamp;headingValues[headingWriteIndex]=heading;
        headingWriteIndex=(headingWriteIndex+1)%headingTimes.length;
        if(headingHistorySize<headingTimes.length)headingHistorySize++;
        finder.updateHeading(heading);
    }

    private float headingAt(long measuredAtNanos){
        if(measuredAtNanos<=0||headingHistorySize==0)return heading;
        long bestDifference=Long.MAX_VALUE;float bestHeading=heading;
        for(int i=0;i<headingHistorySize;i++){
            long difference=Math.abs(headingTimes[i]-measuredAtNanos);
            if(difference<bestDifference){bestDifference=difference;bestHeading=headingValues[i];}
        }
        return bestHeading;
    }

    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}

    private TextView text(String value,float size,int color,boolean bold){
        TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);
        view.setTypeface(Typeface.create("sans",bold?Typeface.BOLD:Typeface.NORMAL));return view;
    }

    private GradientDrawable panel(int color,float radius){
        GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(radius*getResources().getDisplayMetrics().density);return drawable;
    }

    private static class RadioItem{
        final String key,name,type;int rssi;float filteredRssi;long measuredAtNanos;
        RadioItem(String key,String name,int rssi,String type,long measuredAtNanos){
            this.key=key;this.name=name;this.type=type;this.rssi=rssi;filteredRssi=rssi;this.measuredAtNanos=measuredAtNanos;
        }
        void addMeasurement(int value,long timestamp){
            rssi=value;filteredRssi=filteredRssi*.72f+value*.28f;measuredAtNanos=timestamp;
        }
    }

    private static class FinderView extends View{
        static final int SECTOR_COUNT=24;
        static final long MAX_SAMPLE_AGE_NANOS=90_000_000_000L;
        final Paint paint=new Paint(3);final float[] sectors=new float[SECTOR_COUNT];
        final int[] sampleCounts=new int[SECTOR_COUNT];final long[] lastSamples=new long[SECTOR_COUNT];
        final Typeface normalTypeface=Typeface.create("sans",Typeface.NORMAL),boldTypeface=Typeface.create("sans",Typeface.BOLD);
        final int card=Color.rgb(16,31,48),mint=Color.rgb(0,212,170),white=Color.rgb(238,246,252),muted=Color.rgb(143,163,180);
        String target="対象を下の一覧から選択",type="";float heading;int rssi=-127;
        boolean hasHeading;
        FinderView(Context context){super(context);Arrays.fill(sectors,-127);paint.setTypeface(normalTypeface);setBackgroundColor(Color.TRANSPARENT);}
        void clearTarget(){target="対象を下の一覧から選択";type="";rssi=-127;resetMeasurements();}
        void setTarget(String name,String type){target=name;this.type=type;rssi=-127;resetMeasurements();}
        void setCurrentSignal(int value){rssi=value;invalidate();}
        void resetMeasurements(){Arrays.fill(sectors,-127);Arrays.fill(sampleCounts,0);Arrays.fill(lastSamples,0);invalidate();}
        void updateHeading(float value){
            if(!hasHeading){heading=value;hasHeading=true;}
            else heading=normalize(heading+shortestTurn(heading,value)*.22f);
            postInvalidateOnAnimation();
        }
        void updateSignal(int value,float direction,long measuredAtNanos){
            rssi=rssi<=-127?value:Math.round(rssi*.68f+value*.32f);
            int sector=Math.round(normalize(direction)/(360f/SECTOR_COUNT))%SECTOR_COUNT;
            int count=sampleCounts[sector];
            if(count==0)sectors[sector]=value;
            else{
                float difference=value-sectors[sector];
                float limited=Math.max(-10f,Math.min(10f,difference));
                float alpha=count<3?.45f:.28f;
                sectors[sector]+=limited*alpha;
            }
            sampleCounts[sector]=Math.min(30,count+1);
            lastSamples[sector]=measuredAtNanos>0?measuredAtNanos:SystemClock.elapsedRealtimeNanos();
            postInvalidateOnAnimation();
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float d=getResources().getDisplayMetrics().density;canvas.save();canvas.scale(d,d);float w=getWidth()/d,cx=w/2;
            paint.setColor(card);canvas.drawRoundRect(0,0,w,290,24,24,paint);
            draw(canvas,target,18,30,31,white,true);draw(canvas,type.isEmpty()?"待機中":type+"を追跡中",13,30,54,mint,false);
            String reading=rssi<=-127?"-- dBm":rssi+" dBm  •  "+strength(rssi);draw(canvas,reading,15,30,82,white,true);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(Color.rgb(49,78,98));canvas.drawCircle(cx,172,66,paint);canvas.drawCircle(cx,172,48,paint);paint.setStyle(Paint.Style.FILL);
            long now=SystemClock.elapsedRealtimeNanos();int seenCount=0,totalSamples=0;
            float scoreSum=0;int scoreCount=0,best=-1;float bestScore=-128;
            for(int i=0;i<SECTOR_COUNT;i++){
                if(isFresh(i,now)){seenCount++;totalSamples+=sampleCounts[i];}
                float score=smoothedScore(i,now);
                if(score>-127){scoreSum+=score;scoreCount++;if(score>bestScore){bestScore=score;best=i;}}
            }
            float bestDirection=best<0?heading:refinedDirection(best,bestScore,now);
            float relative=shortestTurn(heading,bestDirection);
            canvas.save();canvas.rotate(relative,cx,172);Path arrow=new Path();arrow.moveTo(cx,112);arrow.lineTo(cx-15,144);arrow.lineTo(cx-6,141);arrow.lineTo(cx-6,208);arrow.lineTo(cx+6,208);arrow.lineTo(cx+6,141);arrow.lineTo(cx+15,144);arrow.close();
            float contrast=scoreCount==0?0:bestScore-scoreSum/scoreCount;
            float confidence=Math.min(1f,seenCount/(SECTOR_COUNT*.7f))*.65f+Math.min(1f,contrast/7f)*.35f;
            boolean ready=seenCount>=6&&totalSamples>=10;
            paint.setColor(ready?mint:muted);canvas.drawPath(arrow,paint);canvas.restore();
            String confidenceText=confidence>=.72f?"高":confidence>=.45f?"中":"低";
            String guide=target.startsWith("対象を")?"一覧から探したい電波をタップ":
                    (!ready?"測定 "+seenCount+"/"+SECTOR_COUNT+"方向 • ゆっくり一周":"推定精度 "+confidenceText+" • 矢印方向へ近づいて再測定");
            draw(canvas,guide,13,30,260,ready?mint:muted,true);canvas.restore();
        }
        boolean isFresh(int index,long now){return sampleCounts[index]>0&&now-lastSamples[index]<=MAX_SAMPLE_AGE_NANOS;}
        float smoothedScore(int center,long now){
            float total=0,weights=0;
            for(int offset=-2;offset<=2;offset++){
                int index=(center+offset+SECTOR_COUNT)%SECTOR_COUNT;if(!isFresh(index,now))continue;
                float weight=offset==0?4f:Math.abs(offset)==1?2f:1f;
                weight*=Math.min(1f,.35f+sampleCounts[index]*.22f);total+=sectors[index]*weight;weights+=weight;
            }
            return weights==0?-127:total/weights;
        }
        float refinedDirection(int best,float bestScore,long now){
            double x=0,y=0;
            for(int offset=-2;offset<=2;offset++){
                int index=(best+offset+SECTOR_COUNT)%SECTOR_COUNT;if(!isFresh(index,now))continue;
                float score=smoothedScore(index,now);double weight=Math.pow(10,(score-bestScore)/10.0);
                double angle=Math.toRadians(index*(360.0/SECTOR_COUNT));x+=Math.cos(angle)*weight;y+=Math.sin(angle)*weight;
            }
            return normalize((float)Math.toDegrees(Math.atan2(y,x)));
        }
        static float normalize(float degrees){degrees%=360f;return degrees<0?degrees+360f:degrees;}
        static float shortestTurn(float from,float to){return ((to-from+540f)%360f)-180f;}
        void draw(Canvas c,String text,float size,float x,float y,int color,boolean bold){paint.setTextSize(size);paint.setColor(color);paint.setTypeface(bold?boldTypeface:normalTypeface);c.drawText(text,x,y,paint);}
    }
}
