package nl.cl.gram.outernet;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Strategy;

import java.util.ArrayList;
import java.util.Date;
import java.util.logging.Logger;

import nl.co.gram.outernet.Hop;

import static nl.cl.gram.outernet.Util.nanosAsSeconds;

public class MainActivity extends AppCompatActivity {
    private static final Logger logger = Logger.getLogger("outernet.main");
    private static final String SERVICE_ID = "nl.co.gram.outernet";
    ConnectionsClient connectionsClient = null;
    private TextView textView = null;
    private CommCenter commCenter = null;
    private Handler handler = new Handler();
    private static final Strategy strategy = Strategy.P2P_CLUSTER;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        enableLocation();
        connectionsClient = Nearby.getConnectionsClient(this);
        commCenter = new CommCenter(connectionsClient);
        textView = (TextView) findViewById(R.id.textview);
        textView.setText("I am " + commCenter.id() + "\n");
        textView.setMovementMethod(new ScrollingMovementMethod());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private Runnable doFunStuff = new Runnable() {
        @Override
        public void run() {
            Date d = new Date();
            textView.append("At " + d + "\n");
            for (Comm c : commCenter.activeComms()) {
                textView.append("  connected to: " + c.remoteID() + " at " + c.remote() + "\n");
            }
            handler.postDelayed(this, 15_000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        logger.info("Starting");
        startAdvertising();
        startDiscovery();
        handler.postDelayed(doFunStuff, 7_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        logger.info("Stopping");
        handler.removeCallbacks(doFunStuff);
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        connectionsClient.stopAllEndpoints();
        finish();
    }

    private void enableLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            }
        }
    }

    private void startDiscovery() {
        DiscoveryOptions discoveryOptions =
                new DiscoveryOptions.Builder().setStrategy(strategy).build();
        EndpointDiscoveryCallback callback = new EndpointDiscoveryCallback() {
            @Override
            public void onEndpointFound(@NonNull String s, @NonNull DiscoveredEndpointInfo discoveredEndpointInfo) {
                logger.info("onEndpointFound: " + s);
                connectionsClient
                        .requestConnection(Long.toString(commCenter.id()), s, commCenter)
                        .addOnSuccessListener(
                                (Void unused) -> {
                                    logger.info("requesting connection to " + s + " succeeded");
                                })
                        .addOnFailureListener(
                                (Exception e) -> {
                                    logger.info("requesting connection to " + s + " failed: " + e.getMessage());
                                });
            }

            @Override
            public void onEndpointLost(@NonNull String s) {
                logger.info("onEndpointLost: " + s);
            }
        };
        connectionsClient
                .startDiscovery(SERVICE_ID, callback, discoveryOptions)
                .addOnSuccessListener(
                        (Void unused) -> {
                            logger.info("Discovering started");
                        })
                .addOnFailureListener(
                        (Exception e) -> {
                            logger.severe("Discovering failed: " + e.getMessage());
                            e.printStackTrace();
                        });
    }

    private void startAdvertising() {
        AdvertisingOptions advertisingOptions =
                new AdvertisingOptions.Builder().setStrategy(strategy).build();
        connectionsClient
                .startAdvertising(Long.toString(commCenter.id()), SERVICE_ID, commCenter, advertisingOptions)
                .addOnSuccessListener(
                        (Void unused) -> {
                            logger.info("Advertizing started");
                        })
                .addOnFailureListener(
                        (Exception e) -> {
                            logger.severe("Advertizing failed: " + e.getMessage());
                            e.printStackTrace();
                        });
    }
}