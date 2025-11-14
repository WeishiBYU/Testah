package com.example.addon.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import com.example.addon.modules.ChestScanner;
import net.minecraft.command.CommandSource;

public class ChestScanCommand extends Command {
    public ChestScanCommand() {
        super("chest-scan", "Manually scan the chest you're looking at.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            ChestScanner chestScanner = Modules.get().get(ChestScanner.class);
            if (chestScanner == null) {
                error("ChestScanner module not found.");
                return SINGLE_SUCCESS;
            }
            
            chestScanner.manualScan();
            return SINGLE_SUCCESS;
        });
    }
}