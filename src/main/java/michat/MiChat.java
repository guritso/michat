package michat;

import arc.Core;
import arc.Events;
import arc.scene.Element;
import arc.scene.event.ChangeListener;
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
            // the named paste button so it opens the mod's dialog too
            Element hudPaste = Core.scene.find("schematics");
            if(hudPaste != null){
                for(int i = hudPaste.getListeners().size - 1; i >= 0; i--){
                    if(hudPaste.getListeners().get(i) instanceof ChangeListener){
                        hudPaste.removeListener(hudPaste.getListeners().get(i));
                    }
                }
                hudPaste.changed(() -> Vars.ui.schematics.show());
            }
            // in matches without a server the native chat is hidden and drops messages;
            // rebind the named HUD chat button (which would open the database there) to the
            // mod's match chat, keeping the native action whenever a server exists
            rebindHudChatButton();
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
     * The mobile HUD has one button that toggles the native chat when networked, and opens
     * the research/database dialogs otherwise. Take it over only in matches without a server,
     * where the native chat cannot be seen; call the original behavior in every other state.
     * Replaces the game's icon updater (Element stores a single one) with merged logic.
     */
    private void rebindHudChatButton(){
        Element hudChat = Core.scene.find("chat");
        if(hudChat == null) return;

        Seq<EventListener> originals = new Seq<>();
        for(EventListener l : hudChat.getListeners()){
            if(l instanceof ChangeListener) originals.add(l);
        }
        for(int i = hudChat.getListeners().size - 1; i >= 0; i--){
            if(hudChat.getListeners().get(i) instanceof ChangeListener){
                hudChat.removeListener(hudChat.getListeners().get(i));
            }
        }

        hudChat.changed(() -> {
            if(matchChat != null && !Vars.net.active() && Vars.state.isGame()){
                matchChat.toggle();
            }else{
                ChangeListener.ChangeEvent ev = new ChangeListener.ChangeEvent();
                for(EventListener l : originals) l.handle(ev);
            }
        });

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
