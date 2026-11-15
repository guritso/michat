package michat;

import arc.*;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Peer-to-peer chat over an existing VPN (WireGuard) interface.
 * No configuration: the VPN interface and its subnet are detected automatically,
 * and every machine on that subnet running Mindustry with this mod is found by
 * probing the chat port. Messages are flooded between peers (deduplicated by id,
 * with a small hop limit), so no central relay is needed.
 */
public class ChatMesh{
    public static final int PORT = 26319;

    private static final String HELLO = "MICHAT1";
    private static final String PREFIX = "MICHAT_MSG|";
    private static final String NOTE = "MICHAT_NOTE|";
    private static final String SCHEM = "MICHAT_SCHEM|";
    private static final int HOP_LIMIT = 4;
    private static final int MAX_HISTORY = 200;
    private static final int MAX_SEEN = 512;
    private static final int MAX_SCHEM = 90000;

    public interface Listener{
        void onMessage(Message message);
        void onStatus();
    }

    public static class Peer{
        public final String ip;
        public String name;
        public final boolean outgoing;
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;

        Peer(String ip, String name, boolean outgoing, Socket socket) throws IOException{
            this.ip = ip;
            this.name = name;
            this.outgoing = outgoing;
            this.socket = socket;
            // probe sockets carry a short read timeout; peer connections must wait indefinitely
            socket.setSoTimeout(0);
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        }
    }

    public static class Probe{
        final String ip, name;
        final Socket socket;
        final boolean outgoing;

        Probe(String ip, String name, Socket socket, boolean outgoing){
            this.ip = ip;
            this.name = name;
            this.socket = socket;
            this.outgoing = outgoing;
        }
    }

    public static class Message{
        public final String name, text;
        /** base64 schematic code when this message carries a schematic, null otherwise */
        public final String schem;
        public final boolean system, self;

        Message(String name, String text, boolean system, boolean self){
            this(name, text, system, self, null);
        }

        Message(String name, String text, boolean system, boolean self, String schem){
            this.name = name;
            this.text = text;
            this.schem = schem;
            this.system = system;
            this.self = self;
        }
    }

    private final Object lock = new Object();
    private final Seq<Peer> peers = new Seq<>();
    private final Seq<Listener> listeners = new Seq<>();
    private final List<Message> history = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private volatile String localIp = "";
    private ExecutorService scanPool;

    public void addListener(Listener listener){
        synchronized(lock){
            listeners.add(listener);
        }
    }

