package dev.errnicraft.chatimage;

/**
 * Хранит состояние оверлея drag-and-drop.
 * Устанавливается из DragDropHandler, читается в ChatScreenMixin.
 */
public class DragDropOverlay {
    private static volatile boolean active = false;

    public static boolean isActive() { return active; }
    public static void setActive(boolean v) { active = v; }
}
