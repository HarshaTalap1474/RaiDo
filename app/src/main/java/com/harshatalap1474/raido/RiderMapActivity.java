package com.harshatalap1474.raido;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

// Google Location Services (For High Accuracy)
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

// OSM Imports
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

// Firebase
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;

public class RiderMapActivity extends AppCompatActivity {

    private MapView map = null;
    private Button mRequestBtn;
    private TextView mDriverInfo;

    // Accurate Location
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private GeoPoint myLocation;
    private Marker myLocationMarker; // Custom marker for "Blue Dot"

    // Firebase
    private DatabaseReference databaseRef;
    private String userId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. CONFIG: This fixes the "Green Screen" issue alongside Cleartext
        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_rider_map);

        mRequestBtn = findViewById(R.id.request_ride_btn);
        mDriverInfo = findViewById(R.id.driver_info_txt);

        // 2. MAP SETUP
        map = findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getController().setZoom(18.0);

        // 3. AUTH SETUP
        if(FirebaseAuth.getInstance().getCurrentUser() != null) {
            userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        } else {
            // Safety fallback: if somehow they got here without login
            finish(); // Close activity
            return;
        }
        databaseRef = FirebaseDatabase.getInstance().getReference("ride_requests");

        if(FirebaseAuth.getInstance().getCurrentUser() != null) {
            userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        }
        databaseRef = FirebaseDatabase.getInstance().getReference("ride_requests");

        // 4. LOCATION SETUP
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Define how we handle location updates
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    updateMapLocation(location);
                }
            }
        };

        checkLocationPermission();

        mRequestBtn.setOnClickListener(v -> requestRide());
    }

    private void checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        } else {
            startLocationUpdates();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        // "High Accuracy" Request
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000) // Update every 5s
                .setMinUpdateDistanceMeters(5) // Update if moved 5 meters
                .build();

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
    }

    private void updateMapLocation(Location location) {
        // Save location for the ride request
        myLocation = new GeoPoint(location.getLatitude(), location.getLongitude());

        // Update Marker on Map (Our own Blue Dot)
        if (myLocationMarker == null) {
            myLocationMarker = new Marker(map);
            myLocationMarker.setTitle("You");
            myLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            // Default marker is a balloon. You can change icon here if you want.
            map.getOverlays().add(myLocationMarker);

            // Only center the map automatically the FIRST time
            map.getController().animateTo(myLocation);
        }
        myLocationMarker.setPosition(myLocation);
        map.invalidate(); // Refresh view
    }

    private void requestRide() {
        if(myLocation == null) {
            Toast.makeText(this, "Waiting for GPS...", Toast.LENGTH_SHORT).show();
            return;
        }

        mRequestBtn.setText("Searching for Driver...");
        mRequestBtn.setEnabled(false);

        HashMap<String, Object> requestMap = new HashMap<>();
        requestMap.put("riderLat", myLocation.getLatitude());
        requestMap.put("riderLng", myLocation.getLongitude());
        requestMap.put("status", "pending");

        databaseRef.child(userId).setValue(requestMap);
        listenForDriver();
    }

    private void listenForDriver() {
        databaseRef.child(userId).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if(snapshot.exists() && snapshot.hasChild("driverId")) {
                    String driverId = snapshot.child("driverId").getValue().toString();
                    fetchDriverInfo(driverId);
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void fetchDriverInfo(String driverId) {
        FirebaseDatabase.getInstance().getReference("drivers").child(driverId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if(snapshot.exists()){
                            String name = snapshot.child("name").getValue().toString();
                            String vehicle = snapshot.child("vehicle").getValue().toString();

                            mDriverInfo.setText("Driver: " + name + "\n" + vehicle);
                            mRequestBtn.setText("Ride Accepted");
                        }
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        map.onResume();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        map.onPause();
        fusedLocationClient.removeLocationUpdates(locationCallback); // Stop GPS to save battery
    }
}