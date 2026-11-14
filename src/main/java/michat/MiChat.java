package michat;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
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

            Drawable icon = loadIcon();
            Vars.ui.menufrag.addButton("MiChat", icon, () -> window.toggle());
            // the menu only rebuilds its button grid on resize events
            Events.fire(new ResizeEvent());

            Table corner = new Table();
            corner.touchable = Touchable.enabled;
            corner.visible(() -> Vars.state.isGame());
            corner.bottom().left();
            corner.button(icon, () -> window.toggle()).pad(10f).size(54f);
            Core.scene.add(corner);
        });
    }

    private static Drawable loadIcon(){
        try{
            Fi file = Vars.mods.getMod(MiChat.class).root.child("icon.png");
            if(file.exists()){
                return new TextureRegionDrawable(new TextureRegion(new Texture(file)));
            }
        }catch(Exception ignored){
        }
        return Icon.chat;
    }
}
