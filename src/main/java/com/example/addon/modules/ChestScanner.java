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
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChestScanner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDisplay = settings.createGroup("Display");
    private final SettingGroup sgSaving = settings.createGroup("Saving");
    
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
    
    private final Setting<Boolean> showLore = sgDisplay.add(new BoolSetting.Builder()
        .name("show-lore")
        .description("Show item lore and description text.")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> showNBT = sgDisplay.add(new BoolSetting.Builder()
        .name("show-nbt")
        .description("Show raw NBT data for items.")
        .defaultValue(false)
        .build()
    );
    
    private final Setting<Boolean> showAttributes = sgDisplay.add(new BoolSetting.Builder()
        .name("show-attributes")
        .description("Show item attributes and modifiers.")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> showRarity = sgDisplay.add(new BoolSetting.Builder()
        .name("show-rarity")
        .description("Show item rarity level.")
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
    
    // Saving Settings
    private final Setting<Boolean> saveToFile = sgSaving.add(new BoolSetting.Builder()
        .name("save-to-file")
        .description("Save scan results to files.")
        .defaultValue(false)
        .build()
    );
    
    private final Setting<SaveFormat> saveFormat = sgSaving.add(new EnumSetting.Builder<SaveFormat>()
        .name("save-format")
        .description("Format to save scan data in.")
        .defaultValue(SaveFormat.JSON)
        .build()
    );
    
    private final Setting<Boolean> includeTimestamp = sgSaving.add(new BoolSetting.Builder()
        .name("include-timestamp")
        .description("Include timestamp in saved data.")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<String> saveDirectory = sgSaving.add(new StringSetting.Builder()
        .name("save-directory")
        .description("Directory to save scan files to.")
        .defaultValue("chest_scans")
        .build()
    );
    
    private final Setting<Boolean> singleFile = sgSaving.add(new BoolSetting.Builder()
        .name("single-file")
        .description("Save all scans to one CSV file instead of separate files.")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<String> singleFileName = sgSaving.add(new StringSetting.Builder()
        .name("single-file-name")
        .description("Name of the single CSV file (without extension).")
        .defaultValue("all_chest_scans")
        .build()
    );
    
    public enum SaveFormat {
        JSON, CSV, TXT
    }
    
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
        ChatUtils.info("=== Opened Container Contents ===");
        
        if (items.isEmpty()) {
            ChatUtils.info("Container is empty.");
            if (saveToFile.get()) {
                String scanData = formatScanResults(items, null, "Container");
                saveToFile(scanData, null, "container");
            }
            return;
        }
        
        if (compactMode.get()) {
            displayCompactContents(items);
        } else {
            displayDetailedContents(items);
        }
        
        ChatUtils.info("=== Total: %d items ===".formatted(items.size()));
        
        // Save to file if enabled
        if (saveToFile.get()) {
            String scanData = formatScanResults(items, null, "Container");
            saveToFile(scanData, null, "container");
        }
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
            if (saveToFile.get()) {
                String scanData = formatScanResults(items, pos, "Chest");
                saveToFile(scanData, pos, "chest");
            }
            return;
        }
        
        if (compactMode.get()) {
            displayCompactContents(items);
        } else {
            displayDetailedContents(items);
        }
        
        ChatUtils.info("=== Total: %d items ===".formatted(items.size()));
        
        // Save to file if enabled
        if (saveToFile.get()) {
            String scanData = formatScanResults(items, pos, "Chest");
            saveToFile(scanData, pos, "chest");
        }
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
        
        // Item name and rarity
        String itemName = stack.getItem().getName().getString();
        if (showRarity.get()) {
            String rarity = stack.getRarity().toString().toLowerCase();
            sb.append("[").append(rarity.toUpperCase()).append("] ");
        }
        sb.append(itemName);
        
        // Count
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
        
        // Display detailed information on separate lines
        displayDetailedItemInfo(stack);
        
        return sb.toString();
    }
    
    private void displayDetailedItemInfo(ItemStack stack) {
        if (stack.isEmpty()) return;
        
        // Lore
        if (showLore.get()) {
            displayItemLore(stack);
        }
        
        // Attributes
        if (showAttributes.get()) {
            displayItemAttributes(stack);
        }
        
        // NBT data
        if (showNBT.get()) {
            displayItemNBT(stack);
        }
    }
    
    private void displayItemLore(ItemStack stack) {
        try {
            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore != null && !lore.lines().isEmpty()) {
                ChatUtils.info("  Lore:");
                for (Text line : lore.lines()) {
                    ChatUtils.info("    " + line.getString());
                }
            }
            
            // Also check for custom name
            Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
            if (customName != null) {
                ChatUtils.info("  Custom Name: " + customName.getString());
            }
        } catch (Exception e) {
            if (debugMode.get()) {
                ChatUtils.error("Error reading lore: " + e.getMessage());
            }
        }
    }
    
    private void displayItemAttributes(ItemStack stack) {
        try {
            AttributeModifiersComponent attributes = stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            if (attributes != null && !attributes.modifiers().isEmpty()) {
                ChatUtils.info("  Attributes:");
                attributes.modifiers().forEach(modifier -> {
                    String attributeName = modifier.attribute().toString();
                    double value = modifier.modifier().value();
                    String operation = modifier.modifier().operation().toString();
                    ChatUtils.info("    " + attributeName + ": " + value + " (" + operation + ")");
                });
            }
        } catch (Exception e) {
            if (debugMode.get()) {
                ChatUtils.error("Error reading attributes: " + e.getMessage());
            }
        }
    }
    
    private void displayItemNBT(ItemStack stack) {
        try {
            NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
            if (customData != null) {
                NbtCompound nbt = customData.copyNbt();
                if (!nbt.isEmpty()) {
                    ChatUtils.info("  NBT Data:");
                    for (String key : nbt.getKeys()) {
                        ChatUtils.info("    " + key + ": " + nbt.get(key).toString());
                    }
                }
            }
        } catch (Exception e) {
            if (debugMode.get()) {
                ChatUtils.error("Error reading NBT: " + e.getMessage());
            }
        }
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
    
    // Test function to verify save functionality
    public void testSaveFunction() {
        ChatUtils.info("Testing save functionality...");
        
        try {
            // Create test data
            List<ItemStack> testItems = new ArrayList<>();
            testItems.add(new ItemStack(net.minecraft.item.Items.DIAMOND_SWORD));
            testItems.add(new ItemStack(net.minecraft.item.Items.IRON_INGOT, 64));
            
            // Test data formatting
            String testData = formatScanResults(testItems, new BlockPos(0, 64, 0), "Test");
            
            // Test save function
            saveToFile(testData, new BlockPos(0, 64, 0), "test");
            
            ChatUtils.info("Save function test completed!");
            
        } catch (Exception e) {
            ChatUtils.error("Save function test failed: " + e.getMessage());
            if (debugMode.get()) {
                e.printStackTrace();
            }
        }
    }
    
    // Show where files are being saved
    public void showSaveLocation() {
        try {
            Path saveDir = Paths.get(saveDirectory.get());
            Path absolutePath = saveDir.toAbsolutePath();
            
            ChatUtils.info("=== Save Location Info ===");
            ChatUtils.info("Save directory setting: " + saveDirectory.get());
            ChatUtils.info("Relative path: " + saveDir.toString());
            ChatUtils.info("Absolute path: " + absolutePath.toString());
            ChatUtils.info("Directory exists: " + Files.exists(saveDir));
            ChatUtils.info("Save format: " + saveFormat.get().toString());
            ChatUtils.info("Save enabled: " + saveToFile.get());
            ChatUtils.info("Single file mode: " + singleFile.get());
            if (singleFile.get()) {
                ChatUtils.info("Single file name: " + singleFileName.get() + ".csv");
            }
            
            if (!Files.exists(saveDir)) {
                ChatUtils.info("Directory will be created when first file is saved.");
            } else {
                // List existing files
                try {
                    long fileCount = Files.list(saveDir).count();
                    ChatUtils.info("Files in directory: " + fileCount);
                } catch (Exception e) {
                    ChatUtils.info("Could not count files in directory.");
                }
            }
            
        } catch (Exception e) {
            ChatUtils.error("Error checking save location: " + e.getMessage());
            if (debugMode.get()) {
                e.printStackTrace();
            }
        }
    }
    
    // Saving functionality
    private String formatScanResults(List<ItemStack> items, BlockPos pos, String containerType) {
        StringBuilder result = new StringBuilder();
        
        if (includeTimestamp.get()) {
            result.append("Timestamp: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
        }
        
        if (pos != null) {
            result.append("Location: ").append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ()).append("\n");
        }
        
        result.append("Container Type: ").append(containerType).append("\n");
        result.append("Items Found: ").append(items.size()).append("\n\n");
        
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() && ignoreEmptySlots.get()) continue;
            
            result.append("Slot ").append(i + 1).append(": ");
            
            if (saveFormat.get() == SaveFormat.JSON) {
                result.append(formatItemAsJSON(stack));
            } else if (saveFormat.get() == SaveFormat.CSV) {
                result.append(formatItemAsCSV(stack));
            } else {
                result.append(formatItemAsText(stack));
            }
            result.append("\n");
        }
        
        return result.toString();
    }
    
    private String formatItemAsJSON(ItemStack stack) {
        if (stack.isEmpty()) return "{\"empty\": true}";
        
        StringBuilder json = new StringBuilder("{");
        json.append("\"name\": \"").append(stack.getItem().getName().getString().replace("\"", "\\\"")).append("\", ");
        json.append("\"count\": ").append(stack.getCount()).append(", ");
        json.append("\"rarity\": \"").append(stack.getRarity().toString()).append("\", ");
        
        if (stack.isDamageable()) {
            json.append("\"durability\": {\"current\": ").append(stack.getMaxDamage() - stack.getDamage())
                .append(", \"max\": ").append(stack.getMaxDamage()).append("}, ");
        }
        
        if (stack.hasEnchantments()) {
            json.append("\"enchantments\": \"").append(getEnchantmentString(stack).replace("\"", "\\\"")).append("\", ");
        }
        
        // Add NBT data
        try {
            NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
            if (customData != null && !customData.copyNbt().isEmpty()) {
                json.append("\"nbt\": \"").append(customData.copyNbt().toString().replace("\"", "\\\"")).append("\", ");
            }
        } catch (Exception ignored) {}
        
        // Add lore
        try {
            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore != null && !lore.lines().isEmpty()) {
                json.append("\"lore\": [");
                for (int i = 0; i < lore.lines().size(); i++) {
                    if (i > 0) json.append(", ");
                    json.append("\"").append(lore.lines().get(i).getString().replace("\"", "\\\"")).append("\"");
                }
                json.append("], ");
            }
        } catch (Exception ignored) {}
        
        if (json.toString().endsWith(", ")) {
            json.setLength(json.length() - 2);
        }
        json.append("}");
        
        return json.toString();
    }
    
    private String formatItemAsCSV(ItemStack stack) {
        if (stack.isEmpty()) return "\"\",0,\"\",\"\",\"\",\"\"";
        
        StringBuilder csv = new StringBuilder();
        csv.append("\"").append(stack.getItem().getName().getString().replace("\"", "\"\"")).append("\",");
        csv.append(stack.getCount()).append(",");
        csv.append("\"").append(stack.getRarity().toString()).append("\",");
        
        if (stack.isDamageable()) {
            csv.append("\"").append(stack.getMaxDamage() - stack.getDamage()).append("/").append(stack.getMaxDamage()).append("\",");
        } else {
            csv.append("\"\",");
        }
        
        if (stack.hasEnchantments()) {
            csv.append("\"").append(getEnchantmentString(stack).replace("\"", "\"\"")).append("\",");
        } else {
            csv.append("\"\",");
        }
        
        // Add simplified NBT/lore info
        try {
            NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
            if (customData != null && !customData.copyNbt().isEmpty()) {
                csv.append("\"Has NBT Data\"");
            } else {
                csv.append("\"\"");
            }
        } catch (Exception e) {
            csv.append("\"\"");
        }
        
        return csv.toString();
    }
    
    private String formatItemAsText(ItemStack stack) {
        if (stack.isEmpty()) return "Empty";
        
        StringBuilder text = new StringBuilder();
        text.append(stack.getItem().getName().getString());
        text.append(" x").append(stack.getCount());
        
        if (stack.isDamageable()) {
            text.append(" (").append(stack.getMaxDamage() - stack.getDamage()).append("/").append(stack.getMaxDamage()).append(")");
        }
        
        if (stack.hasEnchantments()) {
            text.append(" [").append(getEnchantmentString(stack)).append("]");
        }
        
        return text.toString();
    }
    
    private void saveToFile(String data, BlockPos pos, String containerType) {
        try {
            // Validate inputs
            if (data == null || data.trim().isEmpty()) {
                ChatUtils.error("No data to save.");
                return;
            }
            
            // Create directory if it doesn't exist
            Path saveDir = Paths.get(saveDirectory.get());
            if (!Files.exists(saveDir)) {
                Files.createDirectories(saveDir);
                ChatUtils.info("Created save directory: " + saveDir.toString());
            }
            
            if (singleFile.get() && saveFormat.get() == SaveFormat.CSV) {
                saveToCombinedCSV(data, pos, containerType, saveDir);
            } else {
                saveToSeparateFile(data, pos, containerType, saveDir);
            }
            
        } catch (Exception e) {
            ChatUtils.error("Unexpected error while saving: " + e.getMessage());
            if (debugMode.get()) {
                e.printStackTrace();
            }
        }
    }
    
    private void saveToCombinedCSV(String data, BlockPos pos, String containerType, Path saveDir) throws IOException {
        String filename = singleFileName.get() + ".csv";
        Path filePath = saveDir.resolve(filename);
        
        boolean fileExists = Files.exists(filePath);
        
        try (FileWriter writer = new FileWriter(filePath.toFile(), true)) { // Append mode
            // Add header if file doesn't exist
            if (!fileExists) {
                writer.write("Timestamp,Container_Type,Location_X,Location_Y,Location_Z,Slot,Name,Count,Rarity,Durability,Enchantments,NBT\n");
            }
            
            // Parse the data to extract items
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            String locationX = pos != null ? String.valueOf(pos.getX()) : "";
            String locationY = pos != null ? String.valueOf(pos.getY()) : "";
            String locationZ = pos != null ? String.valueOf(pos.getZ()) : "";
            
            // Extract item data from the formatted data string
            String[] lines = data.split("\n");
            int slotNumber = 1;
            
            for (String line : lines) {
                if (line.startsWith("Slot ") && line.contains(": ")) {
                    String itemData = line.substring(line.indexOf(": ") + 2);
                    
                    // Write timestamp and location info before each item
                    writer.write(String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%d,%s\n",
                        timestamp, containerType, locationX, locationY, locationZ, slotNumber, itemData));
                    slotNumber++;
                }
            }
        }
        
        ChatUtils.info("Scan results appended to: " + filename);
        
        if (debugMode.get()) {
            ChatUtils.info("Full path: " + filePath.toAbsolutePath().toString());
        }
    }
    
    private void saveToSeparateFile(String data, BlockPos pos, String containerType, Path saveDir) throws IOException {
        // Generate filename
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String positionStr = pos != null ? String.format("_%d_%d_%d", pos.getX(), pos.getY(), pos.getZ()) : "";
        String extension = "." + saveFormat.get().toString().toLowerCase();
        String filename = containerType + "_" + timestamp + positionStr + extension;
        
        Path filePath = saveDir.resolve(filename);
        
        // Add CSV header if needed
        if (saveFormat.get() == SaveFormat.CSV) {
            data = "Name,Count,Rarity,Durability,Enchantments,NBT\n" + data;
        }
        
        // Write to file
        try (FileWriter writer = new FileWriter(filePath.toFile())) {
            writer.write(data);
        }
        
        ChatUtils.info("Scan results saved to: " + filePath.getFileName().toString());
        
        if (debugMode.get()) {
            ChatUtils.info("Full path: " + filePath.toAbsolutePath().toString());
            ChatUtils.info("Data length: " + data.length() + " characters");
        }
    }
}