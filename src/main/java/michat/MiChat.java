package michat;

import arc.Core;
import arc.Events;
import arc.scene.event.Touchable;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.Icon;
import mindustry.mod.Mod;

public class MiChat extends Mod{
    private ChatMesh mesh;
    private ChatWindow window;

    @Override
    public void init(){
        Events.on(ClientLoadEvent.class, e -> {
            mesh = new ChatMesh();
            mesh.start();
            window = new ChatWindow(mesh);

            Vars.ui.menufrag.addButton("MiChat", () -> window.toggle());

            Table corner = new Table();
            corner.touchable = Touchable.enabled;
            corner.visible(() -> Vars.state.isGame());
            corner.bottom().left();
            corner.button(Icon.chatSmall, () -> window.toggle()).pad(10f);
            Core.scene.add(corner);
        });
    }
}
