package nl.cl.gram.outernet;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Strategy;

import java.util.Date;
import java.util.logging.Logger;

public class NetworkActivity extends AppCompatActivity {
    private static final Logger logger = Logger.getLogger("outernet.main");
    private static final String SERVICE_ID = "nl.co.gram.outernet";
    ConnectionsClient connectionsClient = null;
    private TextView textView = null;
    private CommCenter commCenter = null;
    private Handler handler = new Handler();
    private static final Strategy strategy = Strategy.P2P_CLUSTER;
    private RecyclerView recyclerView = null;
    private ReceiverListAdapter receiverListAdapter = null;

    public static final String EXTRA_NETWORK_ID = "nl.co.gram.outernet.ExtraNetworkId";

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

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setHasFixedSize(true);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        receiverListAdapter = new ReceiverListAdapter();
        recyclerView.setAdapter(receiverListAdapter);

        Button init = findViewById(R.id.button1);
        init.setText("New Network");
        init.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ReceivingHandler rh = new ReceivingHandler(commCenter);
                commCenter.setReceiver(rh);
                Toast.makeText(NetworkActivity.this, "New: " + Util.toHex(rh.id().toByteArray()), Toast.LENGTH_LONG).show();
                Intent i = new Intent(NetworkActivity.this, QrShowerActivity.class);
                i.putExtra(QrShowerActivity.EXTRA_QR_TO_SHOW, QrShowerActivity.url(rh.sooperSecret()));
                startActivity(i);
            }
        });
        Button add = findViewById(R.id.button2);
        add.setText("Connect to Network");
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (ContextCompat.checkSelfPermission(NetworkActivity.this, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
                        return;
                    }
                }
                startActivityForResult(new Intent(NetworkActivity.this, QrReaderActivity.class), QR_REQUEST_CODE);
            }
        });
    }

    private static final int QR_REQUEST_CODE = 1;

    @Override
    protected void onActivityResult (int requestCode,
                                     int resultCode,
                                     Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        logger.info("Request: " + requestCode + ", result: " + (resultCode == RESULT_OK) + " intent: " + data);
        if (requestCode == QR_REQUEST_CODE && resultCode == RESULT_OK) {
            logger.severe("got");
            String qr = data.getStringExtra(QrReaderActivity.EXTRA_QR_CODE);
            byte[] key = QrShowerActivity.fromUrl(qr);
            if (key == null) {
                logger.severe("invalid key");
                return;
            }
            ReceivingHandler rh = new ReceivingHandler(key, commCenter);
            commCenter.setReceiver(rh);
            Toast.makeText(this, "Added receiver: " + Util.toHex(rh.id().toByteArray()), Toast.LENGTH_LONG).show();
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
        receiverListAdapter.submitList(commCenter.receivers());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logger.info("Stopping");
        handler.removeCallbacks(doFunStuff);
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        connectionsClient.stopAllEndpoints();
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


    class MyViewHolder extends RecyclerView.ViewHolder {
        // each data item is just a string in this case
        public FrameLayout frameLayout;
        public TextView textView;
        ReceivingHandler receivingHandler = null;
        public MyViewHolder(FrameLayout fl) {
            super(fl);
            frameLayout = fl;
            textView = fl.findViewById(R.id.textView);
            fl.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Toast.makeText(NetworkActivity.this, textView.getText(), Toast.LENGTH_LONG).show();
                }
            });
        }

        void bindTo(ReceivingHandler data) {
            receivingHandler = data;
            String hex = Util.toHex(data.id().toByteArray());
            textView.setText(hex.substring(0, 6) + "...." + hex.substring(hex.length()-6));
        }
    }

    public static final DiffUtil.ItemCallback<ReceivingHandler> DIFF_CALLBACK  = new DiffUtil.ItemCallback<ReceivingHandler>() {
        @Override
        public boolean areItemsTheSame(@NonNull ReceivingHandler oldItem, @NonNull ReceivingHandler newItem) {
            return oldItem.equals(newItem);
        }

        @Override
        public boolean areContentsTheSame(@NonNull ReceivingHandler oldItem, @NonNull ReceivingHandler newItem) {
            return oldItem.id().equals(newItem.id());
        }
    };

    class ReceiverListAdapter extends ListAdapter<ReceivingHandler, MyViewHolder> {
        public ReceiverListAdapter() {
            super(DIFF_CALLBACK);
        }

        @NonNull
        @Override
        public MyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout v = (FrameLayout) LayoutInflater.from(parent.getContext()).inflate(R.layout.list_element, parent, false);
            MyViewHolder vh = new MyViewHolder(v);
            return vh;
        }

        @Override
        public void onBindViewHolder(MyViewHolder holder, int position) {
            holder.bindTo(getItem(position));
        }
    }
}