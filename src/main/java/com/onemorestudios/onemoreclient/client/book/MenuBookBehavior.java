package com.onemorestudios.onemoreclient.client.book;

import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.client.StationView;
import com.pockyl.diegesis.api.event.SceneEvent;

import net.minecraft.world.item.ItemStack;

/** The menu book up close: Hands On Delight's recipe book over the table, with the menu on its pages; everything goes to the book. */
public final class MenuBookBehavior implements StationBehavior {
    private final RecipeBook book = new RecipeBook(new MenuBookSource());

    @Override
    public void onSceneReady(StationView view) {
        view.node("book_open").ifPresent(node -> node.setPainter(book));
    }

    @Override
    public void onOpen(StationView view) {
        book.opened(view);
    }

    @Override
    public void onClose(StationView view) {
        book.shut(view);
    }

    @Override
    public void frame(StationView view, float dt) {
        book.frame(view, dt);
    }

    @Override
    public void onEvent(StationView view, SceneEvent event) {
        if (event instanceof SceneEvent.Click click && click.node().equals("book_open")) book.click(view, click.button());
    }

    @Override
    public boolean onKey(StationView view, int keyCode, int scanCode, int modifiers) {
        return book.key(view, keyCode, modifiers);
    }

    @Override
    public boolean onChar(StationView view, char character, int modifiers) {
        return book.typed(view, character);
    }

    @Override
    public boolean onScroll(StationView view, String node, double amount) {
        return "book_open".equals(node) && book.scroll(amount);
    }

    @Override
    public boolean onItemClick(StationView view, String node, ItemStack stack, int button, boolean shift) {
        if (!"book_open".equals(node)) return false;
        book.click(view, button);
        return true;
    }
}
