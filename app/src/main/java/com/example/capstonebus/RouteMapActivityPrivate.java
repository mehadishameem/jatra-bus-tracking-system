package com.example.capstonebus;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RouteMapActivityPrivate extends AppCompatActivity implements OnMapReadyCallback {

    private GoogleMap mMap;
    private Marker busMarker;
    private LatLng lastLocation;

    private DatabaseReference locationRef;
    private DatabaseReference busAccessRef;

    private ImageButton backBtn, refreshBtn, navBtn;
    private LinearLayout loadingOverlay;
    private TextView routeTitle, speedText, lastUpdateText, activityStatusText;

    private View statusDot;

    private String busNumber;
    private String busId;
    private String status;
    private String email;
    private String emailKey;

    // Listener references for proper cleanup
    private ValueEventListener locationListener;
    private ValueEventListener accessListener;
    private boolean hasCheckedAccess = false;

    // SharedPreferences for saving last viewed bus
    private static final String PREFS_NAME = "BusTrackingPrefs";
    private static final String KEY_LAST_BUS = "lastBusNumber";
    private static final String KEY_LAST_EMAIL = "lastEmail";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.route_map_private);

        // Get data from intent
        busNumber = getIntent().getStringExtra("busNumber");
        busId = getIntent().getStringExtra("busId");
        status = getIntent().getStringExtra("status");

        // Default values if not provided
        if (busNumber == null) busNumber = "Unknown";
        if (busId == null) busId = "N/A";
        if (status == null) status = "inactive";

        // ✅ FIXED: Optional user check - doesn't kick out if auth is slow
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null && user.getEmail() != null) {
            email = user.getEmail();
            emailKey = normEmail(email);

            // Save this bus as the last viewed bus
            saveLastViewedBus(busNumber, email);

            // Setup access monitoring reference
            busAccessRef = FirebaseDatabase.getInstance()
                    .getReference("bus_access")
                    .child(busNumber)
                    .child(emailKey);
        }

        // Initialize UI components
        backBtn = findViewById(R.id.backButton);
        refreshBtn = findViewById(R.id.refreshButton);
        navBtn = findViewById(R.id.navigationButton);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        routeTitle = findViewById(R.id.routeTitle);
        speedText = findViewById(R.id.speedText);
        lastUpdateText = findViewById(R.id.lastUpdateText);
        activityStatusText = findViewById(R.id.activityStatusText);
        statusDot = findViewById(R.id.statusDot);

        // Set title with bus number
        routeTitle.setText(busNumber + " • Live Tracking");

        // Back button - clears saved bus and goes to bus list
        backBtn.setOnClickListener(v -> {
            clearLastViewedBus();
            goToBusList();
        });

        // Refresh camera to current bus location
        refreshBtn.setOnClickListener(v -> {
            if (lastLocation != null && mMap != null) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(lastLocation, 16f));
            }
        });

        // Navigation button - opens Google Maps navigation
        navBtn.setOnClickListener(v -> {
            if (lastLocation != null) {
                Uri gmmIntentUri = Uri.parse("google.navigation:q=" +
                        lastLocation.latitude + "," + lastLocation.longitude);
                Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
                mapIntent.setPackage("com.google.android.apps.maps");

                if (mapIntent.resolveActivity(getPackageManager()) != null) {
                    startActivity(mapIntent);
                } else {
                    Toast.makeText(this, "Google Maps not installed", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Load Map
        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.mapPrivate);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mMap.getUiSettings().setZoomControlsEnabled(true);

        // Setup location reference
        locationRef = FirebaseDatabase.getInstance()
                .getReference("driver_location/private_bus/" + busNumber + "/locations");

        // Start location tracking
        startRealtimeUpdates();

        // Start access monitoring (only if we have user info)
        if (busAccessRef != null && email != null) {
            startAccessMonitoring();
        }
    }

    /**
     * ✅ OPTION 3: Smart Access Check
     * - Checks access on first load
     * - If revoked during viewing, gives 5-second grace period
     */
    private void startAccessMonitoring() {
        accessListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean authorized = snapshot.child("authorized").getValue(Boolean.class);

                // First check - when activity loads
                if (!hasCheckedAccess) {
                    hasCheckedAccess = true;

                    if (authorized == null || !authorized) {
                        // Access was already revoked before they opened
                        Toast.makeText(RouteMapActivityPrivate.this,
                                "You don't have access to this bus",
                                Toast.LENGTH_LONG).show();
                        clearLastViewedBus();
                        goToBusList();
                        finish();
                    }
                    return;
                }

                // Subsequent checks - while they're watching
                if (authorized != null && !authorized) {
                    // Access was revoked WHILE they're watching
                    Toast.makeText(RouteMapActivityPrivate.this,
                            "Access revoked. Redirecting to bus list...",
                            Toast.LENGTH_LONG).show();

                    clearLastViewedBus();

                    // Give them 5 seconds to see what happened
                    new Handler().postDelayed(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            goToBusList();
                            finish();
                        }
                    }, 5000);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Handle error silently or log it
            }
        };

        busAccessRef.addValueEventListener(accessListener);
    }

    /**
     * Start real-time location updates from Firebase
     */
    private void startRealtimeUpdates() {
        locationListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {

                if (!snapshot.hasChildren()) return;

                for (DataSnapshot snap : snapshot.getChildren()) {
                    // Use Businfo model to get all data
                    Businfo businfo = snap.getValue(Businfo.class);

                    if (businfo == null) continue;

                    Double lat = businfo.getLat();
                    Double lon = businfo.getLon();
                    float speed = businfo.getSpeed();
                    long timestamp = businfo.timestamp;

                    if (lat == null || lon == null) continue;

                    LatLng newPos = new LatLng(lat, lon);
                    updateBusMarker(newPos);

                    // Calculate time difference
                    long currentTime = System.currentTimeMillis();
                    long diffMillis = currentTime - timestamp;
                    long diffMinutes = diffMillis / 60000;

                    // Update speed and activity status based on time difference
                    updateSpeedAndStatus(speed, diffMinutes);

                    // Update last update time
                    updateLastUpdateTime(timestamp);

                    loadingOverlay.setVisibility(View.GONE);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(RouteMapActivityPrivate.this,
                        "Error loading location", Toast.LENGTH_SHORT).show();
            }
        };

        locationRef.limitToLast(1).addValueEventListener(locationListener);
    }

    /**
     * Update speed display and activity status based on speed and last update time
     */
    private void updateSpeedAndStatus(float speed, long minutesAgo) {
        // Convert m/s to km/h
        int speedKmh = Math.round(speed * 3.6f);

        // Determine activity status based on time and speed
        if (minutesAgo > 10) {
            // No update for more than 10 minutes - Bus is inactive
            speedText.setText("-- km/h");
            activityStatusText.setText("Inactive");
            activityStatusText.setTextColor(0xFFF44336); // Red
            statusDot.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFFF44336)
            );
        } else if (speedKmh < 1) {
            // Speed is very low - Bus is stopped/parked
            speedText.setText(speedKmh + " km/h");
            activityStatusText.setText("Stopped");
            activityStatusText.setTextColor(0xFFFF9800); // Orange
            statusDot.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFFFF9800)
            );
        } else {
            // Bus is moving
            speedText.setText(speedKmh + " km/h");
            activityStatusText.setText("Moving");
            activityStatusText.setTextColor(0xFF4CAF50); // Green
            statusDot.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF4CAF50)
            );
        }
    }

    /**
     * Update the "last updated" timestamp display
     */
    private void updateLastUpdateTime(long timestamp) {
        long currentTime = System.currentTimeMillis();
        long diffMillis = currentTime - timestamp;

        String timeAgo;

        if (diffMillis < 1000) {
            timeAgo = "Just now";
        } else if (diffMillis < 60000) {
            // Less than 1 minute
            long seconds = diffMillis / 1000;
            timeAgo = seconds + "s ago";
        } else if (diffMillis < 3600000) {
            // Less than 1 hour
            long minutes = diffMillis / 60000;
            timeAgo = minutes + "m ago";
        } else if (diffMillis < 86400000) {
            // Less than 24 hours
            long hours = diffMillis / 3600000;
            timeAgo = hours + "h ago";
        } else {
            // Show actual time
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault());
            timeAgo = sdf.format(new Date(timestamp));
        }

        lastUpdateText.setText("Updated: " + timeAgo);
    }

    /**
     * Update bus marker position on map
     */
    private void updateBusMarker(LatLng newPos) {

        if (busMarker == null) {
            // Initial marker
            busMarker = mMap.addMarker(new MarkerOptions()
                    .position(newPos)
                    .title(busNumber)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
            );

            CameraPosition cameraPosition = new CameraPosition.Builder()
                    .target(newPos)
                    .zoom(16f)
                    .build();

            mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));

        } else {
            // Smooth move
            animateMarker(busMarker, newPos);
            mMap.animateCamera(CameraUpdateFactory.newLatLng(newPos));
        }

        lastLocation = newPos;
    }

    /**
     * Smoothly animate marker from old position to new position
     */
    private void animateMarker(Marker marker, LatLng newPos) {

        LatLng startPos = marker.getPosition();
        long duration = 600;
        long startTime = System.currentTimeMillis();

        Handler handler = new Handler();

        handler.post(new Runnable() {
            @Override
            public void run() {

                float elapsed = (float) (System.currentTimeMillis() - startTime) / duration;
                if (elapsed > 1) elapsed = 1;

                double lat = startPos.latitude + ((newPos.latitude - startPos.latitude) * elapsed);
                double lon = startPos.longitude + ((newPos.longitude - startPos.longitude) * elapsed);

                marker.setPosition(new LatLng(lat, lon));

                if (elapsed < 1) {
                    handler.postDelayed(this, 16);
                }
            }
        });
    }

    // ==================== LIFECYCLE MANAGEMENT ====================

    /**
     * ✅ FIXED: Remove listeners when activity is not visible
     * Prevents memory leaks and unnecessary Firebase callbacks
     */
    @Override
    protected void onStop() {
        super.onStop();
        removeListeners();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        removeListeners();
    }

    /**
     * Remove all Firebase listeners safely
     */
    private void removeListeners() {
        if (locationRef != null && locationListener != null) {
            locationRef.removeEventListener(locationListener);
            locationListener = null;
        }
        if (busAccessRef != null && accessListener != null) {
            busAccessRef.removeEventListener(accessListener);
            accessListener = null;
        }
    }

    // ==================== SHARED PREFERENCES ====================

    /**
     * Save the last viewed bus to SharedPreferences
     */
    private void saveLastViewedBus(String busNumber, String email) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_LAST_BUS, busNumber)
                .putString(KEY_LAST_EMAIL, email)
                .apply();
    }

    /**
     * Clear the saved bus from SharedPreferences
     */
    private void clearLastViewedBus() {
        clearLastViewedBus(this);
    }

    /**
     * Static method to clear last viewed bus from any context
     */
    public static void clearLastViewedBus(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .remove(KEY_LAST_BUS)
                .remove(KEY_LAST_EMAIL)
                .apply();
    }

    /**
     * Get the last viewed bus number
     */
    public static String getLastViewedBus(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(KEY_LAST_BUS, null);
    }

    /**
     * Get the email of user who last viewed a bus
     */
    public static String getLastViewedEmail(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(KEY_LAST_EMAIL, null);
    }

    // ==================== NAVIGATION ====================

    /**
     * Navigate to bus list activity
     */
    private void goToBusList() {
        Intent intent = new Intent(this, ShowAllPrivateBus.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    /**
     * Navigate to login activity
     */
    private void goLogin() {
        clearLastViewedBus();
        Intent intent = new Intent(this, GoogleSignInActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // ==================== UTILITIES ====================

    /**
     * Normalize email for use as Firebase key
     * (Firebase keys can't contain . # $ [ ])
     */
    private String normEmail(String e) {
        return (e == null ? "" : e.trim().toLowerCase())
                .replaceAll("[.#$\\[\\]]", "_");
    }

    /**
     * Handle system back button press
     */
    @Override
    public void onBackPressed() {
        clearLastViewedBus();
        super.onBackPressed();
    }
}