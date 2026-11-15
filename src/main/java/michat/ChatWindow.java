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
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Player;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;


import java.util.List;

/** Minimal, native-looking chat window. */
public class ChatWindow implements ChatMesh.Listener{
    private static final int REPLAY_LIMIT = 25;

    private final ChatMesh mesh;
    private final SchemaPicker picker;
    // last mesh message already handled by the native chat (delivered or intentionally skipped)
    private ChatMesh.Message lastDelivered;

    private Table root;
    private Table list;
    private Table statusRow;
    private ScrollPane scroll;
    private TextField field;
    private float winWidth;
    private boolean shown;

    public ChatWindow(ChatMesh mesh){
        this.mesh = mesh;
        mesh.addListener(this);
        this.picker = new SchemaPicker(mesh);
        build();
    }

    public boolean shown(){
        return shown;
    }

    public void toggle(){
        shown = !shown;
        if(shown) Core.app.post(this::toBottom);
    }

    private void build(){
        root = new Table();
        root.setFillParent(true);
        root.touchable = Touchable.childrenOnly;
        // MiChat is only used from the menu; during matches the native chat takes over
        root.visible(() -> shown && !Vars.state.isGame());

        Table box = new Table(Styles.black6);
        box.margin(10f);

        boolean mob = Core.app.isMobile();
        float sceneW = Math.max(Core.scene.getWidth(), 300f);
        float sceneH = Math.max(Core.scene.getHeight(), 400f);
        float w = Math.min(mob ? 500f : 424f, sceneW - 24f);
        winWidth = w;
        float boxH = Math.min(mob ? 560f : 430f, sceneH - 24f);
        float listH = Math.max(200f, Math.min(320f, boxH - 116f));
        float closeSize = mob ? 64f : 40f;

        Table head = new Table();
        head.add("MiChat").color(Pal.accent).growX().left();
        head.button(Icon.upload, Styles.cleari, () -> picker.show()).size(closeSize).get()
            .resizeImage(closeSize * 0.6f);
        head.button(Icon.cancel, Styles.cleari, () -> toggle()).size(closeSize).get()
            .resizeImage(closeSize * 0.6f);
        box.add(head).growX().row();

        list = new Table();
        list.margin(2f, 6f, 2f, 6f);
        scroll = new ScrollPane(list, Styles.smallPane);
        scroll.setScrollingDisabled(true, false);
        box.row().add(scroll).width(w - 20f).maxHeight(listH).row();

        Table input = new Table();
        field = new TextField("", Styles.defaultField);
        field.setMaxLength(300);
        field.setMessageText("Message");
        field.setTextFieldListener((tf, c) -> {
            if(c == '\r' || c == '\n') send();
        });
        input.add(field).growX();
        input.button("Send", this::send).padLeft(6f);
        box.row().add(input).growX().padTop(8f).row();

        statusRow = new Table();
        box.row().add(statusRow).growX().left().padTop(4f);

        root.add(box).width(w).maxHeight(boxH);
        Core.scene.add(root);

        rebuildStatus();
        rebuildMessages();
    }

    private float width(){
        float base = Core.app.isMobile() ? 500f : 424f;
        return Math.min(base, Math.max(300f, Core.scene.getWidth()) - 24f);
    }

    private void send(){
        String text = field.getText();
        field.setText("");
        mesh.send(text);
    }

    private void toBottom(){
        Core.app.post(() -> scroll.setScrollY(Float.MAX_VALUE));
    }

    @Override
    public void onMessage(ChatMesh.Message m){
        Core.app.post(() -> {
            deliver(m, false);
            rebuildMessages();
        });
    }

    @Override
    public void onStatus(){
        Core.app.post(this::rebuildStatus);
    }

    /** Replays mesh history not yet handled into the native chat when a match starts. */
    public void onMatchLoaded(){
        Core.app.post(() -> {
            if(!Vars.state.isGame() || Vars.ui == null) return;
            List<ChatMesh.Message> h = mesh.history();
            int idx = lastDelivered == null ? -1 : h.indexOf(lastDelivered);
            int start = idx >= 0 ? Math.min(idx + 1, h.size()) : Math.max(0, h.size() - REPLAY_LIMIT);
            for(int i = start; i < h.size(); i++){
                deliver(h.get(i), true);
            }
        });
    }

    /**
     * Sends one mesh message to the native chat when appropriate, tracking what was handled.
     * Messages that arrive while a match is loading are left for the match-load replay.
     */
    private void deliver(ChatMesh.Message m, boolean replay){
        if(Vars.ui == null || !Vars.state.isGame()) return; // outside matches the native chat is not used
        // everything up to here has been handled, even when intentionally skipped below
        lastDelivered = m;
        if(m.system){
            if(!m.self){
                Vars.ui.chatfrag.addMessage("[gray]" + who(m.name) + " " + esc(m.text));
            }
        }else if(m.schem != null){
            // schematics only travel via the mesh, so the same-match and own-message
            // dedup used for server-echoed chat does not apply to them
            Vars.ui.chatfrag.addMessage("[gray]" + who(m.name) + " shared schematic '" + esc(m.text) + "'");
        }else if(!m.self && !(!replay && inSameMatch(m.name))){
            // in matches the server already echoes same-match players
            Vars.ui.chatfrag.addMessage(nativeFormat(m.name, m.text));
        }
    }

    private void rebuildMessages(){
        list.clear();
        for(ChatMesh.Message m : mesh.history()){
            if(m.system){
                Label label = new Label("[#a2a2a2]" + who(m.name) + " " + esc(m.text));
                label.setWrap(true);
                list.add(label).left().growX().width(winWidth - 44f).pad(1f, 6f, 1f, 6f).row();
            }else if(m.schem != null){
                Label label = new Label("(" + who(m.name) + "): [white]schematic '" + esc(m.text) + "'");
                label.setWrap(true);
                list.add(label).left().growX().width(winWidth - 44f - 88f).pad(1f, 6f, 1f, 6f);
                list.button("Import", () -> importSchematic(m.schem)).size(80f, 30f).pad(1f, 2f, 1f, 6f);
                list.row();
            }else{
                Label label = new Label(nativeFormat(m.name, m.text));
                label.setWrap(true);
                list.add(label).left().growX().width(winWidth - 44f).pad(1f, 6f, 1f, 6f).row();
            }
        }
        toBottom();
    }

    /** Imports a schematic received over the mesh. */
    private static void importSchematic(String code){
        try{
            Schematic s = Schematics.readBase64(code);
            Vars.schematics.add(s);
            Vars.ui.showInfoToast("schematic imported", 3f);
        }catch(Exception e){
            Vars.ui.showException(e);
        }
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
        if(!mesh.onVpn()){
            statusRow.add("[#e55454]\u25cf[] [#a2a2a2]no VPN detected").left();
        }else{
            String peers = mesh.peerSummary();
            statusRow.add("[#54d860]\u25cf[] [#a2a2a2]" + mesh.localIp() +
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
