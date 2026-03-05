package com.example.capstonebus;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DURATION = 2000; // 2 seconds

    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen splash
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_splash);

        auth = FirebaseAuth.getInstance();

        ImageView icon = findViewById(R.id.splashIcon);
        // icon is set via XML to jatra.png

        // Delay 2 seconds then check login
        new Handler().postDelayed(this::checkLogin, SPLASH_DURATION);
    }

    private void checkLogin() {
        FirebaseUser user = auth.getCurrentUser();

        if (user != null && user.getEmail() != null &&
                user.getEmail().toLowerCase().endsWith("ewubd.edu")) {
            // Logged in with EWU email
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } else {
            // Not logged in or invalid email → go to Google Sign-In
            startActivity(new Intent(this, GoogleSignInActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        }
        finish();
    }
}
