package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChestScanner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDisplay = settings.createGroup("Display");
    
    // Settings
    private final Setting<Boolean> scanOnOpen = sgGeneral.add(new BoolSetting.Builder()
        .name("scan-on-open")
        .description("Automatically scan chest contents when you open a chest.")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> autoScan = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-scan")
        .description("Automatically scan chests when looking at them.")
        .defaultValue(false)
        .build()
    );
    
    private final Setting<Double> scanRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("scan-range")
        .description("Maximum range to scan chests.")
        .defaultValue(5.0)
        .range(1.0, 10.0)
        .sliderRange(1.0, 10.0)
        .build()
    );
    
    private final Setting<Boolean> showItemCount = sgDisplay.add(new BoolSetting.Builder()
        .name("show-count")
        .description("Show item stack counts.")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> showEnchantments = sgDisplay.add(new BoolSetting.Builder()
        .name("show-enchantments")
        .description("Show item enchantments.")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> showDurability = sgDisplay.add(new BoolSetting.Builder()
        .name("show-durability")
        .description("Show item durability for damageable items.")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> compactMode = sgDisplay.add(new BoolSetting.Builder()
        .name("compact-mode")
        .description("Group identical items together.")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> ignoreEmptySlots = sgDisplay.add(new BoolSetting.Builder()
        .name("ignore-empty")
        .description("Don't display empty slots in the output.")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> debugMode = sgDisplay.add(new BoolSetting.Builder()
        .name("debug-mode")
        .description("Show debug information for troubleshooting.")
        .defaultValue(false)
        .build()
    );
    
    // State tracking
    private BlockPos lastScannedChest = null;
    private int tickCounter = 0;
    private GenericContainerScreen pendingScreen = null;
    private int screenScanDelay = 0;
    
    public ChestScanner() {
        super(AddonTemplate.CATEGORY, "chest-scanner", "Scans and displays information about items in chests.");
    }
    
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        
        // Handle pending screen scan
        if (pendingScreen != null && screenScanDelay > 0) {
            screenScanDelay--;
            if (screenScanDelay == 0) {
                scanOpenContainer(pendingScreen);
                pendingScreen = null;
            }
        }
        
        // Only check every 10 ticks (0.5 seconds) to avoid spam
        if (++tickCounter < 10) return;
        tickCounter = 0;
        
        if (autoScan.get()) {
            checkForChestInCrosshair();
        }
    }
    
    @EventHandler
    private void onScreenOpen(OpenScreenEvent event) {
        if (!scanOnOpen.get() || mc.player == null) return;
        
        // Check if the opened screen is a chest/container screen
        if (event.screen instanceof GenericContainerScreen containerScreen) {
            // Schedule scanning for next few ticks to ensure screen is loaded
            pendingScreen = containerScreen;
            screenScanDelay = 3; // Wait 3 ticks (150ms) for screen to load
        }
    }
    
    private void scanOpenContainer(GenericContainerScreen screen) {
        if (mc.player == null) return;
        
        try {
            GenericContainerScreenHandler handler = screen.getScreenHandler();
            
            List<ItemStack> items = new ArrayList<>();
            
            // Get the container inventory size (rows * 9, typically 27 for chests)
            int containerRows = handler.getRows();
            int containerSize = containerRows * 9;
            
            if (debugMode.get()) {
                ChatUtils.info("Debug: Container has " + containerRows + " rows, size: " + containerSize);
            }
            
            // Scan the container slots (not player inventory)
            for (int i = 0; i < containerSize; i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (!stack.isEmpty()) {
                    items.add(stack);
                    if (debugMode.get()) {
                        ChatUtils.info("Debug: Found item in slot " + i + ": " + stack.getItem().getName().getString() + " x" + stack.getCount());
                    }
                } else if (!ignoreEmptySlots.get()) {
                    items.add(ItemStack.EMPTY);
                }
            }
            
            ChatUtils.info("=== Opened Container Contents ===");
            displayContainerContents(items);
            
        } catch (Exception e) {
            ChatUtils.error("Failed to scan opened container: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void displayContainerContents(List<ItemStack> items) {
        if (items.isEmpty()) {
            ChatUtils.info("Container is empty.");
            return;
        }
        
        if (compactMode.get()) {
            displayCompactContents(items);
        } else {
            displayDetailedContents(items);
        }
        
        ChatUtils.info("=== Total: %d items ===".formatted(items.size()));
    }
    
    private void checkForChestInCrosshair() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            return;
        }
        
        BlockHitResult blockHit = (BlockHitResult) mc.crosshairTarget;
        BlockPos pos = blockHit.getBlockPos();
        
        // Check if we're within range
        if (mc.player.getBlockPos().toCenterPos().distanceTo(pos.toCenterPos()) > scanRange.get()) {
            return;
        }
        
        // Check if this is a new chest or we haven't scanned this one recently
        if (pos.equals(lastScannedChest)) {
            return;
        }
        
        BlockEntity blockEntity = mc.world.getBlockEntity(pos);
        if (blockEntity instanceof ChestBlockEntity chestBlockEntity) {
            lastScannedChest = pos;
            scanChest(chestBlockEntity, pos);
        }
    }
    
    public void scanChest(ChestBlockEntity chest, BlockPos pos) {
        List<ItemStack> items = new ArrayList<>();
        
        // Get all items from the chest
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (!stack.isEmpty() || !ignoreEmptySlots.get()) {
                items.add(stack);
            }
        }
        
        displayChestContents(items, pos);
    }
    
    private void displayChestContents(List<ItemStack> items, BlockPos pos) {
        ChatUtils.info("=== Chest Contents at %d, %d, %d ===".formatted(pos.getX(), pos.getY(), pos.getZ()));
        
        if (items.isEmpty()) {
            ChatUtils.info("Chest is empty.");
            return;
        }
        
        if (compactMode.get()) {
            displayCompactContents(items);
        } else {
            displayDetailedContents(items);
        }
        
        ChatUtils.info("=== Total: %d items ===".formatted(items.size()));
    }
    
    private void displayCompactContents(List<ItemStack> items) {
        Map<String, ItemInfo> groupedItems = new HashMap<>();
        
        for (ItemStack stack : items) {
            if (stack.isEmpty()) continue;
            
            String itemName = stack.getItem().getName().getString();
            String key = itemName;
            
            // Include enchantments in the key for grouping if enabled
            if (showEnchantments.get() && stack.hasEnchantments()) {
                key += " " + getEnchantmentString(stack);
            }
            
            groupedItems.merge(key, new ItemInfo(itemName, stack.getCount(), stack), 
                (existing, newInfo) -> new ItemInfo(existing.name, existing.count + newInfo.count, existing.stack));
        }
        
        for (ItemInfo info : groupedItems.values()) {
            displayItemInfo(info.stack, info.count);
        }
    }
    
    private void displayDetailedContents(List<ItemStack> items) {
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            ChatUtils.info("Slot %d: %s".formatted(i + 1, formatItemStack(stack)));
        }
    }
    
    private void displayItemInfo(ItemStack stack, int totalCount) {
        ChatUtils.info(formatItemStack(stack, totalCount));
    }
    
    private String formatItemStack(ItemStack stack) {
        return formatItemStack(stack, stack.getCount());
    }
    
    private String formatItemStack(ItemStack stack, int count) {
        if (stack.isEmpty()) {
            return "Empty";
        }
        
        StringBuilder sb = new StringBuilder();
        
        // Item name and count
        sb.append(stack.getItem().getName().getString());
        
        if (showItemCount.get() && count > 1) {
            sb.append(" x").append(count);
        }
        
        // Durability
        if (showDurability.get() && stack.isDamageable()) {
            int durability = stack.getMaxDamage() - stack.getDamage();
            int maxDurability = stack.getMaxDamage();
            sb.append(" (").append(durability).append("/").append(maxDurability).append(")");
        }
        
        // Enchantments
        if (showEnchantments.get() && stack.hasEnchantments()) {
            sb.append(" [").append(getEnchantmentString(stack)).append("]");
        }
        
        return sb.toString();
    }
    
    private String getEnchantmentString(ItemStack stack) {
        List<String> enchants = new ArrayList<>();
        
        stack.getEnchantments().getEnchantments().forEach(enchantment -> {
            int level = stack.getEnchantments().getLevel(enchantment);
            String enchantName = enchantment.toString(); // Simplified for now
            enchants.add(enchantName + " " + level);
        });
        
        return String.join(", ", enchants);
    }
    
    // Helper class for grouping items
    private static class ItemInfo {
        final String name;
        final int count;
        final ItemStack stack;
        
        ItemInfo(String name, int count, ItemStack stack) {
            this.name = name;
            this.count = count;
            this.stack = stack;
        }
    }
    
    // Manual scan command that can be triggered
    public void manualScan() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            ChatUtils.error("Not looking at a block.");
            return;
        }
        
        BlockHitResult blockHit = (BlockHitResult) mc.crosshairTarget;
        BlockPos pos = blockHit.getBlockPos();
        
        if (mc.player.getBlockPos().toCenterPos().distanceTo(pos.toCenterPos()) > scanRange.get()) {
            ChatUtils.error("Chest is too far away (max range: %.1f blocks)".formatted(scanRange.get()));
            return;
        }
        
        BlockEntity blockEntity = mc.world.getBlockEntity(pos);
        if (!(blockEntity instanceof ChestBlockEntity chestBlockEntity)) {
            ChatUtils.error("Not looking at a chest.");
            return;
        }
        
        scanChest(chestBlockEntity, pos);
    }
}