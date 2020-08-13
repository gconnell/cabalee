package nl.co.gram.cabalee;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Logger;

public class ServerPort extends Thread {
    private static final Logger logger = Logger.getLogger("cabalee.serverport");
    private static final int PORT = 22225;
    private final CommCenter commCenter;
    private ServerSocket serverSocket = null;

    public ServerPort(CommCenter commCenter) {
        super();
        this.commCenter = commCenter;
    }

    public void run() {
        logger.severe("Starting service on port " + PORT);
        try {
            synchronized (this) {
                serverSocket = new ServerSocket(PORT);
            }
            while (true) {
                Socket socket;
                try {
                    logger.info("accepting network connection");
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    logger.severe("failed to accept connection, quitting server thread: " + e.getMessage());
                    return;
                }
                try {
                    String name = "serverport:" + socket.toString();
                    logger.severe("accepted network socket from client: " + name);
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
        } catch (IOException e) {
            logger.severe("failed to start server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public synchronized void close() {
        if (serverSocket != null) {
            try {
                logger.info("closing server socket");
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
