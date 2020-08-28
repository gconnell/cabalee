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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.protobuf.ByteString;

import java.util.List;
import java.util.logging.Logger;

public class CabalActivity extends AppCompatActivity {
    private static final Logger logger = Logger.getLogger("cabalee.netact");
    private Cabal cabal = null;
    private ByteString networkId = null;
    private EditText editText = null;
    private RecyclerView recyclerView = null;
    private ReceiverListAdapter receiverListAdapter = null;
    private LinearLayoutManager linearLayoutManager = null;
    private LocalBroadcastManager localBroadcastManager = null;
    private IntentFilter intentFilter = null;
    private BroadcastReceiver broadcastReceiver = null;
    private boolean sent = false;

    CommService.Binder commServiceBinder = null;
    private ServiceConnection commServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            commServiceBinder = (CommService.Binder) service;
            cabal = commServiceBinder.svc().commCenter().receiver(networkId);
            if (cabal == null) {
                finish();
                return;
            }
            setTitle(cabal.name());
            ImageView avatar = findViewById(R.id.avatar);
            avatar.setImageBitmap(Util.identicon(Util.IDENTICON_CABAL, cabal.myID().publicKey().identity()));
            refreshList();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            commServiceBinder = null;
        }
    };

    private void refreshList() {
        int size = receiverListAdapter.getItemCount();
        boolean atBottom = size == 0 || linearLayoutManager.findLastCompletelyVisibleItemPosition() == size - 1;
        if (cabal != null) {
            List<Message> messages = cabal.messages();
            receiverListAdapter.submitList(messages);
            int lastIdx = messages.size()-1;
            if (messages.size() > 0 && (atBottom || sent)) {
                sent = false;
                recyclerView.smoothScrollToPosition(lastIdx);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network);
        editText = findViewById(R.id.inputview);
        localBroadcastManager = LocalBroadcastManager.getInstance(this);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setHasFixedSize(true);
        linearLayoutManager = new LinearLayoutManager(this);
        linearLayoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(linearLayoutManager);
        receiverListAdapter = new ReceiverListAdapter();
        recyclerView.setAdapter(receiverListAdapter);

        networkId = ByteString.copyFrom(getIntent().getByteArrayExtra(Intents.EXTRA_NETWORK_ID));
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

        ImageView avatar = findViewById(R.id.avatar);
        avatar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // clickAvatar();
            }
        });

        b3.setBackground(new BitmapDrawable(getResources(), Util.identicon(Util.IDENTICON_CABAL, networkId)));

        intentFilter = new IntentFilter();
        intentFilter.addAction(Intents.PAYLOAD_RECEIVED);
        intentFilter.addAction(Intents.CABAL_DESTROY);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intents.PAYLOAD_RECEIVED.equals(action)) {
                    refreshList();
                } else if (Intents.CABAL_DESTROY.equals(action)) {
                    byte[] id = intent.getByteArrayExtra(Intents.EXTRA_NETWORK_ID);
                    if (networkId.equals(ByteString.copyFrom(id))) {
                        CabalActivity.this.finish();
                    }
                }
            }
        };
    }

    private void sendText() {
        if (cabal == null) return;
        if (commServiceBinder == null) return;
        if (commServiceBinder.svc().commCenter().activeComms().size() == 0) {
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
                        .setText(out)
                        .build())
                .build();
        sent = true;
        cabal.sendPayload(p, cabal.myID());  // TODO: fix
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
            case R.id.addmember:
                if (cabal != null) showQr();
                return true;
            case R.id.editname:
                if (cabal != null) editName();
                return true;
            case R.id.destroy:
                if (cabal != null) destroy();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void destroy() {
        final Handler h = new Handler(getApplicationContext().getMainLooper());
        final Runnable destroy = new Runnable() {
            int attempts = 1;
            @Override
            public void run() {
                logger.info("Sending destroy");
                cabal.sendPayload(Payload.newBuilder()
                        .setSelfDestruct(SelfDestruct.newBuilder())
                        .build(), null);  // TODO: fix
                if (0 < attempts--) {
                    h.postDelayed(this, 59_000);
                }
            }
        };
        new AlertDialog.Builder(this)
                .setMessage(R.string.self_destruct_warning)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                })
                .setPositiveButton(R.string.self_destruct, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        h.post(destroy);
                    }
                })
                .create()
                .show();
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
                        Intent i = new Intent(CabalActivity.this, QrShowerActivity.class);
                        i.setData(Uri.parse(QrShowerActivity.url(cabal.sooperSecret())));
                        i.putExtra(Intents.EXTRA_QR_TITLE, "Cabal Secret");
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
                        cabal.setName(name);
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
        localBroadcastManager.registerReceiver(broadcastReceiver, intentFilter);
        refreshList();
        sendVisibility(true);
        if (commServiceBinder != null && commServiceBinder.svc().commCenter().receiver(networkId) == null) {
            // cabal was destroyed, finish this activity
            finish();
        }
    }

    private void sendVisibility(boolean visible) {
        Intent intent = new Intent(Intents.CABAL_VISIBILITY_CHANGED);
        intent.putExtra(Intents.EXTRA_VISIBILITY, visible);
        intent.putExtra(Intents.EXTRA_NETWORK_ID, networkId.toByteArray());
        localBroadcastManager.sendBroadcast(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        localBroadcastManager.unregisterReceiver(broadcastReceiver);
        sendVisibility(false);
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
        Message message = null;
        public MyViewHolder(FrameLayout fl) {
            super(fl);
            frameLayout = fl;
            textView = fl.findViewById(R.id.textView);
            identicon = fl.findViewById(R.id.identicon);
        }

        void bindTo(Message data) {
            message = data;
            textView.setText("?");
            switch (data.payload.getKindCase()) {
                case CLEARTEXT_BROADCAST: {
                    byte identiconType = message.from.signingType() == Identity.SIGNED ? Util.IDENTICON_VERIFIED_ID : Util.IDENTICON_UNVERIFIED_ID;
                    Bitmap bmp = Util.identicon(identiconType, message.from.identity());
                    identicon.setImageBitmap(bmp);
                    MessageContents contents = message.payload.getCleartextBroadcast();
                    textView.setText(contents.getText());
                    break;
                }
                case SELF_DESTRUCT: {
                    frameLayout.setBackgroundColor(getResources().getColor(R.color.destroyColor));
                    textView.setBackgroundColor(getResources().getColor(R.color.destroyColor));
                    textView.setTextColor(getResources().getColor(R.color.destroyText));
                    Spannable s = new SpannableString(getResources().getString(R.string.self_destruct));
                    s.setSpan(new StyleSpan(Typeface.BOLD), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    textView.setText(s);
                    textView.append(" ");
                    textView.append(getResources().getString(R.string.self_destruct_time));
                    if (!message.payload.getSelfDestruct().getText().isEmpty()) {
                        textView.append("\n");
                        textView.append(message.payload.getSelfDestruct().getText());
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        identicon.setImageDrawable(getDrawable(R.drawable.ic_baseline_whatshot_24));
                    } else {
                        identicon.setImageDrawable(getResources().getDrawable(R.drawable.ic_baseline_whatshot_24));
                    }
                    break;
                }
            }
            /*
            Date d = new Date();
            Spannable s = new SpannableString("\n@ "  + d);
            s.setSpan(new ForegroundColorSpan(0x55000000), 0, s.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            textView.append(s);
            */
        }
    }

    public static final DiffUtil.ItemCallback<Message> DIFF_CALLBACK  = new DiffUtil.ItemCallback<Message>() {
        @Override
        public boolean areItemsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            return oldItem.equals(newItem);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            return oldItem.equals(newItem);
        }
    };

    class ReceiverListAdapter extends ListAdapter<Message, MyViewHolder> {
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
