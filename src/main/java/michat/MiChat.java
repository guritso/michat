package michat;

import arc.Core;
import arc.Events;
import arc.scene.Element;
import arc.scene.event.ClickListener;
import arc.scene.event.EventListener;
import arc.scene.ui.ImageButton;
import arc.struct.Seq;
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
    private MatchChatFragment matchChat;

    @Override
    public void init(){
        Events.on(ClientLoadEvent.class, e -> {
            mesh = new ChatMesh();
            mesh.start();
            matchChat = new MatchChatFragment(mesh);
            window = new ChatWindow(mesh, matchChat);

            Vars.ui.menufrag.addButton("MiChat", Icon.chat, () -> window.toggle());
            // schematic export dialog gains a "send to chat" option (in-match access reads this field)
            Vars.ui.schematics = new SchemDialog(mesh);
            // the desktop menu caches its button list with the old dialog reference;
            // nulling the public cache makes the resize rebuild bind to the new dialog
            Vars.ui.menufrag.desktopButtons = null;
            // the mobile HUD build captured ui.schematics before mods loaded; rebind
            // the named paste button so it opens the mod's dialog too, and take over the
            // named chat button in matches without a server (the native chat is unusable there)
            rebindHudButtons();
            // the menu only rebuilds its button grid on resize events
            Events.fire(new ResizeEvent());

            // messages typed in the native in-game chat are also broadcast to MiChat peers
            Events.on(ClientChatEvent.class, ev -> {
                if(ev.message != null && !ev.message.startsWith("/")){
                    mesh.send(ev.message);
                }
            });

            // replay pending history, then announce the match start to peers
            Events.on(WorldLoadEvent.class, ev -> Core.app.post(() -> {
                if(window != null) window.onMatchLoaded();
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
            }));
        });
    }

    /**
     * Removes the last ClickListener: arc's Button keeps its own internal one first (it drives
     * the pressed visual and the change event), and Table.button attaches the task's after it.
     */
    private static void removeTaskClickListener(Element button){
        Seq<EventListener> ls = button.getListeners();
        for(int i = ls.size - 1; i >= 0; i--){
            if(ls.get(i) instanceof ClickListener){
                button.removeListener(ls.get(i));
                return;
            }
        }
    }

    private void rebindHudButtons(){
        // paste button: open the mod's export-capable dialog, resolved at click time
        Element hudPaste = Core.scene.find("schematics");
        if(hudPaste != null){
            removeTaskClickListener(hudPaste);
            hudPaste.clicked(() -> Vars.ui.schematics.show());
        }

        Element hudChat = Core.scene.find("chat");
        if(hudChat == null) return;
        removeTaskClickListener(hudChat);
        hudChat.clicked(() -> {
            if(matchChat != null && !Vars.net.active() && Vars.state.isGame()){
                // our match chat: the native chat cannot be seen without a server
                if(matchChat.shown()){
                    matchChat.hide();
                }else{
                    matchChat.toggle();
                }
            }else if(Vars.net.active()){
                // native behavior, as built in HudFragment
                if(Vars.ui.chatfrag.shown()){
                    Vars.ui.chatfrag.hide();
                }else{
                    Vars.ui.chatfrag.toggle();
                }
            }else if(Vars.state.isCampaign()){
                Vars.ui.research.show();
            }else{
                Vars.ui.database.show();
            }
        });

        // replaces the game's icon updater (Element stores a single one) with merged logic
        hudChat.update(() -> {
            if(hudChat instanceof ImageButton){
                ImageButton b = (ImageButton)hudChat;
                if(Vars.state.isGame() || Vars.net.active()){
                    // chat (ours in matches without a server, native when networked)
                    ((ImageButton.ImageButtonStyle)b.getStyle()).imageUp = Icon.chat;
                }else if(Vars.state.isCampaign()){
                    ((ImageButton.ImageButtonStyle)b.getStyle()).imageUp = Icon.tree;
                }else{
                    ((ImageButton.ImageButtonStyle)b.getStyle()).imageUp = Icon.book;
                }
            }
        });
    }
}
