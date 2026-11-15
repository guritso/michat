package michat;

import arc.*;
import arc.graphics.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.core.NetClient;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Player;
import mindustry.gen.Sounds;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;


import java.util.List;

/** Native Mindustry chat dialog for the menu and chat history/actions. */
public class ChatWindow extends BaseDialog implements ChatMesh.Listener{
    private static final int REPLAY_LIMIT = 25;

    private final ChatMesh mesh;
    // in-match chat for matches without a networked server (the native chat is unusable there)
    private final MatchChatFragment matchChat;
    // last mesh message already handled by the native chat (delivered or intentionally skipped)
    private ChatMesh.Message lastDelivered;

    private Table list;
    private Table statusRow;
    private ScrollPane scroll;
    private TextField field;
    private float winWidth;

    public ChatWindow(ChatMesh mesh, MatchChatFragment matchChat){
        super("MiChat");
        this.mesh = mesh;
        this.matchChat = matchChat;
        addCloseButton();
        titleTable.button(Icon.link, Styles.cleari, this::showPeerDialog)
            .size(48f).get().resizeImage(24f);
        mesh.addListener(this);
        build();
        shown(() -> {
            rebuildStatus();
            rebuildMessages();
        });
        onResize(() -> {
            build();
            rebuildStatus();
            rebuildMessages();
        });
    }

    public boolean shown(){
        return isShown();
    }

    public void toggle(){
        if(isShown()){
            hide();
        }else{
            show();
            Core.app.post(this::toBottom);
        }
    }

    private void build(){
        cont.clear();
        float availableWidth = Core.graphics.getWidth() / Scl.scl(1.05f) - Scl.scl(28f);
        winWidth = Math.max(260f, Math.min(900f, availableWidth));
        float availableHeight = Core.graphics.getHeight() / Scl.scl(1.05f) - Scl.scl(220f);
        float listHeight = Math.max(240f, Math.min(720f, availableHeight));
        cont.defaults().width(winWidth).pad(4f);

        statusRow = new Table();
        cont.add(statusRow).growX().left().row();

        list = new Table();
        // Match the native chat: keep recent messages at the bottom, with history rising above.
        list.bottom().left().margin(6f);
        scroll = new ScrollPane(list, Styles.smallPane);
        scroll.setScrollingDisabled(true, false);
        cont.add(scroll).growX().height(listHeight).row();

        Table input = new Table();
        field = new TextField("", Styles.defaultField);
        field.setMaxLength(300);
        field.setMessageText("Message");
        field.setTextFieldListener((tf, c) -> {
            if(c == '\r' || c == '\n') send();
        });
        input.add(field).growX().height(Core.app.isMobile() ? 64f : 42f);
        input.button("Send", this::send).padLeft(6f).size(Core.app.isMobile() ? 112f : 84f, Core.app.isMobile() ? 64f : 42f);
        cont.add(input).growX().padTop(6f).row();
    }

    private void send(){
        String text = field.getText();
        field.setText("");
        mesh.send(text);
    }

    private void showPeerDialog(){
        BaseDialog dialog = new BaseDialog("Connect by IP");
        dialog.addCloseButton();
        dialog.cont.add("Enter the peer's VPN or LAN IPv4 address.")
            .left().wrap().width(Core.app.isMobile() ? 480f : 420f).pad(8f).row();

        TextField address = new TextField(mesh.manualPeerIp(), Styles.defaultField);
        address.setMessageText("e.g. 100.x.x.x");
        address.setMaxLength(15);
        dialog.cont.add(address).growX().height(Core.app.isMobile() ? 64f : 46f).pad(8f).row();
        if(!mesh.manualPeerIp().isEmpty()) address.selectAll();

        dialog.buttons.button("Save & connect", Icon.play, () -> {
            try{
                mesh.setManualPeerIp(address.getText());
                dialog.hide();
            }catch(IllegalArgumentException e){
                if(Vars.ui != null) Vars.ui.showInfoToast("Enter a valid IPv4 address, or leave it empty to remove.", 3f);
            }
        }).size(Core.app.isMobile() ? 240f : 180f, Core.app.isMobile() ? 64f : 54f);
        dialog.buttons.button("Cancel", Icon.cancel, dialog::hide)
            .size(Core.app.isMobile() ? 180f : 140f, Core.app.isMobile() ? 64f : 54f);
        dialog.show();
    }

