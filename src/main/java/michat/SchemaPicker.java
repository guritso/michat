package michat;

import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import arc.scene.ui.Dialog;

/** Simple picker to send one of the local schematics to the MiChat mesh. */
public class SchemaPicker extends BaseDialog{
    private final ChatMesh mesh;

    public SchemaPicker(ChatMesh mesh){
        super("Send schematic to chat");
        this.mesh = mesh;
        addCloseButton();
    }

    @Override
    public Dialog show(){
        rebuild();
        return super.show();
    }

    private void rebuild(){
        cont.clear();
        cont.pane(t -> {
            t.margin(10f);
            for(Schematic s : Vars.schematics.all()){
                t.table(Tex.button, row -> {
                    String name = s.name();
                    String shown = name == null || name.isEmpty() ? "(unnamed)" : name;
                    row.add(shown).left().growX().pad(8f);
                    row.button(Icon.chat, Styles.clearNonei, () -> {
                        hide();
                        boolean ok = mesh.sendSchematic(shown, Vars.schematics.writeBase64(s));
                        Vars.ui.showInfoToast(ok ? "schematic sent" : "schematic failed to send", 3f);
                    }).size(48f).pad(4f);
                }).growX().padBottom(4f).row();
            }
        }).growX().row();
    }
}
