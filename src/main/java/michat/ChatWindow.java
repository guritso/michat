package michat;

import arc.*;
import arc.graphics.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;

import java.util.List;

/** Minimal, native-looking chat window. */
public class ChatWindow implements ChatMesh.Listener{
    private final ChatMesh mesh;

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
    public void onMessage(){
        Core.app.post(() -> {
            List<ChatMesh.Message> h = mesh.history();
            // while playing, mesh messages are mirrored into the native chat
            if(!h.isEmpty() && Vars.state.isGame() && Vars.ui != null){
                ChatMesh.Message m = h.get(h.size() - 1);
                if(m.system){
                    Vars.ui.chatfrag.addMessage("[gray]" + esc(m.name) + " " + esc(m.text));
                }else{
                    Vars.ui.chatfrag.addMessage(who(m.name) + ": " + esc(m.text));
                }
            }
            rebuildMessages();
        });
    }

    @Override
    public void onStatus(){
        Core.app.post(this::rebuildStatus);
    }

    private void rebuildMessages(){
        list.clear();
        for(ChatMesh.Message m : mesh.history()){
            if(m.system){
                list.add("[#a2a2a2]" + esc(m.name) + " " + esc(m.text))
                    .left().growX().pad(1f, 6f, 1f, 6f).row();
            }else{
                Label label = new Label(who(m.name) + ": [white]" + esc(m.text));
                label.setWrap(true);
                list.add(label).left().growX().width(winWidth - 44f).pad(1f, 6f, 1f, 6f).row();
            }
        }
        toBottom();
    }

    /** Names may carry the player's own native color markup. */
    private static String who(String name){
        if(name.startsWith("[")) return name;
        return "[" + nameColor(name) + "]" + esc(name) + "[]";
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