    private void toBottom(){
        if(scroll != null) Core.app.post(() -> scroll.setScrollY(Float.MAX_VALUE));
    }

    @Override
    public void onMessage(ChatMesh.Message m){
        Core.app.post(() -> {
            if(m.schem != null) ChatMesh.log("window got schematic from " + m.name +
                (m.self ? " (self)" : " (peer)"));
            boolean inMenu = Vars.ui == null || !Vars.state.isGame();
            if(deliver(m, false) || inMenu){
                // same arrival sound the native chat plays for received packets
                Sounds.uiChat.play();
            }
            // The menu has no native chat feed, so briefly surface incoming messages there too.
            if(inMenu && !m.self && Vars.ui != null){
                if(m.schem != null){
                    Vars.ui.showInfoFade(esc(m.name) + " shared schematic '" + esc(m.text) + "'");
                }else if(m.system){
                    Vars.ui.showInfoFade("[gray]" + who(m.name) + " " + esc(m.text));
                }else{
                    Vars.ui.showInfoFade(nativeFormat(m.name, esc(m.text)));
                }
            }
            rebuildMessages();
        });
    }

    @Override
    public void onStatus(){
        Core.app.post(this::rebuildStatus);
    }

    /** Replays mesh history not yet handled into the chat that is active when a match starts. */
    public void onMatchLoaded(){
        Core.app.post(() -> {
            if(!Vars.state.isGame() || Vars.ui == null) return;
            if(isShown()) hide();
            // the game wipes pending chat on world load; do the same in the mod chat
            if(matchChat != null) matchChat.clearMessages();
            List<ChatMesh.Message> h = mesh.history();
            int idx = lastDelivered == null ? -1 : h.indexOf(lastDelivered);
            int start = idx >= 0 ? Math.min(idx + 1, h.size()) : Math.max(0, h.size() - REPLAY_LIMIT);
            for(int i = start; i < h.size(); i++){
                deliver(h.get(i), true);
            }
        });
    }

    /**
     * Sends one mesh message to the chat active in the current state, tracking what was handled.
     * Without a networked server the native chat is hidden and drops messages, so the mod's
     * own match chat takes over there. Messages that arrive while a match is loading are left
     * for the match-load replay.
     * @return whether the message was displayed in any chat.
     */
    private boolean deliver(ChatMesh.Message m, boolean replay){
        if(Vars.ui == null || !Vars.state.isGame()) return false; // outside matches no chat is used
        if(m.schem != null && !replay){
            ChatMesh.log("deliver schematic in match (" + (Vars.net.active() ? "native" : "michat") + " chat)");
        }
        // everything up to here has been handled, even when intentionally skipped below
        lastDelivered = m;
        // without a server there is no echo of our own messages: show them directly
        boolean own = !Vars.net.active() && matchChat != null;
        if(m.system){
            if(!m.self) return route("[gray]" + who(m.name) + " " + esc(m.text), own);
            return false;
        }else if(m.schem != null){
            // schematics only travel via the mesh, so the same-match and own-message
            // dedup used for server-echoed chat does not apply to them
            return route("[gray]" + who(m.name) + " shared schematic '" + esc(m.text) + "'", own);
        }else if(m.self){
            return own && route(nativeFormat(m.name, m.text), true);
        }else if(!(!replay && inSameMatch(m.name))){
            // in matches the server already echoes same-match players
            return route(nativeFormat(m.name, m.text), own);
        }
        return false;
    }

    /** Displays one chat line in the mod chat (no server) or in the native chat, if it works. */
    private boolean route(String text, boolean useModChat){
        if(useModChat && matchChat != null){
            matchChat.addMessage(text);
        }else{
            Vars.ui.chatfrag.addMessage(text);
        }
        return true;
    }

