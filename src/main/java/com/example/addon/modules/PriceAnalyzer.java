package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PriceAnalyzer extends Module {
    
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilters = settings.createGroup("Filters");
    private final SettingGroup sgDisplay = settings.createGroup("Display");
    private final SettingGroup sgExport = settings.createGroup("Export");
    
    // General settings
    private final Setting<String> csvFilePath = sgGeneral.add(new StringSetting.Builder()
        .name("csv-file-path")
        .description("Path to the chest scans CSV file")
        .defaultValue("chest_scans/all_chest_scans.csv")
        .build()
    );
    
    private final Setting<Boolean> excludeDuplicates = sgGeneral.add(new BoolSetting.Builder()
        .name("exclude-duplicates")
        .description("Exclude duplicate entries (same item, same seller, same price)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> divideByCount = sgGeneral.add(new BoolSetting.Builder()
        .name("divide-by-count")
        .description("Divide total price by item count to get price per item")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Integer> countColumnIndex = sgGeneral.add(new IntSetting.Builder()
        .name("count-column-index")
        .description("CSV column index for item count (0-based indexing)")
        .defaultValue(8)
        .min(0)
        .max(20)
        .build()
    );
    
    private final Setting<Integer> minSampleSize = sgGeneral.add(new IntSetting.Builder()
        .name("min-sample-size")
        .description("Minimum number of samples required to show average price")
        .defaultValue(2)
        .min(1)
        .max(100)
        .build()
    );
    
    // Filter settings
    private final Setting<String> itemNameFilter = sgFilters.add(new StringSetting.Builder()
        .name("item-filter")
        .description("Filter items by name (case insensitive, partial match)")
        .defaultValue("")
        .build()
    );
    
    private final Setting<Double> minPrice = sgFilters.add(new DoubleSetting.Builder()
        .name("min-price")
        .description("Minimum price filter (0 to disable)")
        .defaultValue(0.0)
        .min(0.0)
        .build()
    );
    
    private final Setting<Double> maxPrice = sgFilters.add(new DoubleSetting.Builder()
        .name("max-price")
        .description("Maximum price to include (0 = no limit)")
        .defaultValue(0.0)
        .min(0.0)
        .build()
    );
    
    private final Setting<Boolean> enableOutlierDetection = sgFilters.add(new BoolSetting.Builder()
        .name("enable-outlier-detection")
        .description("Remove outliers using statistical methods")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Double> outlierMultiplier = sgFilters.add(new DoubleSetting.Builder()
        .name("outlier-multiplier")
        .description("IQR multiplier for outlier detection (1.5 = standard, 3.0 = conservative)")
        .defaultValue(1.5)
        .min(0.5)
        .max(5.0)
        .build()
    );
    
    private final Setting<Integer> minSampleForOutlierDetection = sgFilters.add(new IntSetting.Builder()
        .name("min-samples-for-outliers")
        .description("Minimum samples required before applying outlier detection")
        .defaultValue(5)
        .min(3)
        .max(50)
        .build()
    );
    
    private final Setting<String> rarityFilter = sgFilters.add(new StringSetting.Builder()
        .name("rarity-filter")
        .description("Filter by item rarity (comma separated: COMMON,RARE,EPIC)")
        .defaultValue("")
        .build()
    );
    
    // Display settings
    private final Setting<Integer> maxResults = sgDisplay.add(new IntSetting.Builder()
        .name("max-results")
        .description("Maximum number of results to display")
        .defaultValue(20)
        .min(1)
        .max(100)
        .build()
    );
    
    private final Setting<Boolean> sortByPrice = sgDisplay.add(new BoolSetting.Builder()
        .name("sort-by-price")
        .description("Sort results by average price (highest first)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> showSampleCount = sgDisplay.add(new BoolSetting.Builder()
        .name("show-sample-count")
        .description("Show number of samples used in calculation")
        .defaultValue(true)
        .build()
    );
    
    // Export settings
    private final Setting<Boolean> autoExportResults = sgExport.add(new BoolSetting.Builder()
        .name("auto-export-results")
        .description("Automatically export price analysis to CSV after each analysis")
        .defaultValue(false)
        .build()
    );
    
    private final Setting<String> exportFilePath = sgExport.add(new StringSetting.Builder()
        .name("export-file-path")
        .description("Path for exported price analysis CSV")
        .defaultValue("chest_scans/price_analysis.csv")
        .build()
    );
    
    private final Setting<Boolean> includeTimestamps = sgExport.add(new BoolSetting.Builder()
        .name("include-timestamps")
        .description("Include analysis timestamp in exported data")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> appendToFile = sgExport.add(new BoolSetting.Builder()
        .name("append-to-file")
        .description("Append new analysis to existing file instead of overwriting")
        .defaultValue(true)
        .build()
    );
    
    // Data storage
    private Map<String, ItemPriceData> analyzedPrices = new HashMap<>();
    private Map<String, ItemPriceData> csvLoadedPrices = new HashMap<>();
    private long lastAnalysisTime = 0;
    private boolean analysisInProgress = false;
    private boolean debugMode = false; // For error reporting
    
    public PriceAnalyzer() {
        super(AddonTemplate.CATEGORY, "price-analyzer", "Analyzes chest scan data to calculate average item prices");
    }
    
        
    @Override
    public void onActivate() {
        info("Price Analyzer activated. Use commands '.price-analyzer analyze' and '.price-analyzer display' to analyze prices.");
    }
    
    @Override
    public void onDeactivate() {
        analyzedPrices.clear();
    }    // Price data storage class
    public static class ItemPriceData {
        public String itemName;
        public String rarity;
        List<Double> prices;
        Set<String> uniqueEntries; // For duplicate detection
        double averagePrice;
        int sampleCount;
        
        public ItemPriceData(String itemName, String rarity) {
            this.itemName = itemName;
            this.rarity = rarity;
            this.prices = new ArrayList<>();
            this.uniqueEntries = new HashSet<>();
        }
        
        public void addPrice(double price, String seller, String uniqueId) {
            String entryKey = itemName + "|" + seller + "|" + String.format("%.2f", price);
            if (!uniqueEntries.contains(entryKey)) {
                prices.add(price);
                uniqueEntries.add(entryKey);
            }
        }
        
        public void calculateAverage() {
            if (!prices.isEmpty()) {
                averagePrice = prices.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                sampleCount = prices.size();
            }
        }
        
        public void calculateAverageWithOutlierDetection(boolean enableOutlierDetection, double outlierMultiplier, int minSamples) {
            if (prices.isEmpty()) {
                averagePrice = 0;
                sampleCount = 0;
                return;
            }
            
            List<Double> workingPrices = new ArrayList<>(prices);
            sampleCount = workingPrices.size();
            
            // Apply outlier detection if enabled and we have enough samples
            if (enableOutlierDetection && workingPrices.size() >= minSamples) {
                workingPrices = removeOutliers(workingPrices, outlierMultiplier);
            }
            
            // Calculate average
            if (!workingPrices.isEmpty()) {
                double sum = workingPrices.stream().mapToDouble(Double::doubleValue).sum();
                averagePrice = sum / workingPrices.size();
            } else {
                // If all values were outliers, use original data
                double sum = prices.stream().mapToDouble(Double::doubleValue).sum();
                averagePrice = sum / prices.size();
            }
        }
        
        private List<Double> removeOutliers(List<Double> data, double multiplier) {
            if (data.size() < 4) return data; // Need at least 4 points for quartile calculation
            
            // Sort the data
            List<Double> sortedData = new ArrayList<>(data);
            sortedData.sort(Double::compareTo);
            
            // Calculate quartiles
            int n = sortedData.size();
            double q1 = getQuartile(sortedData, 0.25);
            double q3 = getQuartile(sortedData, 0.75);
            double iqr = q3 - q1;
            
            // Calculate outlier bounds
            double lowerBound = q1 - (multiplier * iqr);
            double upperBound = q3 + (multiplier * iqr);
            
            // Filter outliers
            List<Double> filtered = data.stream()
                .filter(price -> price >= lowerBound && price <= upperBound)
                .collect(java.util.stream.Collectors.toList());
            
            return filtered.isEmpty() ? data : filtered; // Return original if all were outliers
        }
        
        private double getQuartile(List<Double> sortedData, double percentile) {
            int n = sortedData.size();
            double index = percentile * (n - 1);
            int lowerIndex = (int) Math.floor(index);
            int upperIndex = (int) Math.ceil(index);
            
            if (lowerIndex == upperIndex) {
                return sortedData.get(lowerIndex);
            } else {
                double weight = index - lowerIndex;
                return sortedData.get(lowerIndex) * (1 - weight) + sortedData.get(upperIndex) * weight;
            }
        }
        
        public double getAveragePrice() { return averagePrice; }
        public int getSampleCount() { return sampleCount; }
    }
    
    // Parse price from lore string
    private double parsePrice(String loreString) {
        if (loreString == null || loreString.isEmpty()) return 0.0;
        
        // Pattern to match "Price: $XXX" format with optional decimal and K/M suffixes
        Pattern pricePattern = Pattern.compile("Price: \\$([0-9,]+(?:\\.[0-9]+)?)([KkMm]?)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pricePattern.matcher(loreString);
        
        if (matcher.find()) {
            try {
                String numberStr = matcher.group(1).replace(",", "");
                double price = Double.parseDouble(numberStr);
                String suffix = matcher.group(2);
                
                if (suffix != null && !suffix.isEmpty()) {
                    switch (suffix.toLowerCase()) {
                        case "k":
                            price *= 1000;
                            break;
                        case "m":
                            price *= 1000000;
                            break;
                    }
                }
                
                return price;
            } catch (NumberFormatException e) {
                // Invalid price format, return 0
                return 0.0;
            }
        }
        
        return 0.0;
    }
    
    // Analyze CSV file
    public void analyzeCSV() {
        if (analysisInProgress) {
            warning("Analysis already in progress...");
            return;
        }
        
        analysisInProgress = true;
        analyzedPrices.clear();
        
        try {
            Path csvPath = Paths.get(System.getProperty("user.home"), "AppData", "Roaming", ".minecraft", csvFilePath.get());
            
            info("Looking for CSV file at: " + csvPath.toString());
            
            if (!csvPath.toFile().exists()) {
                error("CSV file not found: " + csvPath);
                error("Make sure ChestScanner has saved some data first!");
                analysisInProgress = false;
                return;
            }
            
            info("Analyzing CSV file: " + csvPath);
            
            Map<String, ItemPriceData> tempData = new HashMap<>();
            int totalEntries = 0;
            int validEntries = 0;
            
            try (BufferedReader reader = new BufferedReader(new FileReader(csvPath.toFile()))) {
                String line;
                boolean isFirstLine = true;
                
                while ((line = reader.readLine()) != null) {
                    if (isFirstLine) {
                        isFirstLine = false;
                        continue; // Skip header
                    }
                    
                    totalEntries++;
                    
                    // Parse CSV line (handle quoted values)
                    String[] parts = parseCSVLine(line);
                    if (parts.length < 13) continue;
                    
                    try {
                        String itemName = parts[7]; // Name column
                        String rarity = parts[9]; // Rarity column
                        String lore = parts[12]; // Lore column
                        
                        // Extract count if division is enabled
                        int count = 1; // Default to 1 if not dividing or can't parse
                        if (divideByCount.get()) {
                            try {
                                int countCol = countColumnIndex.get();
                                if (parts.length > countCol && !parts[countCol].isEmpty()) {
                                    count = Integer.parseInt(parts[countCol].trim());
                                    if (count <= 0) count = 1; // Ensure count is positive
                                }
                            } catch (NumberFormatException e) {
                                // If count parsing fails, use 1
                                count = 1;
                            }
                        }
                        
                        // Apply filters
                        if (!itemNameFilter.get().isEmpty() && 
                            !itemName.toLowerCase().contains(itemNameFilter.get().toLowerCase())) {
                            continue;
                        }
                        
                        if (!rarityFilter.get().isEmpty()) {
                            Set<String> allowedRarities = Set.of(rarityFilter.get().split(","));
                            if (!allowedRarities.contains(rarity)) continue;
                        }
                        
                        double totalPrice = parsePrice(lore);
                        if (totalPrice <= 0) continue;
                        
                        // Calculate final price (per item if division is enabled)
                        double finalPrice = divideByCount.get() ? (totalPrice / count) : totalPrice;
                        
                        // Apply price filters to final price
                        if (minPrice.get() > 0 && finalPrice < minPrice.get()) continue;
                        if (maxPrice.get() > 0 && finalPrice > maxPrice.get()) continue;
                        
                        // Extract seller from lore
                        String seller = extractSeller(lore);
                        
                        // Add to analysis data using final price
                        String key = itemName + "|" + rarity;
                        ItemPriceData data = tempData.computeIfAbsent(key, k -> new ItemPriceData(itemName, rarity));
                        
                        if (excludeDuplicates.get()) {
                            data.addPrice(finalPrice, seller, parts[0]); // Use timestamp as unique ID
                        } else {
                            data.prices.add(finalPrice);
                        }
                        
                        // Debug output for price calculation
                        if (debugMode && divideByCount.get() && count > 1) {
                            info(String.format("Item: %s, Total: $%.2f, Count: %d, Per Item: $%.2f", 
                                itemName, totalPrice, count, finalPrice));
                        }
                        
                        validEntries++;
                        
                    } catch (Exception e) {
                        // Skip invalid lines
                        continue;
                    }
                }
            }
            
            // Calculate averages with outlier detection and filter by sample size
            int outlierRemovedCount = 0;
            for (ItemPriceData data : tempData.values()) {
                int originalSize = data.prices.size();
                data.calculateAverageWithOutlierDetection(
                    enableOutlierDetection.get(), 
                    outlierMultiplier.get(), 
                    minSampleForOutlierDetection.get()
                );
                
                if (enableOutlierDetection.get() && originalSize > data.prices.size()) {
                    outlierRemovedCount += (originalSize - data.prices.size());
                }
                
                if (data.getSampleCount() >= minSampleSize.get()) {
                    String key = data.itemName + "|" + data.rarity;
                    analyzedPrices.put(key, data);
                }
            }
            
            lastAnalysisTime = System.currentTimeMillis();
            
            String outlierInfo = enableOutlierDetection.get() ? 
                String.format(" (%d outliers removed)", outlierRemovedCount) : "";
            
            info(String.format("Analysis complete! Processed %d entries (%d valid), found %d unique items%s", 
                totalEntries, validEntries, analyzedPrices.size(), outlierInfo));
            
            // Auto-export if enabled
            if (autoExportResults.get()) {
                exportToCSV();
            }
            
        } catch (IOException e) {
            error("Failed to read CSV file: " + e.getMessage());
        } finally {
            analysisInProgress = false;
        }
    }
    
    // Parse CSV line handling quoted values
    private String[] parseCSVLine(String line) {
        List<String> result = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        
        result.add(current.toString());
        return result.toArray(new String[0]);
    }
    
    // Extract seller from lore
    private String extractSeller(String lore) {
        Pattern sellerPattern = Pattern.compile("Seller: ([^|]+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = sellerPattern.matcher(lore);
        return matcher.find() ? matcher.group(1).trim() : "Unknown";
    }
    
    // Display results in chat
    public void displayResults() {
        if (analyzedPrices.isEmpty()) {
            warning("No price data available. Run analysis first!");
            return;
        }
        
        List<ItemPriceData> sortedResults = new ArrayList<>(analyzedPrices.values());
        
        if (sortByPrice.get()) {
            sortedResults.sort((a, b) -> Double.compare(b.getAveragePrice(), a.getAveragePrice()));
        } else {
            sortedResults.sort(Comparator.comparing(a -> a.itemName));
        }
        
        info("=== PRICE ANALYSIS RESULTS ===");
        
        int count = 0;
        for (ItemPriceData data : sortedResults) {
            if (count >= maxResults.get()) break;
            
            String priceStr = formatPrice(data.getAveragePrice());
            String sampleInfo = showSampleCount.get() ? String.format(" (%d samples)", data.getSampleCount()) : "";
            
            info(String.format("%s [%s]: %s%s", 
                data.itemName, data.rarity, priceStr, sampleInfo));
            
            count++;
        }
        
        if (sortedResults.size() > maxResults.get()) {
            info(String.format("... and %d more items", sortedResults.size() - maxResults.get()));
        }
    }
    
    // Format price with appropriate suffix
    private String formatPrice(double price) {
        if (price >= 1000000) {
            return String.format("$%.2fM", price / 1000000);
        } else if (price >= 1000) {
            return String.format("$%.2fK", price / 1000);
        } else {
            return String.format("$%.2f", price);
        }
    }
    
    // Get specific item price
    public void getItemPrice(String itemName) {
        if (analyzedPrices.isEmpty()) {
            warning("No price data available. Run analysis first!");
            return;
        }
        
        List<ItemPriceData> matches = analyzedPrices.values().stream()
            .filter(data -> data.itemName.toLowerCase().contains(itemName.toLowerCase()))
            .sorted((a, b) -> Double.compare(b.getAveragePrice(), a.getAveragePrice()))
            .toList();
        
        if (matches.isEmpty()) {
            warning("No price data found for: " + itemName);
            return;
        }
        
        info("=== PRICE DATA FOR: " + itemName + " ===");
        info("Found " + matches.size() + " matching items:");
        for (ItemPriceData data : matches) {
            String priceStr = formatPrice(data.getAveragePrice());
            String sampleInfo = String.format(" (%d samples)", data.getSampleCount());
            
            info(String.format("%s [%s]: %s%s", 
                data.itemName, data.rarity, priceStr, sampleInfo));
        }
    }
    
    // Public method to get price data for external access
    public ItemPriceData getPriceData(String itemName, String rarity) {
        if (analyzedPrices.isEmpty()) return null;
        
        String key = itemName + "|" + rarity;
        ItemPriceData data = analyzedPrices.get(key);
        
        if (data == null) {
            // Try to find by item name only
            data = analyzedPrices.values().stream()
                .filter(priceData -> priceData.itemName.equals(itemName))
                .findFirst()
                .orElse(null);
        }
        
        return data;
    }
    
    // Export price analysis results to CSV
    public void exportToCSV() {
        if (analyzedPrices.isEmpty()) {
            warning("No price data to export. Run analysis first!");
            return;
        }
        
        try {
            Path exportPath = Paths.get(System.getProperty("user.home"), "AppData", "Roaming", ".minecraft", exportFilePath.get());
            
            // Create directory if it doesn't exist
            Path parentDir = exportPath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            
            boolean fileExists = Files.exists(exportPath);
            boolean shouldAppend = appendToFile.get() && fileExists;
            
            try (FileWriter writer = new FileWriter(exportPath.toFile(), shouldAppend)) {
                // Write header if new file or not appending
                if (!shouldAppend) {
                    if (includeTimestamps.get()) {
                        writer.write("Analysis_Timestamp,Item_Name,Rarity,Average_Price,Sample_Count,Min_Price,Max_Price,Last_Updated\n");
                    } else {
                        writer.write("Item_Name,Rarity,Average_Price,Sample_Count,Min_Price,Max_Price\n");
                    }
                }
                
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                
                // Sort results by price (highest first)
                List<ItemPriceData> sortedResults = new ArrayList<>(analyzedPrices.values());
                sortedResults.sort((a, b) -> Double.compare(b.getAveragePrice(), a.getAveragePrice()));
                
                // Write data
                for (ItemPriceData data : sortedResults) {
                    double minPrice = data.prices.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
                    double maxPrice = data.prices.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
                    
                    if (includeTimestamps.get()) {
                        writer.write(String.format("\"%s\",\"%s\",\"%s\",%.2f,%d,%.2f,%.2f,\"%s\"\n",
                            timestamp,
                            data.itemName.replace("\"", "\"\""),
                            data.rarity,
                            data.getAveragePrice(),
                            data.getSampleCount(),
                            minPrice,
                            maxPrice,
                            timestamp
                        ));
                    } else {
                        writer.write(String.format("\"%s\",\"%s\",%.2f,%d,%.2f,%.2f\n",
                            data.itemName.replace("\"", "\"\""),
                            data.rarity,
                            data.getAveragePrice(),
                            data.getSampleCount(),
                            minPrice,
                            maxPrice
                        ));
                    }
                }
                
                writer.flush();
            }
            
            info("Price analysis exported to: " + exportPath.getFileName().toString());
            info("Exported " + analyzedPrices.size() + " items");
            info("File mode: " + (shouldAppend ? "APPEND" : "OVERWRITE"));
            
        } catch (IOException e) {
            error("Failed to export price analysis: " + e.getMessage());
            if (debugMode) {
                e.printStackTrace();
            }
        }
    }
    
    // Load price data from exported CSV file
    public void loadFromCSV() {
        csvLoadedPrices.clear();
        
        try {
            Path csvPath = Paths.get(System.getProperty("user.home"), "AppData", "Roaming", ".minecraft", exportFilePath.get());
            
            if (!Files.exists(csvPath)) {
                warning("No exported CSV file found at: " + csvPath.getFileName().toString());
                return;
            }
            
            try (BufferedReader reader = new BufferedReader(new FileReader(csvPath.toFile()))) {
                String line;
                boolean isFirstLine = true;
                int loadedCount = 0;
                
                while ((line = reader.readLine()) != null) {
                    // Skip header line
                    if (isFirstLine) {
                        isFirstLine = false;
                        continue;
                    }
                    
                    // Parse CSV line
                    String[] parts = parseCSVLine(line);
                    if (parts.length >= 6) { // Minimum required fields
                        try {
                            String itemName;
                            String rarity;
                            double avgPrice;
                            int sampleCount;
                            double minPrice;
                            double maxPrice;
                            
                            if (includeTimestamps.get() && parts.length >= 8) {
                                // With timestamps: timestamp,item,rarity,avg,samples,min,max,updated
                                itemName = parts[1];
                                rarity = parts[2];
                                avgPrice = Double.parseDouble(parts[3]);
                                sampleCount = Integer.parseInt(parts[4]);
                                minPrice = Double.parseDouble(parts[5]);
                                maxPrice = Double.parseDouble(parts[6]);
                            } else {
                                // Without timestamps: item,rarity,avg,samples,min,max
                                itemName = parts[0];
                                rarity = parts[1];
                                avgPrice = Double.parseDouble(parts[2]);
                                sampleCount = Integer.parseInt(parts[3]);
                                minPrice = Double.parseDouble(parts[4]);
                                maxPrice = Double.parseDouble(parts[5]);
                            }
                            
                            // Create ItemPriceData from CSV
                            ItemPriceData data = new ItemPriceData(itemName, rarity);
                            // Set the average by adding prices that would create this average
                            for (int i = 0; i < sampleCount; i++) {
                                data.addPrice(avgPrice, "CSV", "Loaded"); // Approximation
                            }
                            
                            String key = (itemName + "_" + rarity).toLowerCase();
                            csvLoadedPrices.put(key, data);
                            loadedCount++;
                            
                        } catch (NumberFormatException e) {
                            if (debugMode) {
                                warning("Failed to parse CSV line: " + line);
                            }
                        }
                    }
                }
                
                info("Loaded " + loadedCount + " items from CSV: " + csvPath.getFileName().toString());
                info("Use 'csv-price <item>' to check prices from loaded CSV data");
                
            }
        } catch (IOException e) {
            error("Failed to load CSV file: " + e.getMessage());
            if (debugMode) {
                e.printStackTrace();
            }
        }
    }
    
    // Public method to check if analysis data is available
    public boolean hasAnalysisData() {
        return !analyzedPrices.isEmpty();
    }
    
    // Public method to check if CSV data is available
    public boolean hasCSVData() {
        return !csvLoadedPrices.isEmpty();
    }
    
    // Check analyzer status and data
    public void showStatus() {
        info("=== PRICE ANALYZER STATUS ===");
        info("Analysis data loaded: " + (analyzedPrices.isEmpty() ? "NO" : "YES"));
        info("Number of unique items: " + analyzedPrices.size());
        info("CSV file path: " + csvFilePath.get());
        info("Price division enabled: " + (divideByCount.get() ? "YES (Column " + countColumnIndex.get() + ")" : "NO"));
        info("Outlier detection: " + (enableOutlierDetection.get() ? 
            "YES (IQR × " + outlierMultiplier.get() + ", min " + minSampleForOutlierDetection.get() + " samples)" : "NO"));
        
        Path csvPath = Paths.get(System.getProperty("user.home"), "AppData", "Roaming", ".minecraft", csvFilePath.get());
        info("CSV file exists: " + (csvPath.toFile().exists() ? "YES" : "NO"));
        info("Full CSV path: " + csvPath.toString());
        
        if (csvPath.toFile().exists()) {
            info("CSV file size: " + csvPath.toFile().length() + " bytes");
        }
        
        info("Last analysis time: " + (lastAnalysisTime > 0 ? new java.util.Date(lastAnalysisTime).toString() : "Never"));
        
        if (!analyzedPrices.isEmpty()) {
            info("Sample items found:");
            analyzedPrices.values().stream().limit(3).forEach(data -> 
                info("  - " + data.itemName + " [" + data.rarity + "]: " + formatPrice(data.getAveragePrice()))
            );
        }
        
        info("CSV data loaded: " + (csvLoadedPrices.isEmpty() ? "NO" : "YES"));
        info("CSV items count: " + csvLoadedPrices.size());
        if (!csvLoadedPrices.isEmpty()) {
            info("Sample CSV items:");
            csvLoadedPrices.values().stream().limit(3).forEach(data -> 
                info("  - " + data.itemName + " [" + data.rarity + "]: " + formatPrice(data.getAveragePrice()))
            );
        }
    }
    
    // Get item price data for external access
    public ItemPriceData getItemPrice(String itemName, String rarity) {
        String key = (itemName + "_" + rarity).toLowerCase();
        
        // First try current analysis data
        ItemPriceData data = analyzedPrices.get(key);
        if (data != null) {
            return data;
        }
        
        // Fallback to CSV loaded data
        return csvLoadedPrices.get(key);
    }
    
    // Get item price data from CSV only
    public ItemPriceData getCSVItemPrice(String itemName, String rarity) {
        String key = (itemName + "_" + rarity).toLowerCase();
        return csvLoadedPrices.get(key);
    }
    
    private void info(String message) {
        meteordevelopment.meteorclient.utils.player.ChatUtils.info("[Price Analyzer] " + message);
    }
    
    private void warning(String message) {
        meteordevelopment.meteorclient.utils.player.ChatUtils.warning("[Price Analyzer] " + message);
    }
    
    private void error(String message) {
        meteordevelopment.meteorclient.utils.player.ChatUtils.error("[Price Analyzer] " + message);
    }
}