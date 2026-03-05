package com.example.capstonebus;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.*;
import com.google.android.gms.common.api.ApiException;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.*;

public class GoogleSignInActivity extends AppCompatActivity {

    private static final int RC_SIGN_IN = 1001;

    private FirebaseAuth auth;
    private GoogleSignInClient googleClient;

    private MaterialButton btnGoogle;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_google_sign_in);

        btnGoogle = findViewById(R.id.btnGoogle);
        progress = findViewById(R.id.progress);

        auth = FirebaseAuth.getInstance();

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();

        googleClient = GoogleSignIn.getClient(this, gso);

        btnGoogle.setOnClickListener(v -> startGoogleSignIn());

        // Check if already signed in
        FirebaseUser user = auth.getCurrentUser();
        if (user != null && user.getEmail() != null) {
            setLoading(true);
            checkEWUEmail(user.getEmail());
        }
    }

    private void startGoogleSignIn() {
        setLoading(true);
        startActivityForResult(googleClient.getSignInIntent(), RC_SIGN_IN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != RC_SIGN_IN) return;

        try {
            GoogleSignInAccount account =
                    GoogleSignIn.getSignedInAccountFromIntent(data)
                            .getResult(ApiException.class);

            if (account == null) throw new Exception("Null account");

            firebaseAuthWithGoogle(account.getIdToken());

        } catch (Exception e) {
            setLoading(false);
            toast("Google sign-in failed");
        }
    }

    private void firebaseAuthWithGoogle(String token) {
        AuthCredential cred = GoogleAuthProvider.getCredential(token, null);

        auth.signInWithCredential(cred).addOnCompleteListener(task -> {
            setLoading(false);

            if (!task.isSuccessful()) {
                toast("Authentication failed");
                return;
            }

            FirebaseUser user = auth.getCurrentUser();
            if (user == null || user.getEmail() == null) {
                signOutBoth();
                return;
            }

            checkEWUEmail(user.getEmail());
        });
    }

    /**
     * ✅ FIXED: Proper email validation (removed .com vulnerability)
     */
    private void checkEWUEmail(String email) {
        String e = email.trim().toLowerCase();

        // Only allow @ewubd.edu emails
        if (e.endsWith("ewubd.edu")) {
            goMain();
        } else {
            // Not an EWU email → sign out from both Firebase and Google
            signOutBoth(() -> toast("Please use your institutional EWU email (@ewubd.edu)"));
        }
    }

    private void signOutBoth() {
        signOutBoth(null);
    }

    private void signOutBoth(Runnable afterSignOut) {
        auth.signOut();
        googleClient.signOut().addOnCompleteListener(task -> {
            if (afterSignOut != null) afterSignOut.run();
        });
    }

    /**
     * ✅ ADDED: Check if user has a saved bus to resume
     */
    private void goMain() {
        String lastBus = RouteMapActivityPrivate.getLastViewedBus(this);
        String lastEmail = RouteMapActivityPrivate.getLastViewedEmail(this);
        FirebaseUser currentUser = auth.getCurrentUser();

        // Check if saved bus exists and belongs to current user
        if (lastBus != null && currentUser != null &&
                lastEmail != null && lastEmail.equals(currentUser.getEmail())) {

            // User has a saved bus, go directly to tracking
            Intent intent = new Intent(this, RouteMapActivityPrivate.class);
            intent.putExtra("busNumber", lastBus);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        } else {
            // No saved bus, go to bus list
            Intent intent = new Intent(this, ShowAllPrivateBus.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
        finish();
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnGoogle.setEnabled(!loading);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}