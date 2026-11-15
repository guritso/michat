package michat;

import arc.Core;
import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType.ClientChatEvent;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.ResizeEvent;
import mindustry.game.EventType.WorldLoadEvent;
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

            // announce match starts to peers, then replay recent mesh history into the native chat
            Events.on(WorldLoadEvent.class, ev -> Core.app.post(() -> {
                try{
                    StringBuilder sb = new StringBuilder("entered '");
                    if(Vars.state.map != null){
                        sb.append(Vars.state.map.name()).append("'");
                        sb.append(" (").append(Vars.state.map.width)
                            .append("x").append(Vars.state.map.height);
                        if(Vars.state.rules.mode() != null){
                            sb.append(", ").append(Vars.state.rules.mode());
                        }
                        sb.append(")");
                    }
                    mesh.announce(sb.toString());
                }catch(Exception ignored){
                }
                if(window != null) window.onMatchLoaded();
            }));
        });
    }
}
