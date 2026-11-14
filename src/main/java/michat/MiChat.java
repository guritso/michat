package michat;

import arc.Core;
import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType.ClientChatEvent;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.ResizeEvent;
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

            Vars.ui.menufrag.addButton("MiChat", Icon.chat, () -> window.toggle());
            // the menu only rebuilds its button grid on resize events
            Events.fire(new ResizeEvent());

            // messages typed in the native in-game chat are also broadcast to MiChat peers
            Events.on(ClientChatEvent.class, ev -> {
                if(ev.message != null && !ev.message.startsWith("/")){
                    mesh.send(ev.message);
                }
            });
        });
    }
}
