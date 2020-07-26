package nl.cl.gram.outernet;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.Result;

import java.util.logging.Logger;

import me.dm7.barcodescanner.zxing.ZXingScannerView;

public class QrReaderActivity extends AppCompatActivity implements ZXingScannerView.ResultHandler {
    private static final Logger logger = Logger.getLogger("outernet.qrr");
    private ZXingScannerView mScannerView;
    public static final String EXTRA_QR_CODE = "nl.co.gram.outernet.ExtraQrCode";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        // Programmatically initialize the scanner view
        mScannerView = new ZXingScannerView(this);
        // Set the scanner view as the content view
        setContentView(mScannerView);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Register ourselves as a handler for scan results.
        mScannerView.setResultHandler(this);
        // Start camera on resume
        mScannerView.startCamera();
    }

    @Override
    public void onPause() {
        super.onPause();
        // Stop camera on pause
        mScannerView.stopCamera();
    }

    @Override
    public void handleResult(Result rawResult) {
        // Do something with the result here
        // Prints scan results
        logger.info("result: " + rawResult.getText());
        // Prints the scan format (qrcode, pdf417 etc.)
        logger.info("format: " + rawResult.getBarcodeFormat().toString());
        //If you would like to resume scanning, call this method below:
        //mScannerView.resumeCameraPreview(this);
        Intent intent = new Intent();
        intent.putExtra(EXTRA_QR_CODE, rawResult.getText());
        setResult(RESULT_OK, intent);
        finish();
    }
}
