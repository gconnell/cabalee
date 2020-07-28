package nl.cl.gram.camarilla;

import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.BounceInterpolator;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.protobuf.ByteString;

import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import nl.co.gram.camarilla.MessageContents;
import nl.co.gram.camarilla.Payload;

public class NetworkActivity extends AppCompatActivity {
    private static final Logger logger = Logger.getLogger("camarilla.netact");
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
            for (ReceivingHandler rh : commServiceBinder.svc().commCenter().receivers()) {
                if (networkId.equals(rh.id())) {
                    receivingHandler = rh;
                    from = String.format("%06x", commServiceBinder.svc().commCenter().id() & 0xFFFFFF);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            commServiceBinder = null;
        }
    };

    public static final String EXTRA_NETWORK_ID = "nl.co.gram.camarilla.ExtraNetworkId";

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
        setTitle(Util.toTitle(networkId.toByteArray()));
        bindService(new Intent(this, CommService.class), commServiceConnection, Context.BIND_AUTO_CREATE);

        ImageButton b3 = (ImageButton) findViewById(R.id.button3);
        b3.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendText();
            }
        });
        editText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                switch (actionId) {
                    case EditorInfo.IME_ACTION_DONE:
                        InputMethodManager imm = (InputMethodManager)v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                        sendText();
                        return true;
                    default:
                        return false;
                }
            }
        });
        handler.post(new Runnable() {
            @Override
            public void run() {
                View v = getWindow().getDecorView();
                AnimationSet s = new AnimationSet(true);
                int min = v.getWidth() < v.getHeight() ? v.getWidth() : v.getHeight();
                s.addAnimation(new TranslateAnimation(
                        0, 0,
                        -min, 0));
                s.addAnimation(new AlphaAnimation(0, 1));
                s.setDuration(750);
                s.setInterpolator(new BounceInterpolator());
                b3.startAnimation(s);
            }
        });
    }

    private void sendText() {
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.network_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.menuadd:
                if (receivingHandler == null) { return true; }
                showQr();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void showQr() {
        new AlertDialog.Builder(this)
                .setMessage("You are about to display a QR code which can be used to access this cabal.  Whoever sees this code will the ability to read all future communications, as well as potentially decrypt past captured communications.  Are you sure?")
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                    }
                })
                .setPositiveButton("Show Code", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent i = new Intent(NetworkActivity.this, QrShowerActivity.class);
                        i.putExtra(QrShowerActivity.EXTRA_QR_TO_SHOW, QrShowerActivity.url(receivingHandler.sooperSecret()));
                        i.putExtra(QrShowerActivity.EXTRA_QR_TITLE, Util.toTitle(networkId.toByteArray()));
                        startActivity(i);
                    }
                })
                .create()
                .show();
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
        public ImageView identicon;
        Payload payload = null;
        public MyViewHolder(FrameLayout fl) {
            super(fl);
            frameLayout = fl;
            textView = fl.findViewById(R.id.textView);
            identicon = fl.findViewById(R.id.identicon);
        }

        void bindTo(Payload data) {
            payload = data;
            textView.setText("?");
            switch (data.getKindCase()) {
                case CLEARTEXT_BROADCAST: {
                    BitmapDrawable draw = new BitmapDrawable(Util.identicon(payload.getCleartextBroadcast().getTextBytes()));
                    draw.setAntiAlias(false);
                    identicon.setImageDrawable(draw);
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