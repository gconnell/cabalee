package nl.cl.gram.outernet;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.protobuf.ByteString;

import java.util.logging.Logger;

public class NetworkActivity extends AppCompatActivity {
    private static final Logger logger = Logger.getLogger("outernet.netact");
    private ReceivingHandler receivingHandler = null;
    private ByteString networkId = null;
    private TextView textView = null;
    private RecyclerView recyclerView = null;
    private ReceiverListAdapter receiverListAdapter = null;

    CommService.Binder commServiceBinder = null;
    private ServiceConnection commServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            commServiceBinder = (CommService.Binder) service;
            for (ReceivingHandler rh : commServiceBinder.commCenter().receivers()) {
                if (networkId.equals(rh.id())) {
                    receivingHandler = rh;
                    textView.setText("Full ID: " + Util.toHex(rh.id().toByteArray()));
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            commServiceBinder = null;
        }
    };

    public static final String EXTRA_NETWORK_ID = "nl.co.gram.outernet.ExtraNetworkId";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textView = findViewById(R.id.textview);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setHasFixedSize(true);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        receiverListAdapter = new ReceiverListAdapter();
        recyclerView.setAdapter(receiverListAdapter);

        networkId = ByteString.copyFrom(getIntent().getByteArrayExtra(EXTRA_NETWORK_ID));
        bindService(new Intent(this, CommService.class), commServiceConnection, Context.BIND_AUTO_CREATE);

        Button b = findViewById(R.id.button1);
        b.setText("Show QR");
        b.setOnClickListener(new View.OnClickListener() {
             @Override
             public void onClick(View v) {
                 if (receivingHandler == null) { return; }
                 Intent i = new Intent(NetworkActivity.this, QrShowerActivity.class);
                 i.putExtra(QrShowerActivity.EXTRA_QR_TO_SHOW, QrShowerActivity.url(receivingHandler.sooperSecret()));
                 startActivity(i);
             }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(commServiceConnection);
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