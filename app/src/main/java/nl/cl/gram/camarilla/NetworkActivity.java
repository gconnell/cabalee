package nl.cl.gram.camarilla;

import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.protobuf.ByteString;

import java.nio.charset.StandardCharsets;
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
    private LinearLayoutManager linearLayoutManager = null;
    private String from = "???";
    private boolean visible = false;

    CommService.Binder commServiceBinder = null;
    private ServiceConnection commServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            commServiceBinder = (CommService.Binder) service;
            for (ReceivingHandler rh : commServiceBinder.svc().commCenter().receivers()) {
                if (networkId.equals(rh.id())) {
                    receivingHandler = rh;
                    setTitle(rh.name());
                    from = String.format("%d", commServiceBinder.svc().commCenter().id());
                    ImageView avatar = findViewById(R.id.avatar);
                    avatar.setImageBitmap(Util.identicon(ByteString.copyFrom(from, StandardCharsets.UTF_8)));
                    setShown();
                    return;
                }
            }
            throw new RuntimeException("receiverhandler id not found");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            commServiceBinder = null;
        }
    };

    private final PayloadReceiver refresher = new PayloadReceiver() {
        @Override
        public void receivePayload(Payload p) {
            refreshList();
        }
    };

    private void refreshList() {
        int size = receiverListAdapter.getItemCount();
        boolean atBottom = size == 0 || linearLayoutManager.findLastCompletelyVisibleItemPosition() == size - 1;
        if (receivingHandler != null) {
            List<Payload> payloads = receivingHandler.channel().payloads();
            receiverListAdapter.submitList(payloads);
            if (atBottom && payloads.size() > 0)
                recyclerView.smoothScrollToPosition(payloads.size() - 1);
        }
    }

    private void setShown() {
        if (receivingHandler == null || !visible) {
            return;
        }
        receivingHandler.addPayloadReceiver(refresher);
        receivingHandler.channel().cabalShown();
        refreshList();
    }
    private void stopShown() {
        if (receivingHandler != null && visible) {
            receivingHandler.removePayloadReciever(refresher);
            receivingHandler.channel().cabalHidden();
        }
    }

    public static final String EXTRA_NETWORK_ID = "nl.co.gram.camarilla.ExtraNetworkId";

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

        ImageButton b3 = (ImageButton) findViewById(R.id.sendchat);
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

        b3.setBackground(new BitmapDrawable(getResources(), Util.identicon(networkId)));
    }

    private void sendText() {
        if (receivingHandler == null) return;
        if (commServiceBinder == null) return;
        // TODO: not this
        if (false && commServiceBinder.svc().commCenter().activeComms().size() == 0) {
            new AlertDialog.Builder(this).setTitle("No active connections")
                    .setMessage(R.string.no_connections)
                    .create().show();
            return;
        }
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
                if (receivingHandler != null) showQr();
                return true;
            case R.id.editname:
                if (receivingHandler != null) editName();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void showQr() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.add_member)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                })
                .setPositiveButton("Show Secret", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent i = new Intent(NetworkActivity.this, QrShowerActivity.class);
                        i.putExtra(QrShowerActivity.EXTRA_QR_TO_SHOW, QrShowerActivity.url(receivingHandler.sooperSecret()));
                        i.putExtra(QrShowerActivity.EXTRA_QR_TITLE, "Cabal Secret");
                        startActivity(i);
                    }
                })
                .create()
                .show();
    }

    private void editName() {
        final EditText input = new EditText(this);
        AlertDialog ad = new AlertDialog.Builder(this)
                .setTitle("Rename Cabal")
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                })
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText().toString();
                        logger.info("New name: " + name);
                        receivingHandler.setName(name);
                        setTitle(name);
                    }
                }).create();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        input.setLayoutParams(lp);
        ad.setView(input);
        ad.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        setShown();
    }
    @Override
    protected void onPause() {
        super.onPause();
        stopShown();
        visible = false;
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
                    Bitmap bmp = Util.identicon(ByteString.copyFrom(payload.getCleartextBroadcast().getFrom(), StandardCharsets.UTF_8));
                    identicon.setImageBitmap(bmp);
                    MessageContents contents = data.getCleartextBroadcast();
                    textView.setText(contents.getText());
                    if (contents.hasLocation()) {
                        textView.append("\n@{" + contents.getLocation().getLatitude() + "," + contents.getLocation().getLongitude() + "}");
                    }
                }
            }
            Date d = new Date();
            Spannable s = new SpannableString("\n@ "  + d);
            s.setSpan(new ForegroundColorSpan(0x55000000), 0, s.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            textView.append(s);
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