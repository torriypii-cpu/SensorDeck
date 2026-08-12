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
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
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
import java.util.Locale;

public class SignalFinderActivity extends Activity implements SensorEventListener {
    private static final int REQUEST_RADIO_PERMISSIONS=40;
    private static final int MODE_BLUETOOTH=1,MODE_WIFI=2;
    private final int bg=Color.rgb(7,17,31),card=Color.rgb(16,31,48);
    private final int mint=Color.rgb(0,212,170),white=Color.rgb(238,246,252),muted=Color.rgb(143,163,180);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final HashMap<String,RadioItem> items=new HashMap<>();
    private final long[] headingTimes=new long[2048];
    private final float[] headingValues=new float[2048],headingRates=new float[2048],tiltValues=new float[2048];
    private final float[] matchedPose=new float[3];
    private int mode=MODE_BLUETOOTH;
    private int headingWriteIndex,headingHistorySize,wifiBurstScans;
    private String selectedKey;
    private float heading,headingRate,tiltDegrees,lastRawHeading;
    private long lastHeadingTimestamp;
    private LinearLayout list;
    private TextView status,bluetoothTab,wifiTab;
    private Button calibrationButton;
    private FinderView finder;
    private BluetoothLeScanner bluetoothScanner;
    private WifiManager wifiManager;
    private WifiRttManager wifiRttManager;
    private SensorManager sensorManager;
    private boolean receiverRegistered,renderPending,rttInFlight,paused;

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
            if(scanWifi())wifiBurstScans++;
            boolean burst=finder!=null&&finder.isDirectionCollecting()&&selectedKey!=null&&wifiBurstScans<4;
            handler.postDelayed(this,burst?5000:30000);
        }
    };

    private final Runnable connectedWifiSampleTask=new Runnable(){
        @Override public void run(){
            if(paused||mode!=MODE_WIFI||selectedKey==null||!hasPermissions())return;
            try{
                WifiInfo info=wifiManager.getConnectionInfo();
                RadioItem selected=items.get(selectedKey);String bssid=info==null?null:info.getBSSID();
                if(selected!=null&&selectedKey.equalsIgnoreCase(bssid)){
                    updateItem(new RadioItem(selected.key,selected.name,info.getRssi(),selected.type,
                            SystemClock.elapsedRealtimeNanos(),info.getFrequency(),RadioItem.NO_TX_POWER,null));
                    status.setText(selected.name+" を高精度追跡中 • 同じ場所で水平に持って一周");
                }
            }catch(SecurityException ignored){}
            handler.postDelayed(this,finder!=null&&finder.isDirectionCollecting()?350:700);
        }
    };

    private final Runnable rttSampleTask=new Runnable(){
        @Override public void run(){startWifiRanging();}
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        sensorManager=(SensorManager)getSystemService(SENSOR_SERVICE);
        wifiManager=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if(Build.VERSION.SDK_INT>=28)wifiRttManager=getSystemService(WifiRttManager.class);
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
        TextView note=text("Bluetooth / Wi-Fiの方向と距離を測定\n水平に持ってゆっくり一周すると方向精度が上がります",14,muted,false);
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
        Button reset=new Button(this);reset.setText("▶ 方向スキャンを開始・やり直す");reset.setAllCaps(false);reset.setTextColor(white);
        reset.setTextSize(14);reset.setBackground(panel(card,18));
        reset.setOnClickListener(v->startDirectionScan());
        LinearLayout.LayoutParams resetParams=new LinearLayout.LayoutParams(-1,(int)(48*d));resetParams.topMargin=(int)(10*d);root.addView(reset,resetParams);
        calibrationButton=new Button(this);calibrationButton.setText("1m離れた位置で距離を校正");calibrationButton.setAllCaps(false);
        calibrationButton.setTextColor(mint);calibrationButton.setTextSize(14);calibrationButton.setBackground(panel(card,18));
        calibrationButton.setOnClickListener(v->calibrateDistance());
        LinearLayout.LayoutParams calibrationParams=new LinearLayout.LayoutParams(-1,(int)(48*d));calibrationParams.topMargin=(int)(8*d);
        root.addView(calibrationButton,calibrationParams);calibrationButton.setEnabled(false);
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
        calibrationButton.setEnabled(false);calibrationButton.setText("1m離れた位置で距離を校正");
        renderList();startMode();
    }

    private void startMode(){
        if(!hasPermissions()){requestRadioPermissions();return;}
        stopScans();
        if(mode==MODE_BLUETOOTH)startBluetooth();
        else{
            status.setText("Wi-Fiを検索中 • アクセスポイントをタップして追跡");wifiScanTask.run();
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
            ScanSettings settings=new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    .setReportDelay(0)
                    .setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED).build();
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
        int txPower=result.getTxPower();
        if(txPower==android.bluetooth.le.ScanResult.TX_POWER_NOT_PRESENT&&result.getScanRecord()!=null){
            int advertised=result.getScanRecord().getTxPowerLevel();if(advertised!=Integer.MIN_VALUE)txPower=advertised;
        }
        if(txPower==android.bluetooth.le.ScanResult.TX_POWER_NOT_PRESENT)txPower=RadioItem.NO_TX_POWER;
        updateItem(new RadioItem(key,name,result.getRssi(),"Bluetooth",result.getTimestampNanos(),2400,txPower,null));
    }

    private boolean scanWifi(){
        try{
            if(!wifiManager.isWifiEnabled()){status.setText("Wi-FiをONにしてください");return false;}
            boolean started=wifiManager.startScan();consumeWifiResults();return started;
        }catch(SecurityException e){status.setText("Wi-Fiと位置情報の権限が必要です");}
        return false;
    }

    private void consumeWifiResults(){
        if(mode!=MODE_WIFI||!hasPermissions())return;
        try{
            List<android.net.wifi.ScanResult> results=wifiManager.getScanResults();
            for(android.net.wifi.ScanResult result:results){
                String name=result.SSID;
                if(name==null||name.trim().isEmpty())name="非公開Wi-Fi";
                updateItem(new RadioItem(result.BSSID,name,result.level,"Wi-Fi",result.timestamp*1000L,
                        result.frequency,RadioItem.NO_TX_POWER,result));
            }
            if(selectedKey==null)status.setText("Wi-Fiを検索中 • アクセスポイントをタップして追跡");
        }catch(SecurityException e){status.setText("Wi-Fiと位置情報の権限が必要です");}
    }

    private void updateItem(RadioItem measurement){
        RadioItem item=items.get(measurement.key);
        boolean freshMeasurement=item==null||measurement.measuredAtNanos>item.measuredAtNanos;
        if(item==null){item=measurement;items.put(item.key,item);}
        else{
            item.mergeRadioDetails(measurement);
            if(freshMeasurement)item.addMeasurement(measurement.rssi,measurement.measuredAtNanos);
        }
        if(freshMeasurement&&item.key.equals(selectedKey)){
            finder.updateRadioProfile(item.frequency,item.txPower,referenceRssi(item),isCalibrated(item));
            poseAt(measurement.measuredAtNanos,matchedPose);
            finder.updateSignal(measurement.rssi,matchedPose[0],measurement.measuredAtNanos,matchedPose[1],matchedPose[2]);
        }
        if(!renderPending){renderPending=true;handler.postDelayed(()->{renderPending=false;renderList();},1200);}
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
            String detail=strength(shownRssi)+"   "+shownRssi+" dBm";
            if(item.type.equals("Wi-Fi"))detail+="   •   "+wifiBand(item.frequency);
            if(!Float.isNaN(item.rttDistanceMeters))detail+="\nRTT実測  "+formatMeters(item.rttDistanceMeters);
            TextView row=text(item.name+"\n"+detail,15,white,i==0);
            row.setPadding((int)(16*d),(int)(13*d),(int)(16*d),(int)(13*d));
            row.setBackground(panel(item.key.equals(selectedKey)?Color.rgb(24,68,73):card,18));
            row.setOnClickListener(v->selectItem(item));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=(int)(8*d);list.addView(row,params);
        }
    }

    private void selectItem(RadioItem item){
        selectedKey=item.key;
        finder.setTarget(item.name,item.type,item.frequency,item.txPower,referenceRssi(item),isCalibrated(item),item.rttCapable);
        finder.setCurrentSignal(Math.round(item.filteredRssi),item.rssiSpread);
        if(!Float.isNaN(item.rttDistanceMeters))finder.updateRttDistance(item.rttDistanceMeters,item.rttUncertaintyMeters);
        calibrationButton.setEnabled(true);updateCalibrationButton(item);startDirectionScan();
        poseAt(item.measuredAtNanos,matchedPose);
        finder.updateSignal(item.rssi,matchedPose[0],item.measuredAtNanos,matchedPose[1],matchedPose[2]);
        handler.removeCallbacks(connectedWifiSampleTask);handler.removeCallbacks(rttSampleTask);
        if(mode==MODE_WIFI){connectedWifiSampleTask.run();rttSampleTask.run();}
        renderList();
    }

    private void startDirectionScan(){
        finder.resetDirectionMeasurements();
        if(selectedKey==null){status.setText("先に下の一覧から探したい電波を選んでください");return;}
        RadioItem item=items.get(selectedKey);if(item==null)return;
        status.setText(item.name+" の方向スキャン中 • 水平に持ってゆっくり一周");
        if(mode==MODE_WIFI){
            wifiBurstScans=0;handler.removeCallbacks(wifiScanTask);wifiScanTask.run();
            handler.removeCallbacks(connectedWifiSampleTask);connectedWifiSampleTask.run();
            if(!isConnectedTo(item)&&!item.rttCapable)
                status.setText("方向スキャン中 • このWi-Fiへ接続すると反応と精度が大きく上がります");
        }
    }

    private boolean isConnectedTo(RadioItem item){
        if(item==null||!item.type.equals("Wi-Fi"))return false;
        try{WifiInfo info=wifiManager.getConnectionInfo();return info!=null&&item.key.equalsIgnoreCase(info.getBSSID());}
        catch(SecurityException e){return false;}
    }

    private void calibrateDistance(){
        RadioItem item=selectedKey==null?null:items.get(selectedKey);
        if(item==null){status.setText("先に対象を選んでください");return;}
        if(item.recentCount<3){status.setText("電波をあと少し測定してから校正してください");return;}
        getSharedPreferences("radio_distance_calibration",MODE_PRIVATE).edit()
                .putFloat(calibrationKey(item),item.filteredRssi).apply();
        finder.updateRadioProfile(item.frequency,item.txPower,item.filteredRssi,true);
        updateCalibrationButton(item);status.setText("1m基準を保存しました • この機器の推定距離が安定します");
    }

    private void updateCalibrationButton(RadioItem item){
        calibrationButton.setText(isCalibrated(item)?"✓ 1m校正済み（再校正）":"1m離れた位置で距離を校正");
    }

    private boolean isCalibrated(RadioItem item){
        return getSharedPreferences("radio_distance_calibration",MODE_PRIVATE).contains(calibrationKey(item));
    }

    private float referenceRssi(RadioItem item){
        android.content.SharedPreferences preferences=getSharedPreferences("radio_distance_calibration",MODE_PRIVATE);
        String key=calibrationKey(item);if(preferences.contains(key))return preferences.getFloat(key,-50);
        if(item.type.equals("Bluetooth")){
            if(item.txPower!=RadioItem.NO_TX_POWER)return Math.max(-65,Math.min(-32,item.txPower-42));
            return -52;
        }
        if(item.frequency>=5925)return -47;if(item.frequency>=4900)return -44;return -41;
    }

    private static String calibrationKey(RadioItem item){return item.type+"_"+item.key;}
    private static String wifiBand(int frequency){
        if(frequency>=5925)return "6 GHz";if(frequency>=4900)return "5 GHz";if(frequency>0)return "2.4 GHz";return "周波数不明";
    }
    private static String formatMeters(float value){return value<10?String.format(Locale.JAPAN,"%.1f m",value):String.format(Locale.JAPAN,"%.0f m",value);}

    private static String strength(int rssi){
        if(rssi>=-50)return "かなり近い";if(rssi>=-60)return "近い";
        if(rssi>=-72)return "中くらい";if(rssi>=-85)return "遠い";return "かなり遠い";
    }

    private void startWifiRanging(){
        if(paused||rttInFlight||mode!=MODE_WIFI||selectedKey==null||Build.VERSION.SDK_INT<28)return;
        RadioItem target=items.get(selectedKey);
        if(target==null||target.wifiScanResult==null||!target.rttCapable||wifiRttManager==null||!wifiRttManager.isAvailable())return;
        final String requestedKey=target.key;
        try{
            RangingRequest request=new RangingRequest.Builder().addAccessPoint(target.wifiScanResult).build();
            rttInFlight=true;
            wifiRttManager.startRanging(request,getMainExecutor(),new RangingResultCallback(){
                @Override public void onRangingFailure(int code){
                    rttInFlight=false;if(!paused&&requestedKey.equals(selectedKey))handler.postDelayed(rttSampleTask,2500);
                }
                @Override public void onRangingResults(List<RangingResult> results){
                    rttInFlight=false;
                    if(paused||!requestedKey.equals(selectedKey))return;
                    for(RangingResult result:results){
                        if(result.getStatus()!=RangingResult.STATUS_SUCCESS||result.getMacAddress()==null||
                                !requestedKey.equalsIgnoreCase(result.getMacAddress().toString()))continue;
                        float distance=Math.max(0,result.getDistanceMm())/1000f;
                        int successes=result.getNumSuccessfulMeasurements();
                        float deviation=successes>1?result.getDistanceStdDevMm()/1000f:Math.max(.35f,distance*.18f);
                        RadioItem item=items.get(requestedKey);
                        if(item!=null&&distance<=100&&deviation<=25){
                            item.addRttMeasurement(distance,deviation);finder.updateRttDistance(item.rttDistanceMeters,item.rttUncertaintyMeters);
                            if(!renderPending){renderPending=true;handler.postDelayed(()->{renderPending=false;renderList();},1200);}
                        }
                    }
                    handler.postDelayed(rttSampleTask,1500);
                }
            });
        }catch(SecurityException|IllegalArgumentException|IllegalStateException e){
            rttInFlight=false;if(!paused&&requestedKey.equals(selectedKey))handler.postDelayed(rttSampleTask,3000);
        }
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
        super.onResume();paused=false;Sensor rotation=sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if(rotation!=null)sensorManager.registerListener(this,rotation,SensorManager.SENSOR_DELAY_GAME);
        if(hasPermissions())startMode();
    }

    @Override protected void onPause(){super.onPause();paused=true;sensorManager.unregisterListener(this);stopScans();}

    @Override protected void onDestroy(){
        stopScans();if(receiverRegistered){unregisterReceiver(wifiReceiver);receiverRegistered=false;}super.onDestroy();
    }

    private void stopScans(){
        handler.removeCallbacks(wifiScanTask);handler.removeCallbacks(connectedWifiSampleTask);handler.removeCallbacks(rttSampleTask);
        rttInFlight=false;
        if(bluetoothScanner!=null){try{bluetoothScanner.stopScan(bluetoothCallback);}catch(SecurityException ignored){}bluetoothScanner=null;}
    }

    @Override public void onSensorChanged(SensorEvent event){
        if(event.sensor.getType()!=Sensor.TYPE_ROTATION_VECTOR)return;
        float[] matrix=new float[9],orientation=new float[3];
        SensorManager.getRotationMatrixFromVector(matrix,event.values);SensorManager.getOrientation(matrix,orientation);
        float rawHeading=(float)((Math.toDegrees(orientation[0])+360)%360);
        if(lastHeadingTimestamp>0){
            float seconds=(event.timestamp-lastHeadingTimestamp)/1_000_000_000f;
            if(seconds>0)headingRate=headingRate*.72f+Math.abs(FinderView.shortestTurn(lastRawHeading,rawHeading))/seconds*.28f;
        }
        lastRawHeading=rawHeading;lastHeadingTimestamp=event.timestamp;heading=rawHeading;
        tiltDegrees=Math.max(Math.abs((float)Math.toDegrees(orientation[1])),Math.abs((float)Math.toDegrees(orientation[2])));
        headingTimes[headingWriteIndex]=event.timestamp;headingValues[headingWriteIndex]=heading;
        headingRates[headingWriteIndex]=headingRate;tiltValues[headingWriteIndex]=tiltDegrees;
        headingWriteIndex=(headingWriteIndex+1)%headingTimes.length;
        if(headingHistorySize<headingTimes.length)headingHistorySize++;
        finder.updateHeading(heading,tiltDegrees);
    }

    private void poseAt(long measuredAtNanos,float[] result){
        result[0]=heading;result[1]=headingRate;result[2]=tiltDegrees;
        if(measuredAtNanos<=0||headingHistorySize==0)return;
        long bestDifference=Long.MAX_VALUE;int best=-1;
        for(int i=0;i<headingHistorySize;i++){
            long difference=Math.abs(headingTimes[i]-measuredAtNanos);
            if(difference<bestDifference){bestDifference=difference;best=i;}
        }
        if(best>=0){result[0]=headingValues[best];result[1]=headingRates[best];result[2]=tiltValues[best];}
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
        static final int NO_TX_POWER=Integer.MIN_VALUE;
        final String key,name,type;final float[] recentRssi=new float[15],recentRtt=new float[7];
        int rssi,frequency,txPower,recentCount,recentWrite,rttCount,rttWrite;
        float filteredRssi,rssiSpread,rttDistanceMeters=Float.NaN,rttUncertaintyMeters=Float.NaN;
        long measuredAtNanos;android.net.wifi.ScanResult wifiScanResult;boolean rttCapable;
        RadioItem(String key,String name,int rssi,String type,long measuredAtNanos,int frequency,int txPower,
                  android.net.wifi.ScanResult wifiScanResult){
            this.key=key;this.name=name;this.type=type;this.frequency=frequency;this.txPower=txPower;
            this.wifiScanResult=wifiScanResult;rttCapable=isRttResponder(wifiScanResult);
            this.measuredAtNanos=measuredAtNanos;addMeasurement(rssi,measuredAtNanos);
        }
        void addMeasurement(int value,long timestamp){
            rssi=value;recentRssi[recentWrite]=value;recentWrite=(recentWrite+1)%recentRssi.length;
            recentCount=Math.min(recentRssi.length,recentCount+1);float[] sorted=Arrays.copyOf(recentRssi,recentCount);
            Arrays.sort(sorted);float median=sorted[recentCount/2];
            float[] deviations=new float[recentCount];for(int i=0;i<recentCount;i++)deviations[i]=Math.abs(sorted[i]-median);
            Arrays.sort(deviations);rssiSpread=Math.max(1f,deviations[recentCount/2]*1.4826f);
            filteredRssi=recentCount==1?median:filteredRssi*.62f+median*.38f;measuredAtNanos=timestamp;
        }
        void mergeRadioDetails(RadioItem other){
            if(other.frequency>0)frequency=other.frequency;if(other.txPower!=NO_TX_POWER)txPower=other.txPower;
            if(other.wifiScanResult!=null){wifiScanResult=other.wifiScanResult;rttCapable=isRttResponder(wifiScanResult);}
        }
        void addRttMeasurement(float distance,float uncertainty){
            recentRtt[rttWrite]=distance;rttWrite=(rttWrite+1)%recentRtt.length;rttCount=Math.min(recentRtt.length,rttCount+1);
            float[] sorted=Arrays.copyOf(recentRtt,rttCount);Arrays.sort(sorted);float median=sorted[rttCount/2];
            float[] deviations=new float[rttCount];for(int i=0;i<rttCount;i++)deviations[i]=Math.abs(sorted[i]-median);
            Arrays.sort(deviations);float observed=deviations[rttCount/2]*1.4826f;
            rttDistanceMeters=median;rttUncertaintyMeters=Math.max(.15f,Math.max(observed,uncertainty));
        }
        static boolean isRttResponder(android.net.wifi.ScanResult result){
            return result!=null&&(result.is80211mcResponder()||(Build.VERSION.SDK_INT>=35&&result.is80211azNtbResponder()));
        }
    }

    private static class FinderView extends View{
        static final int SECTOR_COUNT=18,SAMPLES_PER_SECTOR=5;
        static final long MAX_SAMPLE_AGE_NANOS=75_000_000_000L;
        final Paint paint=new Paint(3);final float[] sectors=new float[SECTOR_COUNT];
        final float[][] sectorSamples=new float[SECTOR_COUNT][SAMPLES_PER_SECTOR];
        final int[] sampleCounts=new int[SECTOR_COUNT],storedSamples=new int[SECTOR_COUNT],sampleWrites=new int[SECTOR_COUNT];
        final long[] lastSamples=new long[SECTOR_COUNT];final float[] distanceSignals=new float[15];
        final Typeface normalTypeface=Typeface.create("sans",Typeface.NORMAL),boldTypeface=Typeface.create("sans",Typeface.BOLD);
        final int card=Color.rgb(16,31,48),mint=Color.rgb(0,212,170),amber=Color.rgb(255,190,80);
        final int white=Color.rgb(238,246,252),muted=Color.rgb(143,163,180);
        String target="対象を下の一覧から選択",type="";float heading,tiltDegrees,rssiSpread=6,referenceRssi=-50;
        float rttDistance=Float.NaN,rttUncertainty=Float.NaN,displayedDirection;int rssi=-127,frequency,txPower=RadioItem.NO_TX_POWER;
        int distanceSignalCount,distanceSignalWrite;long rttUpdatedNanos,directionStartedNanos;
        boolean hasHeading,hasDisplayedDirection,calibrated,rttCapable;
        FinderView(Context context){super(context);Arrays.fill(sectors,-127);paint.setTypeface(normalTypeface);setBackgroundColor(Color.TRANSPARENT);}
        void clearTarget(){
            target="対象を下の一覧から選択";type="";rssi=-127;rttDistance=Float.NaN;distanceSignalCount=0;
            resetDirectionMeasurements();
        }
        void setTarget(String name,String type,int frequency,int txPower,float referenceRssi,boolean calibrated,boolean rttCapable){
            target=name;this.type=type;rssi=-127;rttDistance=Float.NaN;distanceSignalCount=0;
            updateRadioProfile(frequency,txPower,referenceRssi,calibrated);this.rttCapable=rttCapable;resetDirectionMeasurements();
        }
        void setCurrentSignal(int value,float spread){rssi=value;rssiSpread=spread;recordDistanceSignal(value);invalidate();}
        void updateRadioProfile(int frequency,int txPower,float referenceRssi,boolean calibrated){
            if(frequency>0)this.frequency=frequency;if(txPower!=RadioItem.NO_TX_POWER)this.txPower=txPower;
            this.referenceRssi=referenceRssi;this.calibrated=calibrated;postInvalidateOnAnimation();
        }
        void resetDirectionMeasurements(){
            Arrays.fill(sectors,-127);Arrays.fill(sampleCounts,0);Arrays.fill(storedSamples,0);Arrays.fill(sampleWrites,0);
            Arrays.fill(lastSamples,0);hasDisplayedDirection=false;directionStartedNanos=SystemClock.elapsedRealtimeNanos();invalidate();
        }
        boolean isDirectionCollecting(){return directionStartedNanos>0&&SystemClock.elapsedRealtimeNanos()-directionStartedNanos<45_000_000_000L;}
        void updateHeading(float value,float tilt){
            tiltDegrees=tilt;
            if(!hasHeading){heading=value;hasHeading=true;}
            else heading=normalize(heading+shortestTurn(heading,value)*.38f);
            postInvalidateOnAnimation();
        }
        void updateSignal(int value,float direction,long measuredAtNanos,float rotationRate,float tilt){
            long now=SystemClock.elapsedRealtimeNanos();
            if(measuredAtNanos>0&&Math.abs(now-measuredAtNanos)>8_000_000_000L)return;
            recordDistanceSignal(value);tiltDegrees=tilt;
            if(rotationRate>165f||tilt>52f){postInvalidateOnAnimation();return;}
            int sector=Math.round(normalize(direction)/(360f/SECTOR_COUNT))%SECTOR_COUNT;
            sectorSamples[sector][sampleWrites[sector]]=value;sampleWrites[sector]=(sampleWrites[sector]+1)%SAMPLES_PER_SECTOR;
            storedSamples[sector]=Math.min(SAMPLES_PER_SECTOR,storedSamples[sector]+1);
            sampleCounts[sector]=Math.min(60,sampleCounts[sector]+1);
            float[] sorted=Arrays.copyOf(sectorSamples[sector],storedSamples[sector]);Arrays.sort(sorted);
            sectors[sector]=sorted[storedSamples[sector]/2];lastSamples[sector]=measuredAtNanos>0?measuredAtNanos:now;
            postInvalidateOnAnimation();
        }
        void recordDistanceSignal(int value){
            distanceSignals[distanceSignalWrite]=value;distanceSignalWrite=(distanceSignalWrite+1)%distanceSignals.length;
            distanceSignalCount=Math.min(distanceSignals.length,distanceSignalCount+1);
            float[] sorted=Arrays.copyOf(distanceSignals,distanceSignalCount);Arrays.sort(sorted);float median=sorted[distanceSignalCount/2];
            float[] deviations=new float[distanceSignalCount];for(int i=0;i<distanceSignalCount;i++)deviations[i]=Math.abs(sorted[i]-median);
            Arrays.sort(deviations);rssiSpread=Math.max(1f,deviations[distanceSignalCount/2]*1.4826f);
            rssi=Math.round(median);
        }
        void updateRttDistance(float distance,float uncertainty){
            rttDistance=distance;rttUncertainty=Math.max(.15f,uncertainty);rttUpdatedNanos=SystemClock.elapsedRealtimeNanos();postInvalidateOnAnimation();
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float d=getResources().getDisplayMetrics().density;canvas.save();canvas.scale(d,d);float w=getWidth()/d,cx=w/2;
            paint.setColor(card);canvas.drawRoundRect(0,0,w,290,24,24,paint);
            draw(canvas,target,18,30,29,white,true);draw(canvas,type.isEmpty()?"待機中":type+"を追跡中",13,30,51,mint,false);
            String reading=rssi<=-127?"-- dBm":rssi+" dBm  •  "+strength(rssi);draw(canvas,reading,14,30,75,white,true);
            draw(canvas,distanceText(),13,30,99,distanceIsExact()?mint:white,true);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(Color.rgb(49,78,98));canvas.drawCircle(cx,181,59,paint);canvas.drawCircle(cx,181,43,paint);paint.setStyle(Paint.Style.FILL);
            long now=SystemClock.elapsedRealtimeNanos();int seenCount=0,totalSamples=0;
            float scoreSum=0;int scoreCount=0,best=-1;float bestScore=-128;
            for(int i=0;i<SECTOR_COUNT;i++){
                if(isFresh(i,now)){seenCount++;totalSamples+=sampleCounts[i];}
                float score=smoothedScore(i,now);
                if(score>-127){scoreSum+=score;scoreCount++;if(score>bestScore){bestScore=score;best=i;}}
            }
            float bestDirection=best<0?heading:refinedDirection(best,bestScore,now);
            if(best>=0){
                if(!hasDisplayedDirection){displayedDirection=bestDirection;hasDisplayedDirection=true;}
                else displayedDirection=normalize(displayedDirection+shortestTurn(displayedDirection,bestDirection)*.24f);
            }
            float relative=shortestTurn(heading,hasDisplayedDirection?displayedDirection:heading);
            canvas.save();canvas.rotate(relative,cx,181);Path arrow=new Path();arrow.moveTo(cx,126);arrow.lineTo(cx-14,153);arrow.lineTo(cx-6,150);arrow.lineTo(cx-6,222);arrow.lineTo(cx+6,222);arrow.lineTo(cx+6,150);arrow.lineTo(cx+14,153);arrow.close();
            float contrast=scoreCount==0?0:bestScore-scoreSum/scoreCount;
            float confidence=Math.min(1f,seenCount/(SECTOR_COUNT*.65f))*.62f+Math.min(1f,Math.max(0,contrast)/7f)*.38f;
            boolean provisional=best>=0;
            boolean ready=type.equals("Wi-Fi")?seenCount>=3&&totalSamples>=3:seenCount>=5&&totalSamples>=8;
            paint.setColor(ready?mint:(provisional?amber:muted));canvas.drawPath(arrow,paint);canvas.restore();
            String confidenceText=confidence>=.72f?"高":confidence>=.45f?"中":"低";
            String guide;
            if(target.startsWith("対象を"))guide="一覧から探したい電波をタップ";
            else if(tiltDegrees>45)guide="スマホを水平にすると方向測定が安定します";
            else if(!provisional)guide="電波を待っています • ゆっくり回してください";
            else if(!ready)guide="仮方向を反映中 • "+seenCount+"/"+SECTOR_COUNT+"方向";
            else guide="方向精度 "+confidenceText+" • 矢印へ近づいて再測定";
            draw(canvas,guide,13,30,267,ready?mint:(provisional?amber:muted),true);canvas.restore();
        }
        boolean distanceIsExact(){return !Float.isNaN(rttDistance)&&SystemClock.elapsedRealtimeNanos()-rttUpdatedNanos<12_000_000_000L;}
        String distanceText(){
            if(type.isEmpty())return "距離 --";
            if(distanceIsExact())return "距離 "+formatMeters(rttDistance)+" ±"+formatMeters(rttUncertainty)+" • RTT実測";
            if(rssi<=-127)return rttCapable?"距離をRTTで測定中…":"推定距離を計算中…";
            float[] range=estimateDistance();String prefix=calibrated?"校正済":"推定";
            return prefix+" 約"+formatMeters(range[0])+" • 目安 "+formatMeters(range[1])+"〜"+formatMeters(range[2]);
        }
        float[] estimateDistance(){
            float centerExponent=type.equals("Bluetooth")?2.45f:2.75f;
            float delta=referenceRssi-rssi,center=distanceFor(delta,centerExponent);
            float modelError=calibrated?2.5f:(type.equals("Bluetooth")&&txPower!=RadioItem.NO_TX_POWER?5f:7f);
            float uncertainty=modelError+Math.min(8f,rssiSpread*1.25f);
            float low=distanceFor(delta-uncertainty,calibrated?3.2f:3.7f);
            float high=distanceFor(delta+uncertainty,calibrated?2.1f:1.9f);
            center=clampDistance(center);low=clampDistance(low);high=clampDistance(high);
            if(low>high){float swap=low;low=high;high=swap;}return new float[]{center,low,high};
        }
        static float distanceFor(float loss,float exponent){return (float)Math.pow(10,loss/(10f*exponent));}
        static float clampDistance(float value){return Math.max(.2f,Math.min(100f,value));}
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