    private void rebuildMessages(){
        list.clear();
        for(ChatMesh.Message m : mesh.history()){
            if(m.system){
                Label label = messageLabel("[#a2a2a2]" + who(m.name) + " " + esc(m.text));
                float actionWidth = m.joinIp != null && !m.self ? 48f : 0f;
                list.add(label).left().growX().width(winWidth - 44f - actionWidth).pad(1f, 6f, 1f, 6f);
                if(m.joinIp != null && !m.self){
                    list.button(Icon.play, Styles.cleari, () -> joinLan(m.joinIp, m.joinPort))
                        .size(40f).pad(1f, 2f, 1f, 6f).get().resizeImage(24f);
                }
                list.row();
            }else if(m.schem != null){
                Label label = messageLabel("(" + who(m.name) + "): [white]schematic '" + esc(m.text) + "'");
                list.add(label).left().growX().width(winWidth - 44f - 88f).pad(1f, 6f, 1f, 6f);
                list.button(Icon.download, Styles.cleari, () -> importSchematic(m.schem))
                    .size(40f).pad(1f, 2f, 1f, 6f).get().resizeImage(24f);
                list.row();
            }else{
                Label label = messageLabel(nativeFormat(m.name, m.text));
                list.add(label).left().growX().width(winWidth - 44f).pad(1f, 6f, 1f, 6f).row();
            }
        }
        toBottom();
    }

    private static Label messageLabel(String text){
        Label label = new Label(text);
        label.setWrap(true);
        if(Core.app.isMobile()) label.setFontScale(1.12f);
        return label;
    }

    /** Imports a schematic received over the mesh. */
    private static void importSchematic(String code){
        try{
            Schematic s = Schematics.readBase64(code);
            Vars.schematics.add(s);
            Vars.ui.showInfo("schematic imported");
            ChatMesh.log("schematic imported from received code");
        }catch(Exception e){
            Vars.ui.showException(e);
            ChatMesh.log("schematic import failed: " + e);
        }
    }

    /** Uses the game's normal join flow so its connection errors and loading UI are preserved. */
    private static void joinLan(String ip, int port){
        ChatMesh.log("joining LAN host " + ip + ":" + port);
        NetClient.connect(ip, port);
    }

    /** Names may carry the player's own native color markup. */
    private static String who(String name){
        if(name.startsWith("[")) return name + "[]";
        return "[" + nameColor(name) + "]" + esc(name) + "[]";
    }

    /** Identical to the native server chat formatter: coral brackets and colon, colored name, white text. */
    private static String nativeFormat(String name, String text){
        return "[coral][[" + who(name) + "[coral]]:[white] " + text;
    }

    /** True when the sender is a player in the local match: the server already delivers their chat. */
    private static boolean inSameMatch(String name){
        if(!Vars.state.isGame() || Groups.player == null) return false;
        String raw = Strings.stripColors(name).trim().toLowerCase();
        if(raw.isEmpty()) return false;
        for(Player p : Groups.player){
            if(p == null || p.name == null) continue;
            if(Strings.stripColors(p.name).trim().toLowerCase().equals(raw)) return true;
        }
        return false;
    }

    private void rebuildStatus(){
        statusRow.clear();
        statusRow.add("[#a2a2a2]v" + ChatMesh.VERSION + " \u00b7 ").left();
        if(mesh.localIp().isEmpty()){
            statusRow.add("[#e55454]\u25cf[] [#a2a2a2]no network detected").left();
        }else{
            String peers = mesh.peerSummary();
            String mode = mesh.onVpn() ? "VPN" : "LAN";
            statusRow.add("[#54d860]\u25cf[] [#a2a2a2]" + mode + " · " + mesh.localIp() +
                (peers.isEmpty() ? " \u00b7 searching" : " \u00b7 " + peers)).left();
        }
    }

    private static String esc(String s){
        return s.replace("[", "[[");
    }

    private static String nameColor(String name){
        int h = Math.abs(name.hashCode());
        // bright pastel-like color: fully saturated hue, lightened toward white for readability
        Color c = new Color().fromHsv(h % 360f, 0.6f, 1f).lerp(Color.white, 0.25f);
        return "#" + String.format("%02x%02x%02x",
            (int)(c.r * 255f), (int)(c.g * 255f), (int)(c.b * 255f));
    }
}
