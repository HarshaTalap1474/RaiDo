package com.harshatalap1474.raido;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

// Location & Maps
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback; // FIX 1: Needed for updates
import com.google.android.gms.location.LocationRequest; // FIX 1
import com.google.android.gms.location.LocationResult; // FIX 1
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority; // FIX 1
import com.google.android.gms.tasks.OnSuccessListener;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

// Firebase
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;

public class DriverMapActivity extends AppCompatActivity {

    private MapView map;
    private LinearLayout requestPanel;
    private TextView distanceTxt;
    private Button acceptBtn;

    // Data
    private String currentDriverId;
    private String foundRiderId = "";
    private GeoPoint riderPoint; // FIX 2: Made this global so we can zoom to it

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback; // FIX 1: The Loop Variable
    private MyLocationNewOverlay locationOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // OSM Config
        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_driver_map);

        // UI Init
        map = findViewById(R.id.driver_map);
        requestPanel = findViewById(R.id.request_panel);
        distanceTxt = findViewById(R.id.rider_distance_txt);
        acceptBtn = findViewById(R.id.accept_btn);

        // Map Init
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getController().setZoom(18.0);

        // Location Init
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // FIX 1: DEFINE THE LOCATION LOOP
        // This code runs every 5 seconds to update position
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    // Update Map
                    GeoPoint myPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
                    map.getController().setCenter(myPoint);

                    // Update Database
                    updateDriverLocation(location.getLatitude(), location.getLongitude());

                    // Update Zoom if we have a rider
                    if(riderPoint != null) {
                        zoomToFitTwoPoints(myPoint, riderPoint); // FIX 2: Actually calling the zoom
                        calculateDistanceToRider(location.getLatitude(), location.getLongitude());
                    }
                }
            }
        };

        // Auth Check
        if(FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentDriverId = FirebaseAuth.getInstance().getCurrentUser().getUid();
            String email = FirebaseAuth.getInstance().getCurrentUser().getEmail();
            registerDriverInfo(email);
        } else {
            finish();
            return;
        }

        checkLocationPermission();
        listenForPendingRequests();

        acceptBtn.setOnClickListener(v -> acceptRide());
    }

    private void registerDriverInfo(String email) {
        DatabaseReference driverRef = FirebaseDatabase.getInstance().getReference("drivers");
        HashMap<String, Object> map = new HashMap<>();

        String driverName = "Driver";
        if (email != null && email.contains("@")) {
            driverName = email.substring(0, email.indexOf("@"));
        }

        map.put("name", driverName);
        map.put("email", email);
        map.put("vehicle", "Honda City - MH12");
        map.put("status", "available");
        driverRef.child(currentDriverId).setValue(map);
    }

    private void checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        } else {
            startLocationUpdates(); // FIX 1: Start the loop
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates(); // FIX 1: Start the loop
        }
    }

    // FIX 1: NEW METHOD TO START THE LOOP
    private void startLocationUpdates() {
        // Show Blue Dot
        locationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), map);
        locationOverlay.enableMyLocation();
        map.getOverlays().add(locationOverlay);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        // Request Updates every 4 seconds
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000)
                .setMinUpdateDistanceMeters(5)
                .build();

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
    }

    private void updateDriverLocation(double lat, double lng) {
        // FIX 3: DATABASE PATH AND KEYS
        // Must use .child("location") to keep it clean
        DatabaseReference driverLocRef = FirebaseDatabase.getInstance().getReference("drivers").child(currentDriverId).child("location");

        HashMap<String, Object> locationMap = new HashMap<>();
        locationMap.put("lat", lat); // Must be "lat" to match Rider Code
        locationMap.put("lng", lng); // Must be "lng" to match Rider Code

        driverLocRef.setValue(locationMap);
    }

    private void calculateDistanceToRider(double driverLat, double driverLng) {
        // Simple math using the riderPoint we already have
        if (riderPoint != null) {
            float[] results = new float[1];
            Location.distanceBetween(driverLat, driverLng, riderPoint.getLatitude(), riderPoint.getLongitude(), results);

            float distanceInMeters = results[0];
            if (distanceInMeters < 1000) {
                distanceTxt.setText(String.format("%.0f m away", distanceInMeters));
            } else {
                distanceTxt.setText(String.format("%.1f km away", distanceInMeters / 1000));
            }
        }
    }

    private void listenForPendingRequests() {
        DatabaseReference requestsRef = FirebaseDatabase.getInstance().getReference("ride_requests");
        requestsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot postSnapshot : snapshot.getChildren()) {
                    if (postSnapshot.hasChild("status") &&
                            "pending".equals(postSnapshot.child("status").getValue(String.class))) {

                        double lat = postSnapshot.child("riderLat").getValue(Double.class);
                        double lng = postSnapshot.child("riderLng").getValue(Double.class);
                        foundRiderId = postSnapshot.getKey();

                        showRiderOnMap(lat, lng);
                        return;
                    }
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void showRiderOnMap(double lat, double lng) {
        requestPanel.setVisibility(View.VISIBLE);

        // FIX 2: Set the Global Variable so Zoom works
        riderPoint = new GeoPoint(lat, lng);

        Marker riderMarker = new Marker(map);
        riderMarker.setPosition(riderPoint);
        riderMarker.setTitle("Rider Location");
        riderMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        map.getOverlays().add(riderMarker);
        map.invalidate();

        // Initial Zoom
        map.getController().animateTo(riderPoint);
    }

    private void acceptRide() {
        if(foundRiderId.isEmpty()) return;

        DatabaseReference specificRequest = FirebaseDatabase.getInstance()
                .getReference("ride_requests").child(foundRiderId);

        specificRequest.child("status").setValue("accepted");
        specificRequest.child("driverId").setValue(currentDriverId);

        Toast.makeText(this, "Ride Accepted!", Toast.LENGTH_LONG).show();
        acceptBtn.setText("Ride in Progress");
        acceptBtn.setEnabled(false);

        // Update status
        FirebaseDatabase.getInstance().getReference("drivers").child(currentDriverId).child("status").setValue("on_trip");
    }

    private void zoomToFitTwoPoints(GeoPoint point1, GeoPoint point2) {

        if (point1 == null || point2 == null) return;

        double maxLat = Math.max(point1.getLatitude(), point2.getLatitude());
        double minLat = Math.min(point1.getLatitude(), point2.getLatitude());
        double maxLng = Math.max(point1.getLongitude(), point2.getLongitude());
        double minLng = Math.min(point1.getLongitude(), point2.getLongitude());

        BoundingBox box = new BoundingBox(maxLat + 0.001, maxLng + 0.001, minLat - 0.001, minLng - 0.001);
        map.zoomToBoundingBox(box, true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if(fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback); // Save battery
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }
}