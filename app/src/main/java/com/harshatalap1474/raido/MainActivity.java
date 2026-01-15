package com.harshatalap1474.raido;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast; // Added for error handling

import androidx.annotation.NonNull; // Added
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot; // Added
import com.google.firebase.database.DatabaseError; // Added
import com.google.firebase.database.DatabaseReference; // Added
import com.google.firebase.database.FirebaseDatabase; // Added
import com.google.firebase.database.ValueEventListener; // Added

public class MainActivity extends AppCompatActivity {

    private Button btnRider, btnDriver, btnSignOut;
    private TextView txtWelcome;
    private FirebaseAuth mAuth;
    private DatabaseReference userRef; // Reference to the User in Database

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mAuth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = mAuth.getCurrentUser();

        // 1. SECURITY CHECK
        if (currentUser == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        btnRider = findViewById(R.id.btn_rider_mode);
        btnDriver = findViewById(R.id.btn_driver_mode);
        btnSignOut = findViewById(R.id.btn_sign_out);
        txtWelcome = findViewById(R.id.txt_welcome_user);

        // --- NEW CODE: FETCH NAME FROM DATABASE ---
        String userId = currentUser.getUid();
        userRef = FirebaseDatabase.getInstance().getReference("users").child(userId);

        // Set a temporary text while loading
        txtWelcome.setText("Loading...");

        userRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists() && snapshot.hasChild("name")) {
                    // Get the name we saved during Registration
                    String name = snapshot.child("name").getValue(String.class);
                    txtWelcome.setText("Welcome, " + name);
                } else {
                    // Fallback if no name found
                    txtWelcome.setText("Welcome To RaiDo");
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // If permission denied or internet fails
                txtWelcome.setText("Welcome User");
            }
        });
        // ------------------------------------------

        btnRider.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, RiderMapActivity.class));
        });

        btnDriver.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, DriverMapActivity.class));
        });

        btnSignOut.setOnClickListener(v -> {
            mAuth.signOut();
            startActivity(new Intent(MainActivity.this, LoginActivity.class));
            finish();
        });
    }
}