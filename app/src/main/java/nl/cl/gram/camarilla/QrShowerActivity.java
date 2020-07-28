package nl.cl.gram.camarilla;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.util.logging.Logger;

public class QrShowerActivity extends AppCompatActivity {
    private static final Logger logger = Logger.getLogger("camarilla.qrs");
    public static final String EXTRA_QR_TO_SHOW = "nl.co.gram.camarilla.ExtraQrToShow";
    public static final String EXTRA_QR_TITLE = "nl.co.gram.camarilla.ExtraQrTitle";

    private String toShow = null;
    private ImageView imgView = null;

    public static final String camarilla_PREFIX = "camarilla://";

    public static String url(byte[] id) {
        return camarilla_PREFIX + Util.toHex(id);
    }
    public static byte[] fromUrl(String url) {
        if (url == null || !url.startsWith(camarilla_PREFIX)) {
            return null;
        }
        return Util.fromHex(url.substring(camarilla_PREFIX.length()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        toShow = getIntent().getStringExtra(EXTRA_QR_TO_SHOW);
        setTitle(getIntent().getStringExtra(EXTRA_QR_TITLE));
        logger.info("showing qr: " + toShow);

        setContentView(R.layout.activity_qrshow);
        imgView = findViewById(R.id.qrImage);
        try {
            Bitmap bmp = encodeAsBitmap(toShow);
            imgView.setImageBitmap(bmp);
        } catch (Exception e) {
            e.printStackTrace();
            finish();
        }
    }

    private static final int WIDTH = 250;

    Bitmap encodeAsBitmap(String str) {
        BitMatrix result;
        Bitmap bitmap=null;
        try
        {
            result = new MultiFormatWriter().encode(str,
                    BarcodeFormat.QR_CODE, WIDTH, WIDTH, null);
            int w = result.getWidth();
            int h = result.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[offset + x] = result.get(x, y) ? 0xFF000000:0xFFFFFFFF;
                }
            }
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, WIDTH, 0, 0, w, h);
        } catch (Exception iae) {
            iae.printStackTrace();
            return null;
        }
        return bitmap;
    }
}
