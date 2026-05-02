package dev.errnicraft.chatimage.mixin;

import dev.errnicraft.chatimage.ChatImageClient;
import dev.errnicraft.chatimage.ChatImageConfig;
import dev.errnicraft.chatimage.ChatImageStore;
import dev.errnicraft.chatimage.ImageCache;
import dev.errnicraft.chatimage.ImageViewerScreen;
import dev.errnicraft.chatimage.PendingImageState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.util.List;

@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    private static final int CAM_BTN_W = 22;
    private static final int CAM_BTN_H = 16;

    private static final Identifier PLACEHOLDER_TEX =
        Identifier.fromNamespaceAndPath("chatimage", "textures/placeholder.png");

    @Shadow
    protected EditBox input;

    private boolean canSendPhoto() {
        return ChatImageConfig.INSTANCE.getServerHasModVersion() != null
            && !ChatImageConfig.INSTANCE.getUploadToken().isEmpty()
            && ChatImageConfig.INSTANCE.getServerReachable()
            && !ChatImageConfig.INSTANCE.getBanned()
            && ChatImageConfig.INSTANCE.cooldownRemainingMs() <= 0L;
    }

    /** Возвращает текст подсказки для кнопки 📷 в зависимости от состояния */
    private String getButtonHint() {
        if (ChatImageConfig.INSTANCE.getServerHasModVersion() == null) {
            return ChatImageConfig.INSTANCE.tr("chatimage.btn_no_server_mod");
        }
        if (ChatImageConfig.INSTANCE.getBanned()) {
            return ChatImageConfig.INSTANCE.tr("chatimage.btn_banned");
        }
        long cooldownMs = ChatImageConfig.INSTANCE.cooldownRemainingMs();
        if (cooldownMs > 0L) {
            long totalSec = (cooldownMs + 999L) / 1000L;
            if (totalSec >= 60L) {
                long m = totalSec / 60L;
                long s = totalSec % 60L;
                return ChatImageConfig.INSTANCE.tr("chatimage.cooldown_minutes", m, s);
            } else {
                return ChatImageConfig.INSTANCE.tr("chatimage.cooldown_seconds", totalSec);
            }
        }
        return ChatImageConfig.INSTANCE.tr("chatimage.attach");
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void chatimage$render(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ChatScreen self = (ChatScreen)(Object)this;
        Minecraft mc = Minecraft.getInstance();

        // ── 1. Кнопка камеры ──
        int inputBarTop = self.height - 14;
        int camBtnX = self.width - CAM_BTN_W - 2;
        int camBtnY = inputBarTop - CAM_BTN_H - 1;

        boolean canSend = canSendPhoto();
        boolean hoverCam = mouseX >= camBtnX && mouseX < camBtnX + CAM_BTN_W
            && mouseY >= camBtnY && mouseY < camBtnY + CAM_BTN_H;

        int camBgColor = !canSend ? 0xCC2A2A2A : (hoverCam ? 0xCC666666 : 0xCC333333);
        int camBorderColor = !canSend ? 0xFF444444 : 0xFF888888;
        int camIconColor = !canSend ? 0xFF666666 : 0xFFFFFFFF;

        graphics.fill(camBtnX, camBtnY, camBtnX + CAM_BTN_W, camBtnY + CAM_BTN_H, camBgColor);
        graphics.fill(camBtnX, camBtnY, camBtnX + CAM_BTN_W, camBtnY + 1, camBorderColor);
        graphics.fill(camBtnX, camBtnY + CAM_BTN_H - 1, camBtnX + CAM_BTN_W, camBtnY + CAM_BTN_H, camBorderColor);
        graphics.fill(camBtnX, camBtnY, camBtnX + 1, camBtnY + CAM_BTN_H, camBorderColor);
        graphics.fill(camBtnX + CAM_BTN_W - 1, camBtnY, camBtnX + CAM_BTN_W, camBtnY + CAM_BTN_H, camBorderColor);
        graphics.drawCenteredString(mc.font, "📷", camBtnX + CAM_BTN_W / 2, camBtnY + (CAM_BTN_H - mc.font.lineHeight) / 2, camIconColor);

        if (hoverCam) {
            String hint = getButtonHint();
            int hintColor = canSend ? 0xFFCCCCCC : 0xFFFF6666;
            int labelW = mc.font.width(hint) + 6;
            int labelX = Math.min(mouseX, self.width - labelW - 2);
            int labelY = camBtnY - mc.font.lineHeight - 3;
            graphics.fill(labelX, labelY - 1, labelX + labelW, labelY + mc.font.lineHeight + 1, 0xBB000000);
            graphics.drawString(mc.font, hint, labelX + 3, labelY, hintColor, false);
        }

        // ── 2. Превью выбранного фото ──
        PendingImageState.PendingImage pending = PendingImageState.getPending();
        if (pending == null) {
            chatimage$updateCursorForChatImages(graphics, mc, mouseX, mouseY, self.width);
            return;
        }

        boolean isLoaded = pending.isLoaded();
        Identifier tex = pending.getTextureId();

        int dispW = pending.getWidth();
        int dispH = pending.getHeight();

        int previewBottom = inputBarTop - 2;
        int previewTop    = previewBottom - dispH;
        int previewLeft   = 4;

        graphics.fill(
            previewLeft - 2, previewTop - 2,
            previewLeft + dispW + 2, previewBottom + 1,
            0xAA000000
        );

        if (!isLoaded || tex == null) {
            // ── Иконка-плейсхолдер: тёмный фон + символ изображения по центру ──
            // dispW x dispH уже зафиксированы — карточка не прыгает при загрузке
            graphics.fill(previewLeft, previewTop, previewLeft + dispW, previewBottom, 0xFF111111);

            // Рамка
            int borderColor = 0xFF444444;
            graphics.fill(previewLeft, previewTop, previewLeft + dispW, previewTop + 1, borderColor);
            graphics.fill(previewLeft, previewBottom - 1, previewLeft + dispW, previewBottom, borderColor);
            graphics.fill(previewLeft, previewTop, previewLeft + 1, previewBottom, borderColor);
            graphics.fill(previewLeft + dispW - 1, previewTop, previewLeft + dispW, previewBottom, borderColor);

            // Иконка 🖼 строго по центру карточки
            if (dispH >= 12) {
                // iconScale: ~40% высоты карточки, не больше 60% ширины, минимум 1
                float iconScale = dispH * 0.40f / 9.0f;
                iconScale = Math.min(iconScale, dispW * 0.60f / mc.font.lineHeight);
                iconScale = Math.max(iconScale, 1.0f);

                String icon = "🖼";
                int iconPxW = Math.round(mc.font.width(icon) * iconScale);
                int iconPxH = Math.round(mc.font.lineHeight * iconScale);

                // Центр карточки → верхний-левый угол иконки (целые числа, без float дрейфа)
                int iconX = previewLeft + (dispW - iconPxW) / 2;
                int iconY = previewTop  + (dispH - iconPxH) / 2;

                graphics.pose().pushMatrix();
                graphics.pose().translate(iconX, iconY);
                graphics.pose().scale(iconScale, iconScale);
                graphics.drawString(mc.font, icon, 0, 0, 0xFF888888, false);
                graphics.pose().popMatrix();
            }
        } else {
            int texW = pending.getTextureWidth();
            int texH = pending.getTextureHeight();

            // Вписываем текстуру в dispW x dispH с соблюдением aspect ratio
            float scaleX = (float) dispW / texW;
            float scaleY = (float) dispH / texH;
            float s = Math.min(scaleX, scaleY);
            int fitW = Math.max(1, Math.round(texW * s));
            int fitH = Math.max(1, Math.round(texH * s));
            int offsetX = (dispW - fitW) / 2;
            int offsetY = (dispH - fitH) / 2;

            graphics.pose().pushMatrix();
            graphics.pose().translate(previewLeft + offsetX, previewTop + offsetY);
            graphics.pose().scale(s, s);
            graphics.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0f, 0f, texW, texH, texW, texH, -1);
            graphics.pose().popMatrix();
        }

        // Кнопки ✕ и ✔
        int btnX    = previewLeft + dispW + 4;
        int cancelY = previewTop;
        int sendY   = cancelY + 20;

        boolean hoverCancel = mouseX >= btnX && mouseX < btnX + 18 && mouseY >= cancelY && mouseY < cancelY + 18;
        graphics.fill(btnX, cancelY, btnX + 18, cancelY + 18, hoverCancel ? 0xCCFF4444 : 0xCC661111);
        graphics.drawCenteredString(mc.font, "✕", btnX + 9, cancelY + 5, 0xFFFFFFFF);

        boolean hoverSend = mouseX >= btnX && mouseX < btnX + 18 && mouseY >= sendY && mouseY < sendY + 18;
        int sendColor = isLoaded ? (hoverSend ? 0xCC44FF44 : 0xCC116611) : 0xCC555555;
        graphics.fill(btnX, sendY, btnX + 18, sendY + 18, sendColor);
        graphics.drawCenteredString(mc.font, "✔", btnX + 9, sendY + 5, isLoaded ? 0xFFFFFFFF : 0xFF888888);

        chatimage$updateCursorForChatImages(graphics, mc, mouseX, mouseY, self.width);
    }

    private void chatimage$updateCursorForChatImages(GuiGraphics graphics, Minecraft mc, int mouseX, int mouseY, int screenWidth) {
        List<ChatImageStore.ImageMessage> msgs = ChatImageStore.getMessageList();
        for (ChatImageStore.ImageMessage msg : msgs) {
            if (msg.getDismissed() || !msg.hasScreenBounds()) continue;
            if (mouseX >= msg.getBoundsX0() && mouseX < msg.getBoundsX1()
                    && mouseY >= msg.getBoundsY0() && mouseY < msg.getBoundsY1()) {
                String hint = ChatImageConfig.INSTANCE.tr("chatimage.click_to_open");
                int labelW = mc.font.width(hint) + 6;
                int labelX = Math.min(mouseX + 8, screenWidth - labelW - 2);
                int labelY = mouseY - mc.font.lineHeight - 4;
                if (labelY < 0) labelY = mouseY + 12;
                graphics.fill(labelX, labelY - 1, labelX + labelW, labelY + mc.font.lineHeight + 1, 0xBB000000);
                graphics.drawString(mc.font, hint, labelX + 3, labelY, 0xFFCCCCCC, false);
                GLFW.glfwSetCursor(mc.getWindow().handle(),
                    GLFW.glfwCreateStandardCursor(GLFW.GLFW_POINTING_HAND_CURSOR));
                return;
            }
        }
        GLFW.glfwSetCursor(mc.getWindow().handle(), 0L);
    }

    /**
     * Ctrl+V: вставка изображения из буфера обмена.
     * Инжектируемся в HEAD с cancellable=true — до того как EditBox обработает событие.
     * Если в буфере есть картинка — перехватываем и передаём в ChatImageClient.
     * Если нет — пропускаем (EditBox вставит текст как обычно).
     *
     * Enter: отправка pending-фото.
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void chatimage$handleKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        // Ctrl+V — проверяем буфер в фоновом потоке, чтобы не блокировать рендер-поток.
        // Сразу перехватываем событие (cancel), затем фоновый поток решает:
        //   - картинка → pasteImageFromClipboard()
        //   - текст    → вставляем текст вручную через input.insertText()
        if (event.isPaste()) {
            if (!ChatImageClient.canSendPhoto(Minecraft.getInstance())) {
                // Мод отключён/нет токена — не перехватываем, EditBox вставит текст сам
                return;
            }
            cir.setReturnValue(true); // перехватываем немедленно, без блокировки
            Thread t = new Thread(() -> {
                boolean hasImage = chatimage$clipboardHasImage();
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    if (hasImage) {
                        ChatImageClient.pasteImageFromClipboard();
                    } else {
                        // В буфере текст — вставляем его вручную как делал бы EditBox
                        String text = mc.keyboardHandler.getClipboard();
                        if (text != null && !text.isEmpty() && input != null) {
                            input.insertText(text);
                        }
                    }
                });
            });
            t.setDaemon(true);
            t.setName("ChatImage-ClipboardCheck");
            t.start();
            return;
        }

        // Enter — отправить pending фото
        int key = event.key();
        if (key != GLFW.GLFW_KEY_ENTER && key != GLFW.GLFW_KEY_KP_ENTER) return;
        PendingImageState.PendingImage pending = PendingImageState.getPending();
        if (pending == null) return;

        if (!pending.isLoaded()) {
            cir.setReturnValue(true);
            return;
        }

        String caption = (input != null && !input.getValue().trim().isEmpty())
            ? input.getValue().trim()
            : null;

        ChatImageClient.sendPendingImageWithCaption(caption);
        if (input != null) input.setValue("");
        cir.setReturnValue(true);
    }

    private boolean chatimage$clipboardHasImage() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                // PowerShell: проверяем есть ли изображение в буфере.
                // Используем [System.Reflection.Assembly]::LoadWithPartialName вместо Add-Type
                // чтобы не делать JIT-компиляцию при каждом вызове.
                String script = "[System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms') | Out-Null; " +
                    "if ([System.Windows.Forms.Clipboard]::GetImage() -ne $null) { exit 0 } else { exit 1 }";
                Process proc = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true).start();
                return proc.waitFor() == 0;
            } else if (os.contains("mac")) {
                // osascript: пробуем получить PNG из буфера
                String script = "try\n  set x to the clipboard as «class PNGf»\n  return \"ok\"\non error\n  return \"no\"\nend try";
                Process proc = new ProcessBuilder("osascript", "-e", script)
                    .redirectErrorStream(true).start();
                String result = new String(proc.getInputStream().readAllBytes()).trim();
                proc.waitFor();
                return result.equals("ok");
            } else {
                // Linux: xclip проверяем доступные типы
                Process proc = new ProcessBuilder("xclip", "-selection", "clipboard", "-t", "TARGETS", "-o")
                    .redirectErrorStream(true).start();
                String targets = new String(proc.getInputStream().readAllBytes());
                proc.waitFor();
                return targets.contains("image/png") || targets.contains("image/jpeg");
            }
        } catch (Exception e) {
            System.out.println("[ChatImage] clipboardHasImage error: " + e);
            return false;
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void chatimage$handleMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != 0 && event.button() != 1) return;
        boolean isRightClick = event.button() == 1;

        double mx = event.x();
        double my = event.y();
        ChatScreen self = (ChatScreen)(Object)this;
        Minecraft mc = Minecraft.getInstance();

        // 1. Клик по кнопке камеры
        int inputBarTop = self.height - 14;
        int camBtnX = self.width - CAM_BTN_W - 2;
        int camBtnY = inputBarTop - CAM_BTN_H - 1;
        if (mx >= camBtnX && mx < camBtnX + CAM_BTN_W && my >= camBtnY && my < camBtnY + CAM_BTN_H) {
            if (!canSendPhoto()) {
                ChatImageClient.canSendPhoto(mc);
                cir.setReturnValue(true);
                return;
            }
            openFileDialog();
            cir.setReturnValue(true);
            return;
        }

        // 2. Клик по кнопкам превью
        PendingImageState.PendingImage pending = PendingImageState.getPending();
        if (pending != null) {
            int dispW = pending.getWidth();
            int dispH = pending.getHeight();
            int previewBottom = inputBarTop - 2;
            int previewTop    = previewBottom - dispH;
            int btnX          = 4 + dispW + 4;
            int cancelY       = previewTop;
            int sendY         = cancelY + 20;

            if (mx >= btnX && mx < btnX + 18 && my >= cancelY && my < cancelY + 18) {
                PendingImageState.clear();
                cir.setReturnValue(true);
                return;
            } else if (mx >= btnX && mx < btnX + 18 && my >= sendY && my < sendY + 18) {
                if (!pending.isLoaded()) { cir.setReturnValue(true); return; }
                String caption = (input != null && !input.getValue().trim().isEmpty())
                    ? input.getValue().trim() : null;
                ChatImageClient.sendPendingImageWithCaption(caption);
                if (input != null) input.setValue("");
                cir.setReturnValue(true);
                return;
            }
        }

        // 3. Клик по фото в чате
        List<ChatImageStore.ImageMessage> msgs = ChatImageStore.getMessageList();
        for (ChatImageStore.ImageMessage msg : msgs) {
            if (msg.getDismissed() || !msg.hasScreenBounds()) continue;
            if (mx >= msg.getBoundsX0() && mx < msg.getBoundsX1()
                    && my >= msg.getBoundsY0() && my < msg.getBoundsY1()) {

                if (isRightClick) {
                    // ПКМ → скопировать ID в буфер обмена
                    String imageId = msg.getImageId();
                    mc.keyboardHandler.setClipboard(imageId);
                    mc.gui.getChat().addMessage(
                        net.minecraft.network.chat.Component.literal(
                            "§8[ChatImage] §7" + ChatImageConfig.INSTANCE.tr("chatimage.id_copied", imageId, imageId)
                        )
                    );
                    cir.setReturnValue(true);
                    return;
                }

                // ЛКМ → открыть просмотр (только если фото загружено)
                Identifier tex = ImageCache.INSTANCE.getTexture(msg.getImageId());
                kotlin.Pair<Integer, Integer> size = ImageCache.INSTANCE.getSize(msg.getImageId());
                if (tex != null && size != null) {
                    kotlin.Pair<Integer, Integer> texSize = ImageCache.INSTANCE.getTexSize(msg.getImageId());
                    java.io.File originalFile = ChatImageStore.INSTANCE.getOriginalFile(msg.getImageId());
                    int w = texSize != null ? texSize.getFirst()  : size.getFirst();
                    int h = texSize != null ? texSize.getSecond() : size.getSecond();
                    mc.setScreen(new ImageViewerScreen(tex, msg.getImageId(), w, h, originalFile));
                    cir.setReturnValue(true);
                    return;
                }
            }
        }
    }

    @Inject(method = "onClose", at = @At("TAIL"))
    private void chatimage$onClose(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        GLFW.glfwSetCursor(mc.getWindow().handle(), 0L);
    }

    private void openFileDialog() {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.getChat().preserveCurrentChatScreen();
        mc.setScreen(null);

        Thread t = new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            try {
                String path;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer filters = stack.mallocPointer(7);
                    filters.put(stack.UTF8("*.png"));
                    filters.put(stack.UTF8("*.jpg"));
                    filters.put(stack.UTF8("*.jpeg"));
                    filters.put(stack.UTF8("*.webp"));
                    filters.put(stack.UTF8("*.bmp"));
                    filters.put(stack.UTF8("*.tiff"));
                    filters.put(stack.UTF8("*.tif"));
                    filters.flip();
                    path = TinyFileDialogs.tinyfd_openFileDialog(
                            ChatImageConfig.INSTANCE.tr("chatimage.select_image"),
                            "", filters, "Image Files (*.png, *.jpg, *.jpeg, *.webp, *.bmp, *.tiff)", false
                    );
                }
                if (path != null) {
                    File file = new File(path);
                    if (file.exists() && file.length() <= 10L * 1024 * 1024) {
                        mc.execute(() -> {
                            ChatScreen restored = mc.gui.getChat().restoreChatScreen();
                            mc.setScreen(restored != null ? restored : new ChatScreen("", false));
                            ChatImageClient.stageImage(file);
                        });
                    } else {
                        mc.execute(() -> {
                            ChatScreen restored = mc.gui.getChat().restoreChatScreen();
                            mc.setScreen(restored != null ? restored : new ChatScreen("", false));
                            mc.gui.getChat().addMessage(Component.literal(
                                "§c[ChatImage] " + ChatImageConfig.INSTANCE.tr("chatimage.file_too_large")
                            ));
                        });
                    }
                } else {
                    mc.execute(() -> {
                        ChatScreen restored = mc.gui.getChat().restoreChatScreen();
                        mc.setScreen(restored != null ? restored : new ChatScreen("", false));
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
                mc.execute(() -> {
                    ChatScreen restored = mc.gui.getChat().restoreChatScreen();
                    mc.setScreen(restored != null ? restored : new ChatScreen("", false));
                });
            }
        });
        t.setDaemon(true);
        t.setName("ChatImage-FileDialog");
        t.start();
    }
}
