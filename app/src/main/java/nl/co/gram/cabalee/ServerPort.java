package nl.co.gram.cabalee;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Logger;

public class ServerPort extends Thread {
    private static final Logger logger = Logger.getLogger("cabalee.serverport");
    private final CommCenter commCenter;
    private ServerSocket serverSocket = null;
    public static final int PORT = 22225;

    public ServerPort(CommCenter commCenter) {
        super();
        this.commCenter = commCenter;
        try {
            serverSocket = new ServerSocket(PORT);
        } catch (IOException e) {
            throw new RuntimeException("starting server socket", e);
        }
    }

    public void run() {
        logger.severe("Starting service on port " + PORT);
        while (true) {
            Socket socket;
            try {
                logger.info("accepting network connection");
                socket = serverSocket.accept();
            } catch (IOException e) {
                logger.severe("failed to accept connection, quitting server thread: " + e.getMessage());
                close();
                return;
            }
            try {
                String name = "serverport:" + socket.toString();
                logger.severe("accepted network socket from client: " + name);
                socket.setSoTimeout(CommService.KEEP_ALIVE_MILLIS * 2);
                new SocketComm(commCenter, socket.getInputStream(), socket.getOutputStream(), name);
            } catch (Throwable t) {
                logger.severe("failed to start SocketComm");
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public synchronized void close() {
        try {
            logger.info("closing server socket");
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
