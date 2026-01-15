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
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
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
    private FusedLocationProviderClient fusedLocationClient;

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

        // NEW CODE: Check authentication
        if(FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentDriverId = FirebaseAuth.getInstance().getCurrentUser().getUid();

            // Optional: Get the driver's email to use as their name
            String email = FirebaseAuth.getInstance().getCurrentUser().getEmail();
            registerDriverInfo(email);
        } else {
            finish(); // Kick them out if not logged in
            return;
        }

        // Check location permission
        checkLocationPermission();

        // START LISTENING FOR RIDES
        listenForPendingRequests();

        acceptBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                acceptRide();
            }
        });
    }

    private void registerDriverInfo(String email) {
        DatabaseReference driverRef = FirebaseDatabase.getInstance().getReference("drivers");
        HashMap<String, Object> map = new HashMap<>();

        // Extract name from email (everything before @)
        String driverName = "Driver";
        if (email != null && email.contains("@")) {
            driverName = email.substring(0, email.indexOf("@"));
            driverName = driverName.substring(0, 1).toUpperCase() + driverName.substring(1);
        }

        map.put("name", driverName);
        map.put("email", email);
        map.put("vehicle", "Honda City - MH12"); // You can make this configurable later
        map.put("status", "available"); // available, busy, offline
        driverRef.child(currentDriverId).setValue(map);
    }

    private void checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        } else {
            showMyLocation();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showMyLocation();
            } else {
                Toast.makeText(this, "Location permission is required to work as a driver", Toast.LENGTH_LONG).show();
                // Optionally finish the activity if permission is denied
                // finish();
            }
        }
    }

    private void showMyLocation() {
        MyLocationNewOverlay locationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), map);
        locationOverlay.enableMyLocation();
        map.getOverlays().add(locationOverlay);

        // Center map on driver initially
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation().addOnSuccessListener(new OnSuccessListener<Location>() {
                @Override
                public void onSuccess(Location location) {
                    if (location != null) {
                        GeoPoint driverLocation = new GeoPoint(location.getLatitude(), location.getLongitude());
                        map.getController().setCenter(driverLocation);

                        // Update driver location in database
                        updateDriverLocation(location.getLatitude(), location.getLongitude());
                    }
                }
            });
        }
    }

    private void updateDriverLocation(double lat, double lng) {
        DatabaseReference driverRef = FirebaseDatabase.getInstance().getReference("drivers").child(currentDriverId);
        HashMap<String, Object> locationMap = new HashMap<>();
        locationMap.put("latitude", lat);
        locationMap.put("longitude", lng);
        locationMap.put("lastUpdate", System.currentTimeMillis());
        driverRef.updateChildren(locationMap);
    }

    // THE CORE LOGIC: Find a rider
    private void listenForPendingRequests() {
        DatabaseReference requestsRef = FirebaseDatabase.getInstance().getReference("ride_requests");

        requestsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                // Loop through all requests
                for (DataSnapshot postSnapshot : snapshot.getChildren()) {

                    // Check if status is "pending"
                    if (postSnapshot.hasChild("status") &&
                            "pending".equals(postSnapshot.child("status").getValue(String.class))) {

                        double lat = postSnapshot.child("riderLat").getValue(Double.class);
                        double lng = postSnapshot.child("riderLng").getValue(Double.class);

                        foundRiderId = postSnapshot.getKey(); // Save the rider's ID

                        // Show the panel and marker
                        showRiderOnMap(lat, lng);
                        return; // Stop looking after finding one (simple prototype logic)
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(DriverMapActivity.this, "Error listening for requests: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showRiderOnMap(double lat, double lng) {
        // 1. Show the Accept Panel
        requestPanel.setVisibility(View.VISIBLE);

        // 2. Add a Marker for the Rider
        GeoPoint riderPoint = new GeoPoint(lat, lng);
        Marker riderMarker = new Marker(map);
        riderMarker.setPosition(riderPoint);
        riderMarker.setTitle("Rider Location");
        riderMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        // riderMarker.setIcon(getResources().getDrawable(R.drawable.person_icon)); // Optional custom icon

        map.getOverlays().add(riderMarker);
        map.invalidate();

        // 3. Zoom out to show both (optional, simple zoom for now)
        map.getController().animateTo(riderPoint);

        // 4. Update driver status to busy
        updateDriverStatus("busy");
    }

    private void updateDriverStatus(String status) {
        DatabaseReference driverRef = FirebaseDatabase.getInstance().getReference("drivers").child(currentDriverId);
        driverRef.child("status").setValue(status);
    }

    private void acceptRide() {
        if(foundRiderId.isEmpty()) return;

        DatabaseReference specificRequest = FirebaseDatabase.getInstance()
                .getReference("ride_requests").child(foundRiderId);

        // UPDATE DATABASE: status="accepted", driverId="my_id"
        specificRequest.child("status").setValue("accepted");
        specificRequest.child("driverId").setValue(currentDriverId);

        Toast.makeText(this, "Ride Accepted! Navigate to Rider.", Toast.LENGTH_LONG).show();
        acceptBtn.setText("Ride in Progress");
        acceptBtn.setEnabled(false);

        // Update driver status
        updateDriverStatus("on_trip");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up: Remove driver from database when they go offline
        if (currentDriverId != null) {
            DatabaseReference driverRef = FirebaseDatabase.getInstance().getReference("drivers").child(currentDriverId);
            driverRef.removeValue();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Optional: Mark as offline when app is in background
        if (currentDriverId != null) {
            DatabaseReference driverRef = FirebaseDatabase.getInstance().getReference("drivers").child(currentDriverId);
            driverRef.child("status").setValue("offline");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Mark as available when app comes back
        if (currentDriverId != null) {
            DatabaseReference driverRef = FirebaseDatabase.getInstance().getReference("drivers").child(currentDriverId);
            driverRef.child("status").setValue("available");
        }
    }
}