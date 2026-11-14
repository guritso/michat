package michat;

import arc.*;
import arc.graphics.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;

/** Minimal, native-looking chat window. */
public class ChatWindow implements ChatMesh.Listener{
    private final ChatMesh mesh;

    private Table root;
    private Table list;
    private Table statusRow;
    private ScrollPane scroll;
    private TextField field;
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
        root.visible(() -> shown);

        Table box = new Table(Styles.black6);
        box.margin(10f);

        boolean mob = Core.app.isMobile();
        float boxH = Math.min(mob ? 580f : 430f, Core.scene.getHeight() - 20f);
        float listH = Math.min(320f, boxH - 116f);
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
        box.row().add(scroll).width(width() - 20f).maxHeight(listH).row();

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

        root.add(box).width(width()).maxHeight(boxH);
        Core.scene.add(root);

        rebuildStatus();
        rebuildMessages();
    }

    private float width(){
        if(Core.app.isMobile()){
            return Math.max(280f, Core.scene.getWidth() - 16f);
        }
        return Math.min(424f, Core.scene.getWidth() - 24f);
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
        Core.app.post(this::rebuildMessages);
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
                Label label = new Label(
                    "[" + nameColor(m.name) + "]" + esc(m.name) + "[]: [white]" + esc(m.text)
                );
                label.setWrap(true);
                list.add(label).left().growX().width(width() - 44f).pad(1f, 6f, 1f, 6f).row();
            }
        }
        toBottom();
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
