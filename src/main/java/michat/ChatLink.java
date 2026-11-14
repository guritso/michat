package michat;

import arc.Core;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ChatLink{
    private static final int PORT = 39761;
    private final Set<PrintWriter> clients = ConcurrentHashMap.newKeySet();
    private volatile PrintWriter connection;

    void connect(String address, String name){
        Thread thread = new Thread(() -> {
            try{
                Socket socket = new Socket(address, PORT);
                connection = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                String line;
                while((line = reader.readLine()) != null){
                    String text = line;
                    Core.app.post(() -> Log.info("MiChat: @", text));
                }
            }catch(Exception e){
                Log.err("MiChat connection failed", e);
            }
        }, "michat-client");
        thread.setDaemon(true);
        thread.start();
    }

    void host(String name){
        Thread thread = new Thread(() -> {
            try(ServerSocket server = new ServerSocket(PORT)){
                while(!Thread.currentThread().isInterrupted()){
                    Socket socket = server.accept();
                    Thread client = new Thread(() -> serve(socket), "michat-peer");
                    client.setDaemon(true);
                    client.start();
                }
            }catch(Exception e){
                Log.err("MiChat relay stopped", e);
            }
        }, "michat-relay");
        thread.setDaemon(true);
        thread.start();
        Log.info("MiChat relay listening on port @", PORT);
    }

    private void serve(Socket socket){
        try{
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            clients.add(out);
            BufferedReader in = new BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
            String line;
            while((line = in.readLine()) != null){
                String safe = line.replaceAll("[\\r\\n]", "");
                for(PrintWriter client : clients) client.println(safe);
            }
        }catch(Exception e){
            Log.err("MiChat peer disconnected", e);
        }
    }

    void send(String name, String text){
        if(text == null || text.trim().isEmpty()) return;
        String line = (name == null ? "Player" : name).replaceAll("[\\r\\n]", "") + ": " + text.replaceAll("[\\r\\n]", "");
        PrintWriter out = connection;
        if(out != null) out.println(line);
        else Log.info("MiChat: @", line);
    }

    void addMessages(Table table){
        table.add("MiChat relay via WireGuard").left();
    }
}
