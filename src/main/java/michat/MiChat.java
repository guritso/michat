package michat;

import arc.Core;
import arc.Events;
import arc.scene.ui.Dialog;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.Mod;
import mindustry.Vars;

public class MiChat extends Mod{
    private ChatLink link;

    @Override
    public void init(){
        Events.on(ClientLoadEvent.class, event -> {
            link = new ChatLink();
            Vars.ui.menufrag.addButton("MiChat", () -> showChat());
        });
    }

    private void showChat(){
        Dialog dialog = new Dialog("MiChat");
        dialog.cont.pane(messages -> link.addMessages(messages)).width(520f).height(320f).row();
        TextField host = new TextField(Core.settings.getString("michat-host", ""));
        TextField name = new TextField(Core.settings.getString("michat-name", "Player"));
        dialog.cont.add("WireGuard IP:").left();
        dialog.cont.add(host).width(220f).row();
        dialog.cont.add("Name:").left();
        dialog.cont.add(name).width(220f).row();
        dialog.buttons.button("Connect", () -> {
            Core.settings.put("michat-host", host.getText());
            Core.settings.put("michat-name", name.getText());
            link.connect(host.getText(), name.getText());
        }).size(120f, 50f);
        dialog.buttons.button("Host relay", () -> link.host(name.getText())).size(120f, 50f);
        TextField message = new TextField("");
        dialog.cont.add(message).width(380f);
        dialog.cont.button("Send", () -> {
            link.send(name.getText(), message.getText());
            message.setText("");
        }).size(100f, 40f).row();
        dialog.buttons.button("Close", dialog::hide).size(120f, 50f);
        dialog.show();
    }
}
