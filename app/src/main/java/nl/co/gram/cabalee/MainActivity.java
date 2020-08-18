// Copyright 2020 The Cabalī Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package nl.co.gram.cabalee;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.logging.Logger;


public class MainActivity extends AppCompatActivity {
    private static final Logger logger = Logger.getLogger("cabalee.main");
    private static final int QR_CAMERA_REQUEST_CODE = 2;
    private Handler handler = null;
    private RecyclerView recyclerView = null;
    private ReceiverListAdapter receiverListAdapter = null;
    private CommService.Binder commServiceBinder = null;
    private ArrayList<Cabal> cabals = new ArrayList<>();
    private ByteString navigateTo = null;
    private Runnable navigateRunnable = null;
    private LocalBroadcastManager localBroadcastManager = null;
    private BroadcastReceiver broadcastReceiver = null;

    private void refresh() {
        if (commServiceBinder == null) {
            cabals = new ArrayList<>();
        } else {
            cabals = new ArrayList<>(commServiceBinder.svc().commCenter().receivers());
        }
        receiverListAdapter.submitList(cabals);
    }

    private ServiceConnection commServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            commServiceBinder = (CommService.Binder) service;
            logger.info("CommService connected");
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

    private void navigate(Cabal rh) {
        if (navigateRunnable != null) {
            handler.removeCallbacks(navigateRunnable);
            navigateRunnable = null;
        }
        if (rh == null) {
            navigateTo = null;
        } else {
            navigateTo = rh.id();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new Handler(getMainLooper());
        setContentView(R.layout.activity_main);
        enableLocation();
        startCommService();

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setHasFixedSize(true);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        layoutManager.setReverseLayout(true);
        recyclerView.setLayoutManager(layoutManager);
        receiverListAdapter = new ReceiverListAdapter();
        recyclerView.setAdapter(receiverListAdapter);

        Button init = findViewById(R.id.button1);
        init.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addReceivingHandler(null);
            }
        });
        Button add = findViewById(R.id.button2);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navigate(null);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, QR_CAMERA_REQUEST_CODE);
                        return;
                    }
                }
                startActivityForResult(new Intent(MainActivity.this, QrReaderActivity.class), QR_REQUEST_CODE);
            }
        });
        Intent intent = new Intent(this, CommService.class);
        bindService(intent, commServiceConnection, Context.BIND_AUTO_CREATE);
        Button stop = findViewById(R.id.sendchat);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navigate(null);
                if (commServiceBinder != null) {
                    commServiceBinder.svc().stopForeground(true);
                    commServiceBinder.svc().stopSelf();
                }
                finish();
            }
        });

        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (commServiceBinder == null) return;
                if (Intents.CABAL_DESTROY.equals(action)) {
                    logger.info("MainActivity got CABAL_DESTROY");
                    refresh();
                }
            }
        };
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intents.CABAL_DESTROY);
        localBroadcastManager.registerReceiver(broadcastReceiver, intentFilter);

        final Intent startIntent = getIntent();
        if (startIntent != null && startIntent.getDataString() != null && startIntent.getDataString().startsWith(QrShowerActivity.CABALEE_PREFIX)) {
            logger.severe("startIntent action: " + startIntent.getAction());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    handleCabaleeUrl(startIntent);
                }
            }, 1000);
        }
    }

    private static final int QR_REQUEST_CODE = 1;

    @Override
    protected void onActivityResult(int requestCode,
                                    int resultCode,
                                    Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        logger.info("Request: " + requestCode + ", result: " + (resultCode == RESULT_OK));
        if (requestCode == QR_REQUEST_CODE && resultCode == RESULT_OK) {
            handleCabaleeUrl(data);
        }
    }

    private void handleCabaleeUrl(Intent data) {
        String qr = data.getDataString();
        byte[] key = QrShowerActivity.fromUrl(qr);
        if (key == null) {
            logger.severe("invalid key");
            return;
        }
        addReceivingHandler(key);
    }

    private void addReceivingHandler(byte[] key) {
        if (commServiceBinder == null) {
            logger.severe("no comm service");
            return;
        }
        CommCenter commCenter = commServiceBinder.svc().commCenter();
        Cabal rh = commCenter.forKey(key);
        navigate(rh);
        refresh();
        recyclerView.smoothScrollToPosition(cabals.indexOf(rh));
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
        localBroadcastManager.unregisterReceiver(broadcastReceiver);
        unbindService(commServiceConnection);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == QR_CAMERA_REQUEST_CODE) {
            for (int r : results) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            }
            startActivityForResult(new Intent(MainActivity.this, QrReaderActivity.class), QR_REQUEST_CODE);
        }
    }

    private void enableLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                            != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            }
        }
    }

    class MyViewHolder extends RecyclerView.ViewHolder {
        // each data item is just a string in this case
        private FrameLayout frameLayout;
        private TextView textView;
        private ImageView myImage;
        private Cabal cabal = null;
        public MyViewHolder(FrameLayout fl) {
            super(fl);
            frameLayout = fl;
            textView = fl.findViewById(R.id.textView);
            myImage = fl.findViewById(R.id.identicon);
            fl.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toNetworkPage(cabal, myImage);
                }
            });
        }

        void bindTo(Cabal data) {
            cabal = data;
            textView.setText("Cabal: " + data.name());
            myImage.setImageBitmap(Util.identicon(data.id()));
            if (data.id().equals(navigateTo)) {
                if (navigateRunnable != null) {
                    handler.removeCallbacks(navigateRunnable);
                }
                navigateRunnable = new Runnable() {
                    @Override
                    public void run() {
                        navigateTo = null;
                        toNetworkPage(data, myImage);
                    }
                };
                handler.postDelayed(navigateRunnable, 350);
            }
        }
    }

    private void toNetworkPage(Cabal rh, ImageView myImage) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(MainActivity.this, CabalActivity.class);
                intent.putExtra(Intents.EXTRA_NETWORK_ID, rh.id().toByteArray());
                ActivityOptions options = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    if (myImage != null) {
                        options = ActivityOptions.makeSceneTransitionAnimation(MainActivity.this, myImage, "cabal");
                    } else {
                        options = ActivityOptions.makeSceneTransitionAnimation(MainActivity.this);
                    }
                }
                if (options != null) {
                    startActivity(intent, options.toBundle());
                } else {
                    startActivity(intent);
                }
            }
        });
    }

    public static final DiffUtil.ItemCallback<Cabal> DIFF_CALLBACK  = new DiffUtil.ItemCallback<Cabal>() {
        @Override
        public boolean areItemsTheSame(@NonNull Cabal oldItem, @NonNull Cabal newItem) {
            return oldItem.equals(newItem);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Cabal oldItem, @NonNull Cabal newItem) {
            boolean out = oldItem.id().equals(newItem.id())
                    && oldItem.name().equals(newItem.name());
            return out;
        }
    };

    class ReceiverListAdapter extends ListAdapter<Cabal, MyViewHolder> {
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
