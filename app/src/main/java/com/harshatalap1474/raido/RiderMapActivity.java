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

// Google Location Services
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.OnSuccessListener;

// OSM Imports
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.views.overlay.Polyline;

// Firebase
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class RiderMapActivity extends AppCompatActivity {

    private MapView map = null;
    private Button mRequestBtn;
    private TextView mDriverInfo;

    // Accurate Location
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private GeoPoint myLocation;
    private Marker myLocationMarker;

    // Driver tracking
    private Marker driverMarker;
    private Polyline routeLine;
    private String currentDriverId = "";

    // Firebase
    private DatabaseReference databaseRef;
    private String userId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_rider_map);

        mRequestBtn = findViewById(R.id.request_ride_btn);
        mDriverInfo = findViewById(R.id.driver_info_txt);

        map = findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getController().setZoom(18.0);

        if(FirebaseAuth.getInstance().getCurrentUser() != null) {
            userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        } else {
            finish();
            return;
        }
        databaseRef = FirebaseDatabase.getInstance().getReference("ride_requests");

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    updateUIWithLocation(location);
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
        } else {
            Toast.makeText(this, "Permission required", Toast.LENGTH_LONG).show();
        }
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateDistanceMeters(5)
                .build();

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
    }

    private void updateUIWithLocation(Location location) {
        if (location == null) return;

        myLocation = new GeoPoint(location.getLatitude(), location.getLongitude());

        if (myLocationMarker == null) {
            myLocationMarker = new Marker(map);
            myLocationMarker.setTitle("You");
            myLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            map.getOverlays().add(myLocationMarker);
            map.getController().animateTo(myLocation);
        }
        myLocationMarker.setPosition(myLocation);
        map.invalidate();

        // Important: Update Firebase so driver knows where we are
        updateRiderLocationInFirebase(location.getLatitude(), location.getLongitude());

        // Update distance text if driver is assigned
        if (!currentDriverId.isEmpty()) {
            calculateDistanceToDriver(location.getLatitude(), location.getLongitude());
        }
    }

    private void updateRiderLocationInFirebase(double lat, double lng) {
        if (databaseRef != null && userId != null) {
            DatabaseReference riderRef = FirebaseDatabase.getInstance().getReference("ride_requests").child(userId);
            HashMap<String, Object> locationMap = new HashMap<>();
            locationMap.put("riderLat", lat);
            locationMap.put("riderLng", lng);
            riderRef.updateChildren(locationMap);
        }
    }

    private void requestRide() {
        if(myLocation == null) {
            Toast.makeText(this, "Waiting for GPS...", Toast.LENGTH_SHORT).show();
            return;
        }

        mRequestBtn.setText("Searching...");
        mRequestBtn.setEnabled(false);
        mDriverInfo.setText("Looking for drivers...");

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
                if(snapshot.exists()) {
                    String status = snapshot.child("status").getValue(String.class);

                    if ("accepted".equals(status) && snapshot.hasChild("driverId")) {
                        String driverId = snapshot.child("driverId").getValue().toString();

                        // Only start tracking if we haven't already
                        if(!currentDriverId.equals(driverId)){
                            currentDriverId = driverId;
                            fetchDriverInfo(driverId);
                            startTrackingDriver(driverId);
                        }
                    }
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
                            mRequestBtn.setText("Driver Found");
                        }
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    // FIX 1: Tracking logic updated to match Driver App's database structure
    private void startTrackingDriver(String driverId) {
        // Point to the 'location' child
        DatabaseReference driverLocRef = FirebaseDatabase.getInstance()
                .getReference("drivers").child(driverId).child("location");

        driverLocRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if(snapshot.exists()){
                    // Use "lat" and "lng" keys (matching Driver Activity)
                    Double lat = snapshot.child("lat").getValue(Double.class);
                    Double lng = snapshot.child("lng").getValue(Double.class);

                    if (lat != null && lng != null) {
                        GeoPoint driverPoint = new GeoPoint(lat, lng);

                        if(driverMarker == null) {
                            driverMarker = new Marker(map);
                            driverMarker.setTitle("Driver");
                            driverMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
                            // driverMarker.setIcon(getResources().getDrawable(R.drawable.car_icon));
                            map.getOverlays().add(driverMarker);
                        }
                        driverMarker.setPosition(driverPoint);

                        // Draw Route line
                        drawRouteToDriver(driverPoint);

                        // Zoom to fit both
                        if(myLocation != null) {
                            zoomToFitTwoPoints(myLocation, driverPoint);
                        }
                        map.invalidate();
                    }
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    // FIX 2: Distance calculation logic updated to match Database keys
    private void calculateDistanceToDriver(double riderLat, double riderLng) {
        if (currentDriverId.isEmpty()) return;

        // Point to 'location' child
        DatabaseReference driverLocRef = FirebaseDatabase.getInstance()
                .getReference("drivers").child(currentDriverId).child("location");

        driverLocRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    // Use "lat" and "lng" keys
                    Double driverLat = snapshot.child("lat").getValue(Double.class);
                    Double driverLng = snapshot.child("lng").getValue(Double.class);

                    if (driverLat != null && driverLng != null) {
                        float[] results = new float[1];
                        Location.distanceBetween(riderLat, riderLng, driverLat, driverLng, results);
                        float distanceInMeters = results[0];

                        // Append distance to existing text (keeps name/car info)
                        String currentText = mDriverInfo.getText().toString().split("\n")[0]; // Keep top line
                        if(currentText.contains("Driver:")) {
                            mDriverInfo.setText(currentText + "\nDistance: " + String.format("%.0f m", distanceInMeters));
                        }
                    }
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void drawRouteToDriver(GeoPoint driverPoint) {
        if (myLocation == null) return;

        if (routeLine == null) {
            routeLine = new Polyline();
            routeLine.setWidth(10.0f);
            routeLine.setColor(android.graphics.Color.BLUE);
            map.getOverlayManager().add(routeLine);
        }

        List<GeoPoint> points = new ArrayList<>();
        points.add(myLocation);
        points.add(driverPoint);
        routeLine.setPoints(points);
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
        if(fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }
}