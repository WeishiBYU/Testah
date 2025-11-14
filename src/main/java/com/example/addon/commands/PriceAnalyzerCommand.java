package com.example.addon.commands;

import com.example.addon.modules.PriceAnalyzer;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.CommandSource;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class PriceAnalyzerCommand extends Command {
    
    public PriceAnalyzerCommand() {
        super("price-analyzer", "Commands for the Price Analyzer module", "pa");
    }
    
    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("analyze")
            .executes(context -> {
                try {
                    PriceAnalyzer analyzer = Modules.get().get(PriceAnalyzer.class);
                    if (analyzer == null) {
                        error("Price Analyzer module not found!");
                        return SINGLE_SUCCESS;
                    }
                    
                    info("Starting price analysis...");
                    analyzer.analyzeCSV();
                    info("Analysis command completed.");
                } catch (Exception e) {
                    error("Analysis failed: " + e.getMessage());
                    e.printStackTrace();
                }
                return SINGLE_SUCCESS;
            }))
            .then(literal("display")
                .executes(context -> {
                    PriceAnalyzer analyzer = Modules.get().get(PriceAnalyzer.class);
                    if (analyzer == null) {
                        error("Price Analyzer module not found!");
                        return SINGLE_SUCCESS;
                    }
                    
                    analyzer.displayResults();
                    return SINGLE_SUCCESS;
                }))
            .then(literal("export")
                .executes(context -> {
                    PriceAnalyzer analyzer = Modules.get().get(PriceAnalyzer.class);
                    if (analyzer == null) {
                        error("Price Analyzer module not found!");
                        return SINGLE_SUCCESS;
                    }
                    
                    analyzer.exportToCSV();
                    return SINGLE_SUCCESS;
                }))
            .then(literal("price")
                .then(argument("item", StringArgumentType.greedyString())
                    .executes(context -> {
                        PriceAnalyzer analyzer = Modules.get().get(PriceAnalyzer.class);
                        if (analyzer == null) {
                            error("Price Analyzer module not found!");
                            return SINGLE_SUCCESS;
                        }
                        
                        String itemName = StringArgumentType.getString(context, "item");
                        analyzer.getItemPrice(itemName);
                        return SINGLE_SUCCESS;
                    })))
            .then(literal("check-hand")
                .executes(context -> {
                    PriceAnalyzer analyzer = Modules.get().get(PriceAnalyzer.class);
                    if (analyzer == null) {
                        error("Price Analyzer module not found!");
                        return SINGLE_SUCCESS;
                    }
                    
                    // Check price of item in hand
                    net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
                    if (mc.player != null && !mc.player.getMainHandStack().isEmpty()) {
                        String itemName = mc.player.getMainHandStack().getItem().getName().getString();
                        info("Checking price for: " + itemName);
                        analyzer.getItemPrice(itemName);
                    } else {
                        error("No item in hand!");
                    }
                    return SINGLE_SUCCESS;
                }))
            .then(literal("status")
                .executes(context -> {
                    PriceAnalyzer analyzer = Modules.get().get(PriceAnalyzer.class);
                    if (analyzer == null) {
                        error("Price Analyzer module not found!");
                        return SINGLE_SUCCESS;
                    }
                    
                    analyzer.showStatus();
                    return SINGLE_SUCCESS;
                }))
            .then(literal("load-csv")
                .executes(context -> {
                    try {
                        PriceAnalyzer analyzer = Modules.get().get(PriceAnalyzer.class);
                        if (analyzer == null) {
                            error("Price Analyzer module not found!");
                            return SINGLE_SUCCESS;
                        }
                        
                        info("Loading CSV data...");
                        analyzer.loadFromCSV();
                        info("CSV load command completed.");
                    } catch (Exception e) {
                        error("CSV loading failed: " + e.getMessage());
                        e.printStackTrace();
                    }
                    
                    return SINGLE_SUCCESS;
                }))
            .then(literal("csv-price")
                .then(argument("item", StringArgumentType.greedyString())
                    .executes(context -> {
                        PriceAnalyzer analyzer = Modules.get().get(PriceAnalyzer.class);
                        if (analyzer == null) {
                            error("Price Analyzer module not found!");
                            return SINGLE_SUCCESS;
                        }
                        
                        String itemName = StringArgumentType.getString(context, "item");
                        checkCSVPrice(analyzer, itemName);
                        return SINGLE_SUCCESS;
                    })))
            .then(literal("test")
                .executes(context -> {
                    info("=== Price Analyzer Test ===");
                    info("Command system is working!");
                    
                    try {
                        PriceAnalyzer analyzer = Modules.get().get(PriceAnalyzer.class);
                        info("Module found: " + (analyzer != null ? "YES" : "NO"));
                        
                        if (analyzer != null) {
                            info("Module active: " + (analyzer.isActive() ? "YES" : "NO"));
                            info("Analysis data: " + (analyzer.hasAnalysisData() ? "AVAILABLE" : "NONE"));
                            info("CSV data: " + (analyzer.hasCSVData() ? "AVAILABLE" : "NONE"));
                            info("=== Test completed successfully! ===");
                        } else {
                            error("Module is null - this should not happen!");
                        }
                    } catch (Exception e) {
                        error("Test failed with error: " + e.getMessage());
                        e.printStackTrace();
                    }
                    
                    return SINGLE_SUCCESS;
                }))
            .then(literal("help")
                .executes(context -> {
                    info("=== Price Analyzer Commands ===");
                    info(".price-analyzer test - Test if everything is working");
                    info(".price-analyzer status - Show analyzer status and debug info");
                    info(".price-analyzer analyze - Analyze the CSV file");
                    info(".price-analyzer export - Export price analysis to CSV");
                    info(".price-analyzer load-csv - Load price data from exported CSV");
                    info(".price-analyzer display - Show all analyzed prices");
                    info(".price-analyzer price <item> - Get price for specific item");
                    info(".price-analyzer csv-price <item> - Get price from CSV data");
                    info(".price-analyzer check-hand - Check price of item in your hand");
                    info(".price-analyzer help - Show this help");
                    return SINGLE_SUCCESS;
                }))
            .executes(context -> {
                // When just .price-analyzer is used, show status instead
                PriceAnalyzer analyzer = Modules.get().get(PriceAnalyzer.class);
                if (analyzer == null) {
                    error("Price Analyzer module not found!");
                    return SINGLE_SUCCESS;
                }
                
                info("=== Price Analyzer Quick Status ===");
                analyzer.showStatus();
                info("Use '.price-analyzer help' for all commands.");
                return SINGLE_SUCCESS;
            });
    }
    
    private void checkCSVPrice(PriceAnalyzer analyzer, String itemInput) {
        if (!analyzer.hasCSVData()) {
            error("No CSV data loaded! Use '/pa load-csv' first.");
            return;
        }
        
        // Try different rarity combinations
        String[] rarities = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY"};
        PriceAnalyzer.ItemPriceData found = null;
        
        for (String rarity : rarities) {
            found = analyzer.getCSVItemPrice(itemInput, rarity);
            if (found != null) {
                break;
            }
        }
        
        if (found != null) {
            info("=== CSV PRICE DATA ===");
            info("Item: " + found.itemName + " [" + found.rarity + "]");
            info("Average Price: " + formatPrice(found.getAveragePrice()));
            info("Sample Count: " + found.getSampleCount());
            info("Source: Exported CSV Data");
        } else {
            warning("Item '" + itemInput + "' not found in CSV data");
            info("Use '/pa status' to see loaded items");
        }
    }
    
    private String formatPrice(double price) {
        if (price >= 1000000) {
            return String.format("%.2fM", price / 1000000.0);
        } else if (price >= 1000) {
            return String.format("%.2fK", price / 1000.0);
        } else {
            return String.format("%.2f", price);
        }
    }
    
    private void warning(String message) {
        ChatUtils.warning("Price Analyzer", message);
    }
    
    private void info(String message) {
        ChatUtils.info("Price Analyzer", message);
    }
    
    private void error(String message) {
        ChatUtils.error("Price Analyzer", message);
    }
}