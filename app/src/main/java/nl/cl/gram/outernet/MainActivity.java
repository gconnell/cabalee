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

import java.util.Date;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import java9.util.concurrent.CompletableFuture;
import nl.co.gram.outernet.Hop;
import nl.co.gram.outernet.RouteToServiceResponse;
import nl.co.gram.outernet.Service;

public class MainActivity extends AppCompatActivity {
    private static final Logger logger = Logger.getLogger("outernet.main");
    String uuid = null;
    private static final String SERVICE_ID = "nl.co.gram.outernet";
    ConnectionsClient connectionsClient = null;
    private TextView textView = null;
    private CommCenter commCenter = null;
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        enableLocation();
        byte[] uuidBytes = new byte[6];
        Util.randomBytes(uuidBytes);
        uuid = Util.toHex(uuidBytes);
        connectionsClient = Nearby.getConnectionsClient(this);
        commCenter = new CommCenter(connectionsClient);
        textView = (TextView) findViewById(R.id.textview);
        textView.setText("I am " + commCenter.id() + "\n");
        textView.setMovementMethod(new ScrollingMovementMethod());
        Button b = (Button) findViewById(R.id.button);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commCenter.turnOffServices();
            }
        });
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
            commCenter.fastestRouteTo(Service.INTERNET, new Stream<RouteToServiceResponse>() {
                        @Override
                        public void onNext(RouteToServiceResponse r) {
                            textView.append("route:\n");
                            for (Hop hop : r.getOutboundList()) {
                                textView.append("  " + hop.getId() + "\n");
                            }
                            double latency =
                                    r.getInbound(r.getInboundCount() - 1).getElapsedRealtimeNanos() -
                                            r.getOutbound(0).getElapsedRealtimeNanos();
                            textView.append("latency " + (latency / 1_000_000_000D) + "\n");
                        }

                        @Override
                        public void onComplete() {

                        }

                        @Override
                        public void onError(Throwable t) {
                            textView.append("route request failed: " + t.getMessage() + "\n");
                        }
                    });
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
                new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
        EndpointDiscoveryCallback callback = new EndpointDiscoveryCallback() {
            @Override
            public void onEndpointFound(@NonNull String s, @NonNull DiscoveredEndpointInfo discoveredEndpointInfo) {
                logger.info("onEndpointFound: " + s);
                connectionsClient
                        .requestConnection(uuid, s, commCenter)
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
                new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
        connectionsClient
                .startAdvertising(uuid, SERVICE_ID, commCenter, advertisingOptions)
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