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
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
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

public class NetworkActivity extends AppCompatActivity {
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

    CommService.Binder commServiceBinder = null;
    private ServiceConnection commServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            commServiceBinder = (CommService.Binder) service;
            cabal = commServiceBinder.svc().commCenter().receiver(networkId);
            if (cabal == null) return;
            setTitle(cabal.name());
            ImageView avatar = findViewById(R.id.avatar);
            avatar.setImageBitmap(Util.identicon(cabal.myID()));
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
            List<Payload> payloads = cabal.payloads();
            receiverListAdapter.submitList(payloads);
            int lastIdx = payloads.size()-1;
            if (payloads.size() > 0 && (atBottom || payloads.get(lastIdx).getCleartextBroadcast().getFrom().equals(cabal.myID()))) {
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

        b3.setBackground(new BitmapDrawable(getResources(), Util.identicon(networkId)));

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
                        NetworkActivity.this.finish();
                    }
                }
            }
        };
    }

    private void clickAvatar() {
        ImageView avatar = findViewById(R.id.avatar);
        int w = getWindow().getDecorView().getWidth();
        int h = getWindow().getDecorView().getHeight();
        int min = w < h ? w : h;
        float topLeftX = (w - min) / 2F;
        float topLeftY = (h - min) / 2F;
        float currX = avatar.getX();
        float currY = avatar.getY();
        float currW = avatar.getWidth();
        float currH = avatar.getHeight();
        AnimationSet as = new AnimationSet(true);
        as.addAnimation(new TranslateAnimation(
                TranslateAnimation.RELATIVE_TO_SELF, 0, TranslateAnimation.ABSOLUTE, 0,
                TranslateAnimation.RELATIVE_TO_SELF, 0, TranslateAnimation.ABSOLUTE, 0));
        as.addAnimation(new ScaleAnimation(1, min / currW, 1, min / currH));
        as.setDuration(1_000);
        avatar.startAnimation(as);
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
                        .setFrom(cabal.myID())
                        .setText(out)
                        .build())
                .build();
        cabal.sendPayload(p);
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
            int attempts = 4;
            @Override
            public void run() {
                logger.info("Sending destroy");
                cabal.sendPayload(Payload.newBuilder()
                        .setSelfDestruct(SelfDestruct.newBuilder())
                        .build());
                if (0 < --attempts) {
                    h.postDelayed(this, 15_000);
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
                        Intent i = new Intent(NetworkActivity.this, QrShowerActivity.class);
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
                    Bitmap bmp = Util.identicon(payload.getCleartextBroadcast().getFrom());
                    identicon.setImageBitmap(bmp);
                    MessageContents contents = data.getCleartextBroadcast();
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
                    textView.append(payload.getSelfDestruct().getText());
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
