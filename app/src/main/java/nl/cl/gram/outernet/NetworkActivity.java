package nl.cl.gram.outernet;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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

import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import nl.co.gram.outernet.MessageContents;
import nl.co.gram.outernet.Payload;

public class NetworkActivity extends AppCompatActivity {
    private static final Logger logger = Logger.getLogger("outernet.netact");
    private ReceivingHandler receivingHandler = null;
    private ByteString networkId = null;
    private EditText editText = null;
    private RecyclerView recyclerView = null;
    private ReceiverListAdapter receiverListAdapter = null;
    private Handler handler = new Handler();
    private LinearLayoutManager linearLayoutManager = null;
    private String from = "???";

    CommService.Binder commServiceBinder = null;
    private ServiceConnection commServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            commServiceBinder = (CommService.Binder) service;
            for (ReceivingHandler rh : commServiceBinder.commCenter().receivers()) {
                if (networkId.equals(rh.id())) {
                    receivingHandler = rh;
                    from = String.format("%06x", commServiceBinder.commCenter().id() & 0xFFFFFF);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            commServiceBinder = null;
        }
    };

    public static final String EXTRA_NETWORK_ID = "nl.co.gram.outernet.ExtraNetworkId";

    private Runnable updatePayloads = new Runnable() {
        @Override
        public void run() {
            refreshList();
            handler.postDelayed(this, 1_000);
        }
    };

    private void refreshList() {
        boolean atBottom = linearLayoutManager.findLastCompletelyVisibleItemPosition() == receiverListAdapter.getItemCount()-1;
        if (receivingHandler != null) {
            List<Payload> payloads = receivingHandler.payloads();
            receiverListAdapter.submitList(payloads);
            if (atBottom && payloads.size() > 0)
                recyclerView.smoothScrollToPosition(payloads.size()-1);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network);
        editText = findViewById(R.id.inputview);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setHasFixedSize(true);
        linearLayoutManager = new LinearLayoutManager(this);
        linearLayoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(linearLayoutManager);
        receiverListAdapter = new ReceiverListAdapter();
        recyclerView.setAdapter(receiverListAdapter);

        networkId = ByteString.copyFrom(getIntent().getByteArrayExtra(EXTRA_NETWORK_ID));
        bindService(new Intent(this, CommService.class), commServiceConnection, Context.BIND_AUTO_CREATE);

        Button b = findViewById(R.id.button1);
        b.setOnClickListener(new View.OnClickListener() {
             @Override
             public void onClick(View v) {
                 if (receivingHandler == null) { return; }
                 Intent i = new Intent(NetworkActivity.this, QrShowerActivity.class);
                 i.putExtra(QrShowerActivity.EXTRA_QR_TO_SHOW, QrShowerActivity.url(receivingHandler.sooperSecret()));
                 startActivity(i);
             }
        });
        Button b2 = findViewById(R.id.button2);
        b2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (receivingHandler != null) {
                    receivingHandler.clearPayloads();
                }
            }
        });
        Button b3 = findViewById(R.id.button3);
        b3.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (receivingHandler == null) return;
                String out = editText.getText().toString();
                if (out.isEmpty()) return;
                editText.getText().clear();
                Payload p = Payload.newBuilder()
                        .setCleartextBroadcast(MessageContents.newBuilder()
                                .setFrom(from)
                                .setText(out)
                                .setTimestamp(Util.now())
                                .build())
                        .build();
                receivingHandler.sendPayload(p);
                refreshList();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(updatePayloads);
    }
    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(updatePayloads);
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
        Payload payload = null;
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

        void bindTo(Payload data) {
            payload = data;
            textView.setText("?");
            switch (data.getKindCase()) {
                case CLEARTEXT_BROADCAST: {
                    MessageContents contents = data.getCleartextBroadcast();
                    Date d = new Date(contents.getTimestamp().getSeconds() * 1000L + (long) (contents.getTimestamp().getNanos() / 1000000));
                    textView.setText("From " + contents.getFrom() + " at " + d + "\n");
                    textView.append(contents.getText());
                    if (contents.hasLocation()) {
                        textView.append("\n@{" + contents.getLocation().getLatitude() + "," + contents.getLocation().getLongitude() + "}");
                    }
                }
            }
        }
    }

    public static final DiffUtil.ItemCallback<Payload> DIFF_CALLBACK  = new DiffUtil.ItemCallback<Payload>() {
        @Override
        public boolean areItemsTheSame(@NonNull Payload oldItem, @NonNull Payload newItem) {
            return oldItem.equals(newItem);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Payload oldItem, @NonNull Payload newItem) {
            return oldItem.equals(newItem);
        }
    };

    class ReceiverListAdapter extends ListAdapter<Payload, MyViewHolder> {
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