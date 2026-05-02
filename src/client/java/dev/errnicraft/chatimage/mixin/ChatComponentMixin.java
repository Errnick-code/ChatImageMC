package dev.errnicraft.chatimage.mixin;

import dev.errnicraft.chatimage.ChatImageStore;
import dev.errnicraft.chatimage.ImageCache;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ChatComponent.class)
public class ChatComponentMixin {

    private static final int IMG_LEFT_MARGIN = 2;
    private static final int IMG_VERT_PAD = 2;     // зазор снизу фото (от фото до ника)
    private static final int IMG_TOP_GAP  = 2;     // зазор сверху фото (от предыдущей строки)

    @Inject(method = "render", at = @At("TAIL"))
    private void chatimage$renderImages(
        GuiGraphics guiGraphics, Font font, int ticks, int mouseX, int mouseY,
        boolean isChatting, boolean changeCursorOnInsertions,
        CallbackInfo ci
    ) {
        Minecraft mc = Minecraft.getInstance();
        List<ChatImageStore.ImageMessage> msgs = ChatImageStore.getMessageList();
        if (msgs.isEmpty()) return;

        float scale = mc.options.chatScale().get().floatValue();
        if (scale < 0.01f) scale = 1f;
        double chatLineSpacing = mc.options.chatLineSpacing().get();
        int entryHeight = (int)(9.0 * (chatLineSpacing + 1.0));
        int chatWidthPx = Mth.floor(mc.options.chatWidth().get() * 280.0 + 40.0);
        int chatBottom = Mth.floor((guiGraphics.guiHeight() - 40) / scale);

        ChatComponentAccessor accessor = (ChatComponentAccessor)(Object) this;
        List<GuiMessage.Line> trimmed = accessor.getTrimmedMessages();
        int scrollPos = accessor.getChatScrollbarPos();
        int linesPerPage = ((ChatComponent)(Object) this).getLinesPerPage();

        int chatBottomGui = guiGraphics.guiHeight() - 40;
        int chatTopGui = chatBottomGui - (int)(linesPerPage * entryHeight * scale);

        for (ChatImageStore.ImageMessage msg : msgs) {
            if (msg.getDismissed()) continue;

            kotlin.Pair<Integer, Integer> size = ImageCache.INSTANCE.getSize(msg.getImageId());
            if (size == null) continue;  // карточка ещё не зарегистрирована

            int dispW = size.getFirst();
            int dispH = size.getSecond();

            Identifier tex = ImageCache.INSTANCE.getTexture(msg.getImageId()); // null пока грузит
            boolean isLoaded = tex != null;

            kotlin.Pair<Integer, Integer> orig = ImageCache.INSTANCE.getOrigSize(msg.getImageId());
            if (orig == null) continue;
            int origW = orig.getFirst();
            int origH = orig.getSecond();

            int msgLineIndex = -1;
            for (int i = 0; i < trimmed.size(); i++) {
                GuiMessage.Line line = trimmed.get(i);
                if (line.addedTime() == msg.getAddedTime() && line.endOfEntry()) {
                    msgLineIndex = i;
                    break;
                }
            }
            if (msgLineIndex == -1) { msg.setScreenBounds(0, 0, 0, 0); continue; }

            int lineIndexFromBottom = msgLineIndex - scrollPos;
            if (lineIndexFromBottom < 0 || lineIndexFromBottom >= linesPerPage) {
                msg.setScreenBounds(0, 0, 0, 0);
                continue;
            }

            // Alpha как у ванильного чата
            float alpha;
            if (isChatting) {
                alpha = mc.options.chatOpacity().get().floatValue() * 0.9f + 0.1f;
            } else {
                double t = (ticks - msg.getAddedTime()) / 200.0;
                t = (1.0 - t) * 10.0;
                t = Math.max(0.0, Math.min(1.0, t));
                alpha = (float)(t * t) * (mc.options.chatOpacity().get().floatValue() * 0.9f + 0.1f);
                if (alpha <= 1e-5f) { msg.setScreenBounds(0, 0, 0, 0); continue; }
            }

            int alphaInt  = (int)(alpha * 255) << 24;
            int blitColor = alphaInt | 0x00FFFFFF;

            // chatBottom - lineIndexFromBottom*entryHeight = нижний край строки с ником
            // Фото находится ВЫШЕ строки с ником — занимает extraLines строк над ней
            // lineIndexFromBottom+1 = строка сразу над ником, +extraLines = верх слота
            int chatTopScaled = chatBottom - linesPerPage * entryHeight;

            // Строка с ником: от lineIndexFromBottom до lineIndexFromBottom+1
            // Фото идёт выше строки с ником, с зазором IMG_VERT_PAD снизу и сверху
            int nickLineBottom = chatBottom - lineIndexFromBottom * entryHeight;
            int nickLineTop    = nickLineBottom - entryHeight;

            // imgBottom = низ строки ника минус зазор (фото ниже строки ника)
            int imgBottom = nickLineBottom - IMG_VERT_PAD;
            int imgTop    = imgBottom      - dispH + IMG_TOP_GAP; // сдвигаем вниз — зазор сверху

            // Фото полностью выше видимой области чата — скрываем
            if (imgBottom <= chatTopScaled) { msg.setScreenBounds(0, 0, 0, 0); continue; }
            // Фото полностью ниже видимой области — скрываем
            if (imgTop >= chatBottom)       { msg.setScreenBounds(0, 0, 0, 0); continue; }

            // Частичная видимость — clamp с обеих сторон
            int clampedImgTop    = Math.max(imgTop,    chatTopScaled);
            int clampedImgBottom = Math.min(imgBottom, chatBottom);
            if (clampedImgBottom <= clampedImgTop) { msg.setScreenBounds(0, 0, 0, 0); continue; }

            int drawH = clampedImgBottom - clampedImgTop;

            guiGraphics.enableScissor(
                (int)(4 * scale), Math.max(0, chatTopGui),
                (int)(4 * scale) + (int)(chatWidthPx * scale), chatBottomGui
            );

            guiGraphics.pose().pushMatrix();
            guiGraphics.pose().scale(scale, scale);
            guiGraphics.pose().translate(4.0f, 0.0f);

            if (!isLoaded) {
                boolean isDeleted = ImageCache.INSTANCE.isDeleted(msg.getImageId());
                boolean isError   = ImageCache.INSTANCE.isError(msg.getImageId());

                int bgColor;
                int borderColor;
                if (isDeleted) {
                    bgColor     = ((int)(alpha * 180) << 24) | 0x00110000;
                    borderColor = ((int)(alpha * 160) << 24) | 0x00662222;
                } else if (isError) {
                    bgColor     = ((int)(alpha * 180) << 24) | 0x00111100;
                    borderColor = ((int)(alpha * 160) << 24) | 0x00664400;
                } else {
                    bgColor     = ((int)(alpha * 200) << 24) | 0x00111111;
                    borderColor = ((int)(alpha * 160) << 24) | 0x00444444;
                }

                guiGraphics.fill(
                    IMG_LEFT_MARGIN, clampedImgTop,
                    IMG_LEFT_MARGIN + dispW, clampedImgBottom,
                    bgColor
                );
                // Рамка
                guiGraphics.fill(IMG_LEFT_MARGIN, clampedImgTop, IMG_LEFT_MARGIN + dispW, clampedImgTop + 1, borderColor);
                guiGraphics.fill(IMG_LEFT_MARGIN, clampedImgBottom - 1, IMG_LEFT_MARGIN + dispW, clampedImgBottom, borderColor);
                guiGraphics.fill(IMG_LEFT_MARGIN, clampedImgTop, IMG_LEFT_MARGIN + 1, clampedImgBottom, borderColor);
                guiGraphics.fill(IMG_LEFT_MARGIN + dispW - 1, clampedImgTop, IMG_LEFT_MARGIN + dispW, clampedImgBottom, borderColor);

                // ── Иконка по центру ──────────────────────────────────────────
                if (dispH >= 12) {
                    int cardCenterX = IMG_LEFT_MARGIN + dispW / 2;
                    int cardCenterY = imgTop + dispH / 2;

                    if (isError) {
                        // ── ⚠ знак предупреждения ────────────────────────────
                        String icon = "⚠";
                        int iconTint = ((int)(alpha * 255) << 24) | 0x00FFAA00;
                        float iconScale = dispH * 0.40f / 9.0f;
                        iconScale = Math.min(iconScale, dispW * 0.60f / mc.font.lineHeight);
                        iconScale = Math.max(iconScale, 1.0f);
                        int iconPxW = Math.round(mc.font.width(icon) * iconScale);
                        int iconPxH = Math.round(mc.font.lineHeight * iconScale);
                        int iconX = cardCenterX - iconPxW / 2;
                        int iconY = cardCenterY - iconPxH / 2;
                        if (iconY < clampedImgBottom && iconY + iconPxH > clampedImgTop) {
                            guiGraphics.pose().pushMatrix();
                            guiGraphics.pose().translate(iconX, iconY);
                            guiGraphics.pose().scale(iconScale, iconScale);
                            guiGraphics.drawString(mc.font, icon, 0, 0, iconTint, false);
                            guiGraphics.pose().popMatrix();
                        }
                    } else {
                        // ── Обычная текстовая иконка (🖼 или ✗) ──────────────
                        String icon = isDeleted ? "✗" : "🖼";
                        int iconTint = isDeleted
                            ? (((int)(alpha * 255) << 24) | 0x00AA4444)
                            : (((int)(alpha * 255) << 24) | 0x00888888);

                        float iconScale = dispH * 0.40f / 9.0f;
                        iconScale = Math.min(iconScale, dispW * 0.60f / mc.font.lineHeight);
                        iconScale = Math.max(iconScale, 1.0f);

                        int iconPxW = Math.round(mc.font.width(icon) * iconScale);
                        int iconPxH = Math.round(mc.font.lineHeight * iconScale);
                        int iconX = cardCenterX - iconPxW / 2;
                        int iconY = cardCenterY - iconPxH / 2;

                        if (iconY < clampedImgBottom && iconY + iconPxH > clampedImgTop) {
                            guiGraphics.pose().pushMatrix();
                            guiGraphics.pose().translate(iconX, iconY);
                            guiGraphics.pose().scale(iconScale, iconScale);
                            guiGraphics.drawString(mc.font, icon, 0, 0, iconTint, false);
                            guiGraphics.pose().popMatrix();
                        }
                    }
                }
            } else {
                // ── Рендер загруженной текстуры — масштабируется точно в dispW x dispH ──
                float scaleX = (float) dispW / origW;
                float scaleY = (float) dispH / origH;

                float vOffsetOrig = (imgTop < chatTopScaled)
                    ? (float)(chatTopScaled - imgTop) / scaleY
                    : 0f;
                vOffsetOrig = Math.max(0f, vOffsetOrig);
                int drawHOrig = Math.round(drawH / scaleY);

                guiGraphics.pose().pushMatrix();
                guiGraphics.pose().translate(IMG_LEFT_MARGIN, clampedImgTop);
                guiGraphics.pose().scale(scaleX, scaleY);

                guiGraphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    tex,
                    0, 0,
                    0.0f, vOffsetOrig,
                    origW, drawHOrig,
                    origW, origH,
                    blitColor
                );

                guiGraphics.pose().popMatrix();
            }

            guiGraphics.pose().popMatrix();
            guiGraphics.disableScissor();

            int guiX0 = (int)((IMG_LEFT_MARGIN + 4) * scale);
            int guiX1 = (int)((IMG_LEFT_MARGIN + 4 + dispW) * scale);
            int guiY0 = chatBottomGui - (int)((chatBottom - clampedImgTop)    * scale);
            int guiY1 = chatBottomGui - (int)((chatBottom - clampedImgBottom) * scale);
            msg.setScreenBounds(guiX0, guiY0, guiX1, guiY1);
        }
    }
}
