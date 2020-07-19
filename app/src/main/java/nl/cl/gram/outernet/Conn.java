package nl.cl.gram.outernet;

import android.renderscript.ScriptGroup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;

import java9.util.concurrent.CompletableFuture;

public class Conn {
    public final long id;
    public final CompletableFuture<OutputStream> output = new CompletableFuture<>();
    public final CompletableFuture<InputStream> input = new CompletableFuture<>();

    public Conn(long id) {
        this.id = id;
    }

    public long id() { return id; }

    void close() {
        input.complete(null);
        output.complete(null);
        try {
            InputStream is = input.get();
            if (is != null) is.close();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            OutputStream os = output.get();
            if (os != null) os.close();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
