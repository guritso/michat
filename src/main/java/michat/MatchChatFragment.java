package michat;

import arc.Core;
import arc.Input;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.GlyphLayout;
import arc.math.Mathf;
import arc.scene.ui.Label;
import arc.scene.ui.TextField;
import arc.scene.ui.TextField.TextFieldStyle;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Time;
import mindustry.Vars;
import mindustry.input.Binding;
import mindustry.ui.Fonts;

import static arc.Core.*;

/**
 * In-match chat for matches without a networked server (single player and campaign),
 * where the native chat fragment is hidden and even drops pending messages.
 * Visual and behavioral twin of the native ChatFragment, fed by the MiChat mesh;
 * the coral "Mi>" input marker tells it apart from the native chat.
 */
public class MatchChatFragment extends Table{
    private static final int messagesShown = 10;

    private final ChatMesh mesh;
    private final Seq<String> messages = new Seq<>();
    private final Seq<String> history = new Seq<>();
    private float fadetime;
    private boolean shown = false;
    private TextField chatfield;
    private final Label fieldlabel = new Label("[coral]Mi>[]");
    private final GlyphLayout layout = new GlyphLayout();
    private final float offsetx = Scl.scl(4), offsety = Scl.scl(4), fontoffsetx = Scl.scl(2),
        chatspace = Scl.scl(50);
    private final Color shadowColor = new Color(0, 0, 0, 0.5f);
    private final float textspacing = Scl.scl(10);
    private int historyPos = 0;
    private int scrollPos = 0;
    private boolean lastFrameHadFocus;

    public MatchChatFragment(ChatMesh mesh){
        super();
        this.mesh = mesh;
        setFillParent(true);
        history.insert(0, "");

        // the native chat shows and keeps messages only while a server exists;
        // this fragment takes over exactly when it does not
        visible(() -> !Vars.net.active() && Vars.state.isGame() && Vars.ui != null && Vars.ui.hudfrag.shown());

        update(() -> {
            boolean hasOtherFocus = (scene.getKeyboardFocus() != null && !chatfield.hasKeyboard()) &&
                !(Vars.ui.minimapfrag.shown() && !(scene.getKeyboardFocus() instanceof TextField));

            // hand the chat back to the native fragment as soon as a server exists
            if(Vars.net.active() && shown) hide();

            if(!Vars.net.active() && Vars.state.isGame() && input.keyTap(Binding.chat)
                && !hasOtherFocus && !lastFrameHadFocus && !Vars.ui.consolefrag.shown()){
                toggle();
            }

            if(shown){
                if(input.keyTap(Binding.chatHistoryPrev) && historyPos < history.size - 1){
                    if(historyPos == 0) history.set(0, chatfield.getText());
                    historyPos++;
                    updateChat();
                }
                if(input.keyTap(Binding.chatHistoryNext) && historyPos > 0){
                    historyPos--;
                    updateChat();
                }
                scrollPos = (int)Mathf.clamp(scrollPos + input.axis(Binding.chatScroll),
                    0, Math.max(0, messages.size - messagesShown));
            }

            lastFrameHadFocus = hasOtherFocus;
        });

        setup();
        Core.scene.add(this);
    }

    private void setup(){
        chatfield = new TextField("", new TextFieldStyle(scene.getStyle(TextFieldStyle.class)));
        chatfield.setMaxLength(Vars.maxTextLength);
        chatfield.getStyle().background = null;
        chatfield.getStyle().fontColor = Color.white;
        chatfield.setStyle(chatfield.getStyle());

        bottom().left().marginBottom(offsety).marginLeft(offsetx * 2).add(fieldlabel).padBottom(6f);
        add(chatfield).padBottom(offsety).padLeft(offsetx).growX().padRight(offsetx).height(28);

        if(Vars.mobile){
            marginBottom(105f);
            marginRight(240f);
        }
    }

    public boolean shown(){
        return shown;
    }

