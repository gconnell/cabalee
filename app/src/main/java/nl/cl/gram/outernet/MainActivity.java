package nl.cl.gram.outernet;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.logging.Logger;


public class MainActivity extends AppCompatActivity {
    private static final Logger logger = Logger.getLogger("outernet.main");
    private Handler handler = null;
    private RecyclerView recyclerView = null;
    private ReceiverListAdapter receiverListAdapter = null;
    private CommService.Binder commServiceBinder = null;

    private void refresh() {
        if (commServiceBinder == null) {
            receiverListAdapter.submitList(new ArrayList<>());
            return;
        }
        CommCenter commCenter = commServiceBinder.svc().commCenter();
        receiverListAdapter.submitList(commCenter.receivers());
    }

    private ServiceConnection commServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            commServiceBinder = (CommService.Binder) service;
            logger.info("I am " + commServiceBinder.svc().commCenter().id());
            refresh();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            commServiceBinder = null;
        }
    };

    void startCommService() {
        Intent intent = new Intent(this, CommService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new Handler(getMainLooper());
        setContentView(R.layout.activity_main);
        enableLocation();
        startCommService();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setHasFixedSize(true);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        receiverListAdapter = new ReceiverListAdapter();
        recyclerView.setAdapter(receiverListAdapter);

        Button init = findViewById(R.id.button1);
        init.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (commServiceBinder == null) { return; }
                CommCenter commCenter = commServiceBinder.svc().commCenter();
                ReceivingHandler rh = new ReceivingHandler(commCenter);
                commCenter.setReceiver(rh);
                toNetworkPage(rh);
            }
        });
        Button add = findViewById(R.id.button2);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
                        return;
                    }
                }
                startActivityForResult(new Intent(MainActivity.this, QrReaderActivity.class), QR_REQUEST_CODE);
            }
        });
        Intent intent = new Intent(this, CommService.class);
        bindService(intent, commServiceConnection, Context.BIND_AUTO_CREATE);
        Button stop = findViewById(R.id.button3);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (commServiceBinder != null) {
                    commServiceBinder.svc().stopForeground(true);
                    commServiceBinder.svc().stopSelf();
                }
                finish();
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
            String qr = data.getStringExtra(QrReaderActivity.EXTRA_QR_CODE);
            byte[] key = QrShowerActivity.fromUrl(qr);
            if (key == null) {
                logger.severe("invalid key");
                return;
            }
            if (commServiceBinder == null) {
                logger.severe("no comm service");
                return;
            }
            CommCenter commCenter = commServiceBinder.svc().commCenter();
            ReceivingHandler rh = new ReceivingHandler(key, commCenter);
            commCenter.setReceiver(rh);
            toNetworkPage(rh);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logger.info("Stopping");
        unbindService(commServiceConnection);
    }

    private void enableLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            }
        }
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
                    toNetworkPage(receivingHandler);
                }
            });
        }

        void bindTo(ReceivingHandler data) {
            receivingHandler = data;
            String hex = Util.toHex(data.id().toByteArray());
            textView.setText("Session: " + Util.toTitle(data.id().toByteArray()));
        }
    }

    private void toNetworkPage(ReceivingHandler rh) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(MainActivity.this, NetworkActivity.class);
                intent.putExtra(NetworkActivity.EXTRA_NETWORK_ID, rh.id().toByteArray());
                startActivity(intent, ActivityOptions.makeSceneTransitionAnimation(MainActivity.this).toBundle());
            }
        });
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