    public void start(){
        Thread accept = new Thread(this::acceptLoop, "michat-accept");
        accept.setDaemon(true);
        accept.start();

        scanPool = Executors.newFixedThreadPool(48, r -> {
            Thread t = new Thread(r, "michat-scan");
            t.setDaemon(true);
            return t;
        });

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "michat-discover");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::discover, 0, 10, TimeUnit.SECONDS);
    }

    // public state for the UI

    public String localIp(){
        return localIp;
    }

    public boolean onVpn(){
        return !localIp.isEmpty();
    }

    public String peerSummary(){
        synchronized(lock){
            Seq<String> names = new Seq<>();
            for(Peer p : peers) names.add(p.name);
            return String.join(", ", names.toArray(String.class));
        }
    }

    public List<Message> history(){
        synchronized(lock){
            return new ArrayList<>(history);
        }
    }

    public void send(String text){
        String clean = sanitize(text, 300);
        if(clean.isEmpty()) return;
        String name = myName();
        String id = randomId();
        markSeen(id);
        postMessage(name, clean, false, true);
        String line = PREFIX + id + "|0|" + name + "|" + clean;
        synchronized(lock){
            for(Peer p : peers) write(p, line);
        }
    }

    /** Broadcasts a system-style note (e.g. match announcements) to all peers. */
    public void announce(String text){
        String clean = sanitize(text, 200);
        if(clean.isEmpty()) return;
        String name = myName();
        String id = randomId();
        markSeen(id);
        // self = true so the sender does not mirror their own note back into the native chat
        postMessage(name, clean, true, true);
        String line = NOTE + id + "|0|" + name + "|" + clean;
        synchronized(lock){
            for(Peer p : peers) write(p, line);
        }
    }

    /** Sends a schematic to all peers. Returns false when the code was rejected. */
    public boolean sendSchematic(String schemaName, String code){
        if(code == null || code.length() > MAX_SCHEM || !code.startsWith("bXNjaAB")) return false;
        String cleanName = sanitize(schemaName, 60).replace("|", " ");
        if(cleanName.isEmpty()) cleanName = "schema";
        String name = myName();
        String id = randomId();
        markSeen(id);
        postMessage(name, cleanName, false, true, code);
        String line = SCHEM + id + "|0|" + name + "|" + cleanName + "|" + code;
        synchronized(lock){
            for(Peer p : peers) write(p, line);
        }
        return true;
    }

    // discovery

    private void discover(){
        try{
            String ip = findVpnIp();
            if(ip == null){
                if(!localIp.isEmpty()){
                    localIp = "";
                    closeAll();
                    statusChanged();
                }
                return;
            }
            localIp = ip;

            int self = lastOctet(ip);
            if(self > 0){
                String base = ip.substring(0, ip.lastIndexOf('.') + 1);
                List<Future<Probe>> futures = new ArrayList<>();
                for(int i = 1; i < 255; i++){
                    if(i == self) continue;
                    String target = base + i;
                    futures.add(scanPool.submit(() -> probe(target)));
                }
                for(Future<Probe> f : futures){
                    Probe p = f.get();
                    if(p != null) adopt(p);
                }
            }
            statusChanged();
        }catch(Exception ignored){
        }
    }

    private Probe probe(String target){
        try{
            Socket s = new Socket();
            s.connect(new InetSocketAddress(InetAddress.getByName(target), PORT), 300);
            s.setSoTimeout(600);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);
            out.println(HELLO + " " + myName());
            String line = in.readLine();
            if(line != null && line.startsWith(HELLO + " ")){
                return new Probe(target, sanitize(line.substring(HELLO.length() + 1), 64), s, true);
            }
            s.close();
        }catch(Exception ignored){
        }
        return null;
    }

    private void adopt(Probe p){
        boolean replaced = false;
        synchronized(lock){
            Peer existing = findPeer(p.ip);
            if(existing != null && (existing.socket.isClosed() || !existing.socket.isConnected())){
                peers.remove(existing);
                existing = null;
            }
            boolean wantOut = localIp.isEmpty() ? false : localIp.compareTo(p.ip) < 0;
            if(existing != null){
                if(existing.outgoing == wantOut){
                    closeQuietly(p.socket);
                    return;
                }
                closeQuietly(existing.socket);
                peers.remove(existing);
                replaced = true;
            }
            try{
                Peer peer = new Peer(p.ip, p.name, wantOut, p.socket);
                peers.add(peer);
                readAsync(peer);
                if(!replaced) postMessage(peer.name, "joined the chat", true, false);
            }catch(IOException e){
                closeQuietly(p.socket);
                return;
            }
        }
        statusChanged();
    }

    private void acceptLoop(){
        try(ServerSocket ss = new ServerSocket(PORT)){
            while(true){
                Socket s = ss.accept();
                Thread t = new Thread(() -> acceptOne(s), "michat-peer");
                t.setDaemon(true);
                t.start();
            }
        }catch(Exception ignored){
        }
    }

    private void acceptOne(Socket s){
        try{
            s.setSoTimeout(2000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line = in.readLine();
            if(line == null || !line.startsWith(HELLO + " ")){
                s.close();
                return;
            }
            String name = sanitize(line.substring(HELLO.length() + 1), 64);
            // reply with our own greeting so the probing side can complete its handshake
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);
            out.println(HELLO + " " + myName());
            String ip = s.getInetAddress().getHostAddress();
            adopt(new Probe(ip, name, s, false));
        }catch(Exception e){
            closeQuietly(s);
        }
    }

    // message exchange

    private void readAsync(Peer peer){
        Thread t = new Thread(() -> readLoop(peer), "michat-read");
        t.setDaemon(true);
        t.start();
    }

    private void readLoop(Peer peer){
        try{
            String line;
            while((line = peer.in.readLine()) != null){
                handle(peer, line);
            }
        }catch(Exception ignored){
        }finally{
            drop(peer, "left the chat");
        }
    }

    private void handle(Peer peer, String line){
        if(line.startsWith(HELLO + " ")){
            String n = sanitize(line.substring(HELLO.length() + 1), 64);
            if(!n.isEmpty() && !n.equals(peer.name)){
                peer.name = n;
                statusChanged();
            }
            return;
        }
        if(line.startsWith(PREFIX)){
            String[] parts = line.split("\\|", 5);
            if(parts.length < 5) return;
            String id = parts[1];
            String name = sanitize(parts[3], 64);
            String text = sanitize(parts[4], 300);
            int hop;
            try{
                hop = Integer.parseInt(parts[2]);
            }catch(Exception e){
                return;
            }
            if(!markSeen(id)) return;
            if(name.isEmpty() || text.isEmpty()) return;
            postMessage(name, text, false, false);
            if(hop < HOP_LIMIT){
                forward(peer, PREFIX + id + "|" + (hop + 1) + "|" + name + "|" + text);
            }
        }
        if(line.startsWith(SCHEM)){
            String[] parts = line.split("\\|", 6);
            if(parts.length < 6) return;
            String id = parts[1];
            String name = sanitize(parts[3], 64);
            String text = sanitize(parts[4], 60).replace("|", " ");
            String code = parts[5];
            int hop;
            try{
                hop = Integer.parseInt(parts[2]);
            }catch(Exception e){
                return;
            }
            if(!markSeen(id)) return;
            if(name.isEmpty() || text.isEmpty()) return;
            if(code.length() > MAX_SCHEM || !code.startsWith("bXNjaAB")) return;
            postMessage(name, text, false, false, code);
            if(hop < HOP_LIMIT){
                forward(peer, SCHEM + id + "|" + (hop + 1) + "|" + name + "|" + text + "|" + code);
            }
        }
        if(line.startsWith(NOTE)){
            String[] parts = line.split("\\|", 5);
            if(parts.length < 5) return;
            String id = parts[1];
            String name = sanitize(parts[3], 64);
            String text = sanitize(parts[4], 200);
            int hop;
            try{
                hop = Integer.parseInt(parts[2]);
            }catch(Exception e){
                return;
            }
            if(!markSeen(id)) return;
            if(name.isEmpty() || text.isEmpty()) return;
            postMessage(name, text, true, false);
            if(hop < HOP_LIMIT){
                forward(peer, NOTE + id + "|" + (hop + 1) + "|" + name + "|" + text);
            }
        }
    }

    private void forward(Peer except, String line){
        synchronized(lock){
            for(Peer p : peers){
                if(p != except) write(p, line);
            }
        }
    }

    private void write(Peer peer, String line){
        try{
            peer.out.println(line);
        }catch(Exception e){
            drop(peer, "left the chat");
        }
    }

    private void drop(Peer peer, String note){
        synchronized(lock){
            if(peers.remove(peer)){
                closeQuietly(peer.socket);
            }else{
                return;
            }
        }
        postMessage(peer.name, note, true, false);
        statusChanged();
    }

    private void closeAll(){
        synchronized(lock){
            for(Peer p : peers){
                closeQuietly(p.socket);
            }
            peers.clear();
        }
    }

    // bookkeeping

    private Peer findPeer(String ip){
        for(Peer p : peers){
            if(p.ip.equals(ip)) return p;
        }
        return null;
    }

    private boolean markSeen(String id){
        synchronized(seen){
            if(!seen.add(id)) return false;
            if(seen.size() > MAX_SEEN) seen.clear();
            return true;
        }
    }

    private void postMessage(String name, String text, boolean system, boolean self){
        postMessage(name, text, system, self, null);
    }

    private void postMessage(String name, String text, boolean system, boolean self, String schem){
        Message message = new Message(name, text, system, self, schem);
        synchronized(lock){
            history.add(message);
            while(history.size() > MAX_HISTORY) history.remove(0);
            for(Listener l : listeners) l.onMessage(message);
        }
    }

    private void statusChanged(){
        synchronized(lock){
            for(Listener l : listeners) l.onStatus();
        }
    }

    static String myName(){
        if(Vars.player != null && Vars.player.name != null && !Vars.player.name.trim().isEmpty()){
            String colored = Vars.player.coloredName();
            return colored == null || colored.isEmpty() ? Vars.player.name.trim() : colored;
        }
        return Core.settings.getString("name", "Player");
    }

    static String sanitize(String text, int limit){
        if(text == null) return "";
        text = text.replaceAll("[\\u0000-\\u001f]", " ").trim();
        if(text.length() > limit) text = text.substring(0, limit);
        return text;
    }

    private static String randomId(){
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static int lastOctet(String ip){
        try{
            return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1));
        }catch(Exception e){
            return -1;
        }
    }

    private static void closeQuietly(Socket s){
        try{
            s.close();
        }catch(Exception ignored){
        }
    }

    /** Finds the IPv4 address of a WireGuard/tun interface, falling back to any 10.x address. */
    private static String findVpnIp(){
        try{
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while(ifs.hasMoreElements()){
                NetworkInterface nif = ifs.nextElement();
                if(!nif.isUp() || nif.isLoopback()) continue;
                boolean vpn = isVpnNamed(nif);
                for(InetAddress a : Collections.list(nif.getInetAddresses())){
                    if(!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String s = a.getHostAddress();
                    if(vpn && s.startsWith("10.")) return s;
                    if(fallback == null && vpn) fallback = s;
                    if(fallback == null && s.startsWith("10.")) fallback = s;
                }
            }
            return fallback;
        }catch(Exception e){
            return null;
        }
    }

    private static boolean isVpnNamed(NetworkInterface nif){
        String name = nif.getName() == null ? "" : nif.getName().toLowerCase();
        String display = nif.getDisplayName() == null ? "" : nif.getDisplayName().toLowerCase();
        return name.startsWith("wg") || name.startsWith("tun") || display.contains("wireguard") || display.startsWith("tun");
    }
}