    public void toggle(){
        if(!shown){
            scene.setKeyboardFocus(chatfield);
            shown = true;
            if(Vars.mobile){
                Input.TextInput ti = new Input.TextInput();
                ti.maxLength = Vars.maxTextLength;
                ti.title = "MiChat";
                ti.accepted = text -> {
                    chatfield.setText(text);
                    sendMessage();
                    hide();
                    Core.input.setOnscreenKeyboardVisible(false);
                };
                ti.canceled = this::hide;
                Core.input.getTextInput(ti);
            }else{
                chatfield.fireClick();
            }
        }else{
            //sending chat has a delay; same workaround as the native fragment
            Time.runTask(2f, () -> {
                scene.setKeyboardFocus(null);
                shown = false;
                scrollPos = 0;
                sendMessage();
            });
        }
    }

    public void hide(){
        scene.setKeyboardFocus(null);
        shown = false;
        clearChatInput();
    }

    private void sendMessage(){
        String message = chatfield.getText().trim();
        clearChatInput();
        if(message.isEmpty()) return;
        if(history.size < 2 || !history.get(1).equals(message)) history.insert(1, message);
        mesh.send(message);
    }

    public void clearChatInput(){
        historyPos = 0;
        history.set(0, "");
        chatfield.setText("");
        chatfield.setCursorPosition(0);
    }

    private void updateChat(){
        chatfield.setText(history.get(historyPos));
        chatfield.setCursorPosition(chatfield.getText().length());
    }

    public void addMessage(String message){
        if(message == null) return;
        messages.insert(0, message);

        fadetime += 1f;
        fadetime = Math.min(fadetime, messagesShown) + 1f;

        if(scrollPos > 0) scrollPos++;
    }

    /** Mirrors the native clearMessages: called on world load, where the game wipes pending chat. */
    public void clearMessages(){
        messages.clear();
        history.clear();
        history.insert(0, "");
    }

    private void rect(float x, float y, float w, float h){
        //prevents texture bindings; the string lookup is irrelevant as it is only called <10 times per frame, and maps are very fast anyway
        Draw.rect("whiteui", x + w / 2f, y + h / 2f, w, h);
    }

    @Override
    public void draw(){
        float opacity = Core.settings.getInt("chatopacity", 100) / 100f;
        float textWidth = Math.min(Core.graphics.getWidth() / 1.5f, Scl.scl(700f));

        Draw.color(shadowColor);

        if(shown){
            rect(offsetx, chatfield.y + scene.marginBottom, chatfield.getWidth() + 15f, chatfield.getHeight() - 1);
        }

        super.draw();

        float spacing = chatspace;

        chatfield.visible = shown;
        fieldlabel.visible = shown;

        Draw.color(shadowColor);
        Draw.alpha(shadowColor.a * opacity);

        float theight = offsety + spacing + getMarginBottom() + scene.marginBottom;
        for(int i = scrollPos; i < messages.size && i < messagesShown + scrollPos && (i < fadetime || shown); i++){
            String message = messages.get(i);

            layout.setText(Fonts.def, message, Color.white, textWidth, Align.bottomLeft, true);
            theight += layout.height + textspacing;
            if(i - scrollPos == 0) theight -= textspacing + 1;

            Fonts.def.getCache().clear();
            Fonts.def.getCache().setColor(Color.white);
            Fonts.def.getCache().addText(message, fontoffsetx + offsetx, offsety + theight, textWidth, Align.bottomLeft, true);

            if(!shown && fadetime - i < 1f && fadetime - i >= 0f){
                Fonts.def.getCache().setAlphas((fadetime - i) * opacity);
                Draw.color(0, 0, 0, shadowColor.a * (fadetime - i) * opacity);
            }else{
                Fonts.def.getCache().setAlphas(opacity);
            }

            rect(offsetx, theight - layout.height - 2, textWidth + Scl.scl(4f), layout.height + textspacing);
            Draw.color(shadowColor);
            Draw.alpha(opacity * shadowColor.a);

            Fonts.def.getCache().draw();
        }

        Draw.color();

        if(fadetime > 0 && !shown){
            fadetime -= Time.delta / 180f;
        }
    }
}
