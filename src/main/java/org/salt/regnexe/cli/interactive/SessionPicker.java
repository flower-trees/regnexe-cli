package org.salt.regnexe.cli.interactive;

import java.util.*;
import java.util.function.Consumer;

/** Transient name-only picker. Selecting a name does not acquire or change session ownership. */
public final class SessionPicker {
    public record Menu(List<String> names, int selected) {
        public List<String> lines(int maxRows) {
            int count = Math.max(1, maxRows);
            int start = Math.max(0, Math.min(selected - count / 2, names.size() - count));
            List<String> lines = new ArrayList<>();
            for (int i = start; i < Math.min(names.size(), start + count); i++) {
                lines.add((i == selected ? "> " : "  ") + names.get(i));
            }
            return List.copyOf(lines);
        }
    }
    private Menu menu;
    private String chosen;
    private Consumer<Menu> view = value -> {};
    public void setView(Consumer<Menu> view) { this.view = view; view.accept(menu); }
    public boolean open(List<String> names, String currentName) {
        chosen = null;
        if (names.isEmpty()) { cancel(); return false; }
        int selected = Math.max(0, names.indexOf(currentName));
        menu = new Menu(List.copyOf(names), selected); view.accept(menu); return true;
    }
    public boolean move(int delta) {
        if (menu == null) return false;
        menu = new Menu(menu.names(), Math.floorMod(menu.selected() + delta, menu.names().size()));
        view.accept(menu); return true;
    }
    public boolean acceptSelection(boolean inputEmpty) {
        if (menu == null || !inputEmpty) return false;
        chosen = menu.names().get(menu.selected()); cancel(); return true;
    }
    public String takeSelection() { String name = chosen; chosen = null; return name; }
    public boolean cancel() {
        if (menu == null) return false;
        menu = null; view.accept(null); return true;
    }
}
