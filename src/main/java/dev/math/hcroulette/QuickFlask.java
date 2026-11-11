package dev.math.hcroulette;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

/**
 * Frasco Rapido: pocao (de beber) + pepita de cobre na mesa de trabalho. Bebe em 0,5 s em vez de 1,6 s,
 * mas e mais fraco: metade da duracao e um nivel a menos. Cura instantanea I vira regeneracao II por 4 s.
 */
final class QuickFlask implements Listener {

    static final float DRINK_SECONDS = 0.5f;
    private final NamespacedKey recipeKey, flagKey;

    QuickFlask(HardcoreRoulette plugin) {
        recipeKey = new NamespacedKey(plugin, "frasco_rapido");
        flagKey = new NamespacedKey(plugin, "frasco_rapido");
        ItemStack icon = new ItemStack(Material.POTION);
        PotionMeta pm = (PotionMeta) icon.getItemMeta();
        pm.displayName(Component.text("Frasco Rapido", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        icon.setItemMeta(pm);
        ShapelessRecipe r = new ShapelessRecipe(recipeKey, icon);
        r.addIngredient(Material.POTION);
        r.addIngredient(Material.COPPER_NUGGET);
        Bukkit.removeRecipe(recipeKey);
        Bukkit.addRecipe(r);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof ShapelessRecipe sr) || !sr.getKey().equals(recipeKey)) return;
        ItemStack potion = null;
        for (ItemStack it : e.getInventory().getMatrix()) if (it != null && it.getType() == Material.POTION) potion = it;
        e.getInventory().setResult(potion == null ? null : flask(potion));
    }

    /** Monta o frasco a partir da pocao; null se ela nao tiver efeito ou ja for frasco. */
    ItemStack flask(ItemStack potion) {
        if (!(potion.getItemMeta() instanceof PotionMeta src)) return null;
        if (src.getPersistentDataContainer().has(flagKey)) return null;
        List<PotionEffect> base = new ArrayList<>();
        if (src.getBasePotionType() != null) base.addAll(src.getBasePotionType().getPotionEffects());
        base.addAll(src.getCustomEffects());
        if (base.isEmpty()) return null; // agua, estranha, mundana...

        List<PotionEffect> weak = new ArrayList<>();
        for (PotionEffect pe : base) {
            PotionEffectType t = pe.getType();
            if (t.isInstant()) {
                if (pe.getAmplifier() > 0) weak.add(new PotionEffect(t, 1, pe.getAmplifier() - 1));
                else if (t == PotionEffectType.INSTANT_HEALTH) weak.add(new PotionEffect(PotionEffectType.REGENERATION, 80, 1));
                else weak.add(new PotionEffect(t, 1, 0));
            } else {
                weak.add(new PotionEffect(t, Math.max(20, pe.getDuration() / 2), Math.max(0, pe.getAmplifier() - 1),
                        pe.isAmbient(), pe.hasParticles(), pe.hasIcon()));
            }
        }

        ItemStack out = new ItemStack(Material.POTION);
        PotionMeta pm = (PotionMeta) out.getItemMeta();
        Color color = src.hasColor() ? src.getColor() : base.get(0).getType().getColor();
        pm.setColor(color);
        for (PotionEffect pe : weak) pm.addCustomEffect(pe, true);
        pm.displayName(Component.text("Frasco Rapido", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        pm.lore(List.of(
                Component.text("Bebe em meio segundo,", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("mas e mais fraco que a pocao.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        pm.getPersistentDataContainer().set(flagKey, PersistentDataType.BYTE, (byte) 1);
        out.setItemMeta(pm);
        out.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                .consumeSeconds(DRINK_SECONDS)
                .animation(ItemUseAnimation.DRINK)
                .sound(Key.key("entity.generic.drink"))
                .hasConsumeParticles(false)
                .build());
        return out;
    }
}
