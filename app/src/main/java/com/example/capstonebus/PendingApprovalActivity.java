package com.example.capstonebus;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class PendingApprovalActivity extends AppCompatActivity {

    private FirebaseAuth auth;
    private GoogleSignInClient googleClient;

    private TextView tvEmail, tvStatus;
    private ProgressBar progress;
    private MaterialButton btnLogout;

    private DatabaseReference busAccessRef;
    private DatabaseReference reqRef;

    private ValueEventListener busAccessListener;

    private final AtomicBoolean navigated = new AtomicBoolean(false);

    private String busNumber;
    private String emailKey;
    private String email;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pending_approval);

        tvEmail = findViewById(R.id.tvEmail);
        tvStatus = findViewById(R.id.tvStatus);
        progress = findViewById(R.id.progress);
        btnLogout = findViewById(R.id.btnLogout);

        auth = FirebaseAuth.getInstance();

        googleClient = GoogleSignIn.getClient(this,
                new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestIdToken(getString(R.string.default_web_client_id))
                        .requestEmail()
                        .build());

        FirebaseUser user = auth.getCurrentUser();
        if (user == null || user.getEmail() == null) {
            goLogin();
            return;
        }

        email = user.getEmail();
        emailKey = normEmail(email);
        tvEmail.setText(email);

        busNumber = getIntent().getStringExtra("busNumber");
        if (busNumber == null || busNumber.isEmpty()) {
            tvStatus.setText("No bus selected.");
            progress.setVisibility(View.GONE);
            return;
        }

        tvStatus.setText("Checking access...");
        progress.setVisibility(View.VISIBLE);

        btnLogout.setOnClickListener(v -> logout());

        busAccessRef = FirebaseDatabase.getInstance()
                .getReference("bus_access")
                .child(busNumber)
                .child(emailKey);

        reqRef = FirebaseDatabase.getInstance()
                .getReference("access_requests");
    }

    @Override
    protected void onStart() {
        super.onStart();
        navigated.set(false);

        busAccessListener = busAccessRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {

                Boolean authorized = snapshot.child("authorized").getValue(Boolean.class);

                if (authorized != null && authorized) {
                    goLiveTracking();
                } else {
                    sendRequestIfNeeded();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvStatus.setText("Error checking access: " + error.getMessage());
                progress.setVisibility(View.GONE);
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (busAccessRef != null && busAccessListener != null) {
            busAccessRef.removeEventListener(busAccessListener);
        }
    }

    /* ===================== REQUEST LOGIC ===================== */

    private void sendRequestIfNeeded() {
        Query q = reqRef.orderByChild("email_bus")
                .equalTo(emailKey + "_" + busNumber);

        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {

                if (snapshot.exists()) {
                    for (DataSnapshot snap : snapshot.getChildren()) {
                        String status = snap.child("status").getValue(String.class);

                        if ("pending".equals(status)) {
                            tvStatus.setText("Request already sent. Waiting for approval...");
                            progress.setVisibility(View.GONE);
                            return;
                        }

                        if ("rejected".equals(status)) {
                            snap.getRef().removeValue()
                                    .addOnSuccessListener(aVoid -> createNewRequest())
                                    .addOnFailureListener(e -> {
                                        tvStatus.setText("Failed to retry request.");
                                        progress.setVisibility(View.GONE);
                                    });
                            return;
                        }
                    }
                }

                createNewRequest();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvStatus.setText("Error checking request: " + error.getMessage());
                progress.setVisibility(View.GONE);
            }
        });
    }

    private void createNewRequest() {
        String reqId = reqRef.push().getKey();
        if (reqId == null) {
            progress.setVisibility(View.GONE);
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("email", email);
        data.put("emailKey", emailKey);
        data.put("busNumber", busNumber);
        data.put("email_bus", emailKey + "_" + busNumber);
        data.put("requestedAt", System.currentTimeMillis());
        data.put("status", "pending");
        data.put("uid", reqId);

        reqRef.child(reqId).setValue(data)
                .addOnSuccessListener(aVoid -> {
                    tvStatus.setText("Request sent. Waiting for admin approval...");
                    progress.setVisibility(View.GONE);
                })
                .addOnFailureListener(e -> {
                    tvStatus.setText("Failed to send request: " + e.getMessage());
                    progress.setVisibility(View.GONE);
                });
    }

    /* ===================== NAVIGATION ===================== */

    private void goLiveTracking() {
        if (!navigated.compareAndSet(false, true)) return;

        Intent intent = new Intent(this, RouteMapActivityPrivate.class);
        intent.putExtra("busNumber", busNumber);
        intent.putExtra("email", email);
        startActivity(intent);
        finish();
    }

    private void logout() {
        auth.signOut();
        googleClient.signOut().addOnCompleteListener(task -> goLogin());
    }

    private void goLogin() {
        startActivity(new Intent(this, GoogleSignInActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    /* ===================== UTIL ===================== */

    private String normEmail(String e) {
        return (e == null ? "" : e.trim().toLowerCase())
                .replaceAll("[.#$\\[\\]]", "_");
    }
}
