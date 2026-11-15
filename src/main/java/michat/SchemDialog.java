package michat;

import arc.Core;
import arc.scene.ui.TextButton.*;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.FileChooser;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SchematicsDialog;
import mindustry.ui.Styles;

/** Schematic dialog that adds a "send to chat" export option. */
public class SchemDialog extends SchematicsDialog{
    private final ChatMesh mesh;

    public SchemDialog(ChatMesh mesh){
        super();
        this.mesh = mesh;
    }

    @Override
    public void showExport(Schematic s){
        BaseDialog dialog = new BaseDialog("@editor.export");
        dialog.cont.pane(p -> {
            p.margin(10f);
            p.table(Tex.button, t -> {
                TextButtonStyle style = Styles.flatt;
                t.defaults().size(280f, 60f).left();
                if(Vars.steam && !s.hasSteamID()){
                    t.button("@workshop.share", Icon.book, style,
                        () -> Vars.platform.publish(s)).marginLeft(12f);
                    t.row();
                    dialog.hide();
                }
                t.button("@copy.clipboard", Icon.copy, style, () -> {
                    dialog.hide();
                    Vars.ui.showInfoFade("@copied");
                    Core.app.setClipboardText(Vars.schematics.writeBase64(s));
                }).marginLeft(12f);
                t.row();
                t.button("@export.file", Icon.export, style, () -> {
                    dialog.hide();
                    FileChooser.export(s.name(), Vars.schematicExtension, file -> Schematics.write(s, file));
                }).marginLeft(12f);
                t.row();
                t.button("Send to chat", Icon.chat, style, () -> {
                    dialog.hide();
                    String name = s.name();
                    boolean ok = mesh.sendSchematic(name == null || name.isEmpty() ? "schema" : name,
                        Vars.schematics.writeBase64(s));
                    if(ok){
                        Vars.ui.showInfoFade("schematic sent");
                    }else{
                        Vars.ui.showInfoFade("schematic failed to send");
                    }
                }).marginLeft(12f);
            });
        });

        dialog.addCloseButton();
        dialog.show();
    }
}
