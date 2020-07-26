package nl.cl.gram.outernet;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.method.ScrollingMovementMethod;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Strategy;
import com.google.crypto.tink.config.TinkConfig;

import java.security.GeneralSecurityException;
import java.util.Date;
import java.util.logging.Logger;


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
        try {
            TinkConfig.register();
        } catch (GeneralSecurityException e) {
            e.printStackTrace();
            throw new RuntimeException("tink failed: " + e.getMessage());
        }
        setContentView(R.layout.activity_main);
        enableLocation();
        connectionsClient = Nearby.getConnectionsClient(this);
        commCenter = new CommCenter(connectionsClient);
        textView = (TextView) findViewById(R.id.textview);
        textView.setText("I am " + commCenter.id() + "\n");
        textView.setMovementMethod(new ScrollingMovementMethod());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Button init = findViewById(R.id.initiate);
        init.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ReceivingHandler rh = new ReceivingHandler();
                commCenter.setReceiver(rh);
                Toast.makeText(MainActivity.this, "New: " + Util.toHex(rh.id().toByteArray()), Toast.LENGTH_LONG).show();
                Intent i = new Intent(MainActivity.this, QrShowerActivity.class);
                i.putExtra(QrShowerActivity.EXTRA_QR_TO_SHOW, QrShowerActivity.url(rh.sooperSecret()));
                startActivity(i);
            }
        });
        Button add = findViewById(R.id.add);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
                    }
                }
                startActivityForResult(new Intent(MainActivity.this, QrReaderActivity.class), QR_REQUEST_CODE);
            }
        });
    }

    private static final int QR_REQUEST_CODE = 11229;

    @Override
    protected void onActivityResult (int requestCode,
                                     int resultCode,
                                     Intent data) {
        logger.info("Request: " + requestCode + ", result: " + (resultCode == RESULT_OK) + " intent: " + data);
        if (requestCode == QR_REQUEST_CODE && resultCode == RESULT_OK) {
            String qr = data.getStringExtra(QrReaderActivity.EXTRA_QR_CODE);
            byte[] key = QrShowerActivity.fromUrl(qr);
            if (key == null) {
                logger.severe("invalid key");
                return;
            }
            ReceivingHandler rh = new ReceivingHandler(key);
            commCenter.setReceiver(rh);
            Toast.makeText(this, "Added receiver: " + Util.toHex(rh.id().toByteArray()), Toast.LENGTH_LONG).show();
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
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