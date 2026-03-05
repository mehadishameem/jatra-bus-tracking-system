package com.example.capstonebus;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.auth.api.signin.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    Button privatebus, showallprivatebus;
    Button btnLogout;

    private FirebaseAuth firebaseAuth;
    private GoogleSignInClient googleSignInClient;

    // ✅ prevents multiple logout clicks / multiple launches
    private final AtomicBoolean isLoggingOut = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.mainRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), systemBars.top, v.getPaddingRight(), systemBars.bottom);
            return insets;
        });

        btnLogout = findViewById(R.id.btnLogout);
        privatebus = findViewById(R.id.privatebus);
        showallprivatebus = findViewById(R.id.showallprivatebus);

        firebaseAuth = FirebaseAuth.getInstance();

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);

        btnLogout.setOnClickListener(v -> logout());

        SharedPreferences sharedPreferences = getSharedPreferences("UserPrefs", MODE_PRIVATE);

        privatebus.setOnClickListener(v -> {
            sharedPreferences.edit().putString("tracking", "privatebus").apply();
            startActivity(new Intent(MainActivity.this, Search_activity.class));
        });

        showallprivatebus.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, ShowAllPrivateBus.class));
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        // ✅ If not logged in, go login once
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Intent i = new Intent(this, GoogleSignInActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        }
    }

    private void logout() {
        if (!isLoggingOut.compareAndSet(false, true)) return; // ✅ only once
        btnLogout.setEnabled(false);

        FirebaseUser user = firebaseAuth.getCurrentUser();
        SharedPreferences prefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);

        // ✅ we stored allowlistKey in GoogleSignInActivity after successful authorization
        String allowlistKey = prefs.getString("allowlistKey", null);

        if (user != null && allowlistKey != null) {
            FirebaseDatabase.getInstance()
                    .getReference("users")
                    .child(allowlistKey)
                    .child("lastLogout")
                    .setValue(ServerValue.TIMESTAMP)
                    .addOnCompleteListener(t -> performSignOut());
        } else {
            performSignOut();
        }
    }

    private void performSignOut() {
        firebaseAuth.signOut();

        googleSignInClient.signOut().addOnCompleteListener(task -> {
            SharedPreferences prefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
            prefs.edit().clear().apply();

            Toast.makeText(MainActivity.this, "Logged out successfully.", Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(MainActivity.this, GoogleSignInActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);

            finish();
        });
    }
}
