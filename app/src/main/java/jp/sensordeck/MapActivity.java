package jp.sensordeck;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import org.osmdroid.config.Configuration;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

public class MapActivity extends Activity implements LocationListener {
    private MapView map;
    private LocationManager locationManager;
    private Marker marker;
    private GeoPoint lastPoint;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Configuration.getInstance().setUserAgentValue(getPackageName());

        FrameLayout root = new FrameLayout(this);
        map = new MapView(this);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.getController().setZoom(3.0);
        root.addView(map, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(8), dp(8), dp(8), dp(8));
        controls.setBackgroundColor(Color.argb(225, 7, 17, 31));

        Button back = button("戻る");
        back.setOnClickListener(v -> finish());
        Button center = button("現在地");
        center.setOnClickListener(v -> {
            if (lastPoint != null) {
                map.getController().setZoom(16.0);
                map.getController().animateTo(lastPoint);
            }
        });
        Button minus = button("－");
        minus.setOnClickListener(v -> map.getController().zoomOut());
        Button plus = button("＋");
        plus.setOnClickListener(v -> map.getController().zoomIn());

        status = new TextView(this);
        status.setText("GPS受信待ち");
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setPadding(dp(8), 0, dp(6), 0);

        controls.addView(back);
        controls.addView(status, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(center);
        controls.addView(minus);
        controls.addView(plus);

        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlParams.gravity = Gravity.TOP;
        controlParams.setMargins(dp(8), dp(12), dp(8), 0);
        root.addView(controls, controlParams);
        setContentView(root);
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onLocationChanged(Location location) {
        lastPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
        if (marker == null) {
            marker = new Marker(map);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setTitle("現在地");
            map.getOverlays().add(marker);
            map.getController().setZoom(16.0);
            map.getController().setCenter(lastPoint);
        }
        marker.setPosition(lastPoint);
        marker.setSnippet(String.format(Locale.JAPAN,
                "高度 %.0fm / 速度 %.1fkm/h / 精度 ±%.0fm",
                location.getAltitude(), location.getSpeed()*3.6, location.getAccuracy()));
        status.setText(String.format(Locale.JAPAN, "精度 ±%.0fm", location.getAccuracy()));
        map.invalidate();
    }

    @Override protected void onResume() {
        super.onResume();
        map.onResume();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
        } else {
            status.setText("位置情報の許可が必要");
        }
    }

    @Override protected void onPause() {
        super.onPause();
        locationManager.removeUpdates(this);
        map.onPause();
    }
}